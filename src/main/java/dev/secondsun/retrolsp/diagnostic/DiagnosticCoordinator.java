package dev.secondsun.retrolsp.diagnostic;

import dev.secondsun.lsp.Diagnostic;
import dev.secondsun.lsp.LanguageClient;
import dev.secondsun.lsp.Position;
import dev.secondsun.lsp.PublishDiagnosticsParams;
import dev.secondsun.lsp.Range;
import dev.secondsun.retro.util.ProjectService;
import dev.secondsun.retro.util.diagnostic.SyntaxDiagnostic;
import dev.secondsun.retro.util.diagnostic.SyntaxValidator;
import dev.secondsun.retro.util.vo.TokenizedFile;
import dev.secondsun.retrolsp.mcp.tools.SfxInvariantAuditor;
import dev.secondsun.sfxoptimizer.stack.StackDepthTracker;
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
    if (file == null || file.isEmpty()) {
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
  /** Audits a TokenizedFile and collects structured SyntaxDiagnostics across all 3 layers. */
  public List<SyntaxDiagnostic> auditDiagnostics(TokenizedFile file) {
    if (file == null || file.isEmpty()) {
      return List.of();
    }

    List<SyntaxDiagnostic> diagnostics = new ArrayList<>();

    // Layer 1: Syntax & Language Invariants (Block balancing, arity, duplicate symbols)
    try {
      diagnostics.addAll(syntaxValidator.validate(file));
    } catch (Exception e) {
      LOG.log(Level.WARNING, "Syntax validation error: " + e.getMessage(), e);
    }

    // Layer 2: FX3 Hardware Invariants (8-NOP settling, 16-bit word alignment, no merge, modal
    // lifetimes)
    try {
      diagnostics.addAll(invariantAuditor.auditDiagnostics(file));
    } catch (Exception e) {
      LOG.log(Level.WARNING, "Hardware invariant audit error: " + e.getMessage(), e);
    }

    // Layer 3: Stack Displacement & Calling Convention Invariants
    try {
      diagnostics.addAll(
          stackTracker.auditFile(
              file,
              projectService,
              projectService.getFileService(),
              projectService.getSymbolService()));
    } catch (Throwable t) {
      LOG.log(Level.FINE, "Stack displacement tracking skipped: " + t.getMessage(), t);
    }

    diagnostics.sort(
        Comparator.comparingInt(SyntaxDiagnostic::line)
            .thenComparingInt(SyntaxDiagnostic::columnStart));
    return diagnostics;
  }

  /** Audits a TokenizedFile and collects diagnostic records across all 3 layers. */
  public List<DiagnosticRecord> audit(TokenizedFile file) {
    return auditDiagnostics(file).stream().map(DiagnosticRecord::from).toList();
  }

  /** Collects LSP Diagnostic objects for a given workspace document URI. */
  public List<Diagnostic> collectLspDiagnostics(URI uri) {
    if (uri == null) {
      return List.of();
    }
    TokenizedFile file = projectService.getFileContents(uri);
    if (file == null || file.isEmpty()) {
      return List.of();
    }
    List<SyntaxDiagnostic> diagnostics = auditDiagnostics(file);
    List<Diagnostic> lspDiagnostics = new ArrayList<>();
    for (SyntaxDiagnostic sd : diagnostics) {
      Diagnostic diag = new Diagnostic();
      int line = Math.max(0, sd.line() - 1);
      int col = Math.max(0, sd.columnStart());
      int endCol = Math.max(col + 1, sd.columnEnd());
      diag.range = new Range(new Position(line, col), new Position(line, endCol));
      diag.severity =
          switch (sd.severity()) {
            case ERROR -> dev.secondsun.lsp.DiagnosticSeverity.Error;
            case WARNING -> dev.secondsun.lsp.DiagnosticSeverity.Warning;
            case INFO -> dev.secondsun.lsp.DiagnosticSeverity.Information;
            case HINT -> dev.secondsun.lsp.DiagnosticSeverity.Hint;
          };
      diag.code = sd.code();
      diag.source = "retroca65";
      diag.message = sd.message();
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
    TokenizedFile file = projectService.resolveFile(uri, code);
    if ((file == null || file.isEmpty()) && uri != null && (code == null || code.isBlank())) {
      throw new IllegalArgumentException("File not found in workspace: " + uri);
    }
    return file != null ? file : TokenizedFile.EMPTY;
  }
}
