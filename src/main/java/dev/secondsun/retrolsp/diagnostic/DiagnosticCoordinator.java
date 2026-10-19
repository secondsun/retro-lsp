package dev.secondsun.retrolsp.diagnostic;

import dev.secondsun.lsp.Diagnostic;
import dev.secondsun.lsp.LanguageClient;
import dev.secondsun.lsp.Position;
import dev.secondsun.lsp.PublishDiagnosticsParams;
import dev.secondsun.lsp.Range;
import dev.secondsun.retro.util.CA65Scanner;
import dev.secondsun.retro.util.ProjectService;
import dev.secondsun.retro.util.Token;
import dev.secondsun.retro.util.diagnostic.SyntaxDiagnostic;
import dev.secondsun.retro.util.diagnostic.SyntaxValidator;
import dev.secondsun.retro.util.vo.TokenizedFile;
import dev.secondsun.retro.util.vo.Tokens;
import dev.secondsun.retrolsp.mcp.tools.SfxInvariantAuditor;
import dev.secondsun.sfxoptimizer.graphbuilder.CA65Grapher;
import dev.secondsun.sfxoptimizer.graphnode.CodeGraph;
import dev.secondsun.sfxoptimizer.graphnode.CodeNode;
import dev.secondsun.sfxoptimizer.macro.CA65MacroExpander;
import dev.secondsun.sfxoptimizer.stack.StackDepthTracker;
import dev.secondsun.sfxoptimizer.stack.StackViolation;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Diagnostic Coordinator synthesizing diagnostics from three layers: 1. Syntax and Language
 * Invariants (retro-common SyntaxValidator) 2. FX3 Hardware Invariants (SfxInvariantAuditor) 3.
 * Stack Displacement and Calling Convention Invariants (sfx-optimizer StackDepthTracker)
 *
 * <p>Emits diagnostics to connected LSP LanguageClient and formats results for MCP tools.
 */
public class DiagnosticCoordinator {

  private static final Logger LOG = Logger.getLogger(DiagnosticCoordinator.class.getName());

  private final ProjectService projectService;
  private final LanguageClient client;
  private final SyntaxValidator syntaxValidator;
  private final SfxInvariantAuditor invariantAuditor;
  private final StackDepthTracker stackTracker;

  public DiagnosticCoordinator(ProjectService projectService, LanguageClient client) {
    this.projectService = Objects.requireNonNull(projectService);
    this.client = client;
    this.syntaxValidator = new SyntaxValidator();
    this.invariantAuditor = new SfxInvariantAuditor(this.projectService);
    this.stackTracker = new StackDepthTracker();
  }

  public DiagnosticCoordinator(ProjectService projectService) {
    this(projectService, null);
  }

  public ProjectService getProjectService() {
    return projectService;
  }

  public LanguageClient getLanguageClient() {
    return client;
  }

  /** Audits assembly source code or document URI and returns a unified DiagnosticResult. */
  public DiagnosticResult auditResult(String code, String uriStr) {
    TokenizedFile file = resolveFile(code, uriStr);
    if (file == null || file == TokenizedFile.EMPTY) {
      return new DiagnosticResult(true, List.of(), "Empty code: no diagnostics audited.");
    }
    List<DiagnosticRecord> violations = audit(file);
    boolean valid = violations.stream().noneMatch(v -> v.severity() == DiagnosticSeverity.ERROR);
    String summary =
        String.format(
            "SuperFX Diagnostics: %d violation(s) found (%s).",
            violations.size(), valid ? "PASSED" : "FAILED");
    return new DiagnosticResult(valid, violations, summary);
  }

  /** Audits a TokenizedFile and collects diagnostic records across all 3 layers. */
  public List<DiagnosticRecord> audit(TokenizedFile file) {
    if (file == null || file == TokenizedFile.EMPTY) {
      return List.of();
    }

    List<DiagnosticRecord> records = new ArrayList<>();

    // Layer 1: Syntax & Language Invariants (Block balancing, arity, duplicate symbols)
    try {
      List<SyntaxDiagnostic> syntaxErrors = syntaxValidator.validate(file);
      for (SyntaxDiagnostic sd : syntaxErrors) {
        DiagnosticSeverity sev =
            sd.severity() == dev.secondsun.retro.util.diagnostic.DiagnosticSeverity.ERROR
                ? DiagnosticSeverity.ERROR
                : DiagnosticSeverity.WARNING;
        int length = Math.max(1, sd.columnEnd() - sd.columnStart());
        records.add(
            new DiagnosticRecord(
                sd.line(),
                sd.columnStart() + 1,
                length,
                sd.code(),
                DiagnosticCategory.SYNTAX,
                sev,
                sd.message()));
      }
    } catch (Exception e) {
      LOG.log(Level.WARNING, "Syntax validation error: " + e.getMessage(), e);
    }

    // Layer 2: FX3 Hardware Invariants (8-NOP settling, 16-bit word alignment, no merge, modal
    // lifetimes)
    try {
      SfxInvariantAuditor.AuditResult invResult = invariantAuditor.audit(file);
      for (SfxInvariantAuditor.InvariantViolation iv : invResult.violations()) {
        DiagnosticSeverity sev =
            iv.severity() == SfxInvariantAuditor.Severity.ERROR
                ? DiagnosticSeverity.ERROR
                : DiagnosticSeverity.WARNING;
        records.add(
            new DiagnosticRecord(
                iv.line(),
                1,
                1,
                iv.rule(),
                DiagnosticCategory.HARDWARE_INVARIANT,
                sev,
                iv.message()));
      }
    } catch (Exception e) {
      LOG.log(Level.WARNING, "Hardware invariant audit error: " + e.getMessage(), e);
    }

    // Layer 3: Stack Displacement & Calling Convention Invariants
    try {
      TokenizedFile expanded = new CA65MacroExpander().expand(file);
      URI effectiveUri =
          expanded.uri() != null
              ? expanded.uri()
              : (file.uri() != null ? file.uri() : URI.create("file:///snippet.s"));
      expanded.uri = effectiveUri;

      dev.secondsun.retro.util.SymbolService symbolService =
          new dev.secondsun.retro.util.SymbolService();
      symbolService.extractDefinitions(expanded);

      dev.secondsun.retro.util.FileService fileService =
          new dev.secondsun.retro.util.FileService() {
            @Override
            public TokenizedFile readLines(URI targetUri) {
              if (targetUri != null && targetUri.equals(effectiveUri)) {
                return expanded;
              }
              TokenizedFile f = projectService.getFileContents(targetUri);
              if (f != null && f != TokenizedFile.EMPTY) {
                return f;
              }
              try {
                return super.readLines(targetUri);
              } catch (java.io.IOException e) {
                return TokenizedFile.EMPTY;
              }
            }
          };

      CA65Grapher grapher = new CA65Grapher(symbolService, fileService);
      CodeGraph graph = grapher.graph(expanded, 0);
      List<StackViolation> stackViolations = new ArrayList<>(stackTracker.auditStack(graph));

      // Audit any functions defined in the file
      for (int i = 0; i < expanded.textLines(); i++) {
        Tokens lineToks = expanded.getLine(i);
        if (lineToks != null && !lineToks.tokens().isEmpty()) {
          Token first = lineToks.tokens().get(0);
          if (first.type == dev.secondsun.retro.util.TokenType.TOK_FUNCTION
              || "function".equalsIgnoreCase(first.text())) {
            if (lineToks.tokens().size() >= 2) {
              String fnName = lineToks.tokens().get(1).text();
              try {
                CodeNode.FunctionStart fnNode = grapher.graphFunction(fnName);
                if (fnNode != null) {
                  stackViolations.addAll(stackTracker.track(fnNode, false).getViolations());
                }
              } catch (Throwable ignore) {
              }
            }
          }
        }
      }

      for (StackViolation sv : stackViolations) {
        records.add(
            new DiagnosticRecord(
                Math.max(1, sv.getLine()),
                1,
                1,
                sv.getRule(),
                DiagnosticCategory.STACK_INVARIANT,
                DiagnosticSeverity.ERROR,
                sv.getMessage()));
      }
    } catch (Throwable t) {
      LOG.log(Level.FINE, "Stack displacement tracking skipped: " + t.getMessage(), t);
    }

    records.sort(
        Comparator.comparingInt(DiagnosticRecord::line).thenComparingInt(DiagnosticRecord::column));
    return records;
  }

  /** Collects LSP Diagnostic objects for a given workspace document URI. */
  public List<Diagnostic> collectLspDiagnostics(URI uri) {
    if (uri == null) {
      return List.of();
    }
    TokenizedFile file = projectService.getFileContents(uri);
    if (file == null || file == TokenizedFile.EMPTY) {
      return List.of();
    }
    List<DiagnosticRecord> records = audit(file);
    List<Diagnostic> lspDiagnostics = new ArrayList<>();
    for (DiagnosticRecord rec : records) {
      Diagnostic diag = new Diagnostic();
      int line = Math.max(0, rec.line() - 1);
      int col = Math.max(0, rec.column() - 1);
      int endCol = col + Math.max(1, rec.length());
      diag.range = new Range(new Position(line, col), new Position(line, endCol));
      diag.severity =
          switch (rec.severity()) {
            case ERROR -> dev.secondsun.lsp.DiagnosticSeverity.Error;
            case WARNING -> dev.secondsun.lsp.DiagnosticSeverity.Warning;
            case INFO -> dev.secondsun.lsp.DiagnosticSeverity.Information;
          };
      diag.code = rec.rule();
      diag.source = "retroca65";
      diag.message = rec.message();
      lspDiagnostics.add(diag);
    }
    return lspDiagnostics;
  }

  /** Audits document and publishes diagnostics to connected LanguageClient if present. */
  public void publishDiagnostics(URI uri) {
    if (client != null && uri != null) {
      try {
        List<Diagnostic> diags = collectLspDiagnostics(uri);
        client.publishDiagnostics(new PublishDiagnosticsParams(uri, diags));
      } catch (Exception e) {
        LOG.log(
            Level.WARNING, "Failed to publish diagnostics for " + uri + ": " + e.getMessage(), e);
      }
    }
  }

  /** Clears diagnostics for closed document. */
  public void clearDiagnostics(URI uri) {
    if (client != null && uri != null) {
      try {
        client.publishDiagnostics(new PublishDiagnosticsParams(uri, List.of()));
      } catch (Exception e) {
        LOG.log(Level.WARNING, "Failed to clear diagnostics for " + uri + ": " + e.getMessage(), e);
      }
    }
  }

  private TokenizedFile resolveFile(String code, String uriStr) {
    URI uri = uriStr != null ? URI.create(uriStr) : null;
    if (code != null && !code.isBlank()) {
      if (uri != null) {
        projectService.updateFileContents(uri, code);
        return projectService.getFileContents(uri);
      } else {
        CA65Scanner scanner = new CA65Scanner();
        TokenizedFile tf = scanner.tokenize(code);
        tf.uri = URI.create("file:///snippet.s");
        return tf;
      }
    } else if (uri != null) {
      TokenizedFile file = projectService.getFileContents(uri);
      if (file == null || file == TokenizedFile.EMPTY) {
        throw new IllegalArgumentException("File not found in workspace: " + uri);
      }
      return file;
    }
    return TokenizedFile.EMPTY;
  }
}
