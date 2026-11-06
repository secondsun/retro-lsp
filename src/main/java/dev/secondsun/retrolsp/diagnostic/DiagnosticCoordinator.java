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

  /**
   * Constructs a DiagnosticCoordinator with a project service and optional LSP client.
   *
   * @param projectService the project service managing files and symbols
   * @param client optional LSP client to publish diagnostics to
   */
  public DiagnosticCoordinator(ProjectService projectService, LanguageClient client) {
    this.projectService = Objects.requireNonNull(projectService);
    this.client = client;
    this.syntaxValidator = new SyntaxValidator();
    this.invariantAuditor = new SfxInvariantAuditor(this.projectService);
    this.stackTracker = new StackDepthTracker();
  }

  /**
   * Constructs a standalone DiagnosticCoordinator without an LSP client.
   *
   * @param projectService the project service managing files and symbols
   */
  public DiagnosticCoordinator(ProjectService projectService) {
    this(projectService, null);
  }

  /**
   * Returns the underlying project service.
   *
   * @return the project service
   */
  public ProjectService getProjectService() {
    return projectService;
  }

  /**
   * Returns the connected language client, or null if running standalone.
   *
   * @return the language client or null
   */
  public LanguageClient getLanguageClient() {
    return client;
  }

  /**
   * Audits assembly source code or document URI and returns a unified DiagnosticResult.
   *
   * @param code raw assembly source code, or null to read from workspace
   * @param uriStr workspace document URI string, or null
   * @return the unified diagnostic audit result
   */
  public DiagnosticResult auditResult(String code, String uriStr) {
    TokenizedFile file = resolveFile(code, uriStr);
    if (file == null || file.isEmpty()) {
      return new DiagnosticResult(true, List.of(), "Empty code: no diagnostics audited.");
    }
    List<DiagnosticRecord> violations = audit(file);
    boolean valid = violations.stream().noneMatch(v -> v.severity() == DiagnosticSeverity.ERROR);
    String summary =
        String.format(
            "SuperFX Diagnostics: %d violation(s) found (%s). Categories: syntax=%d, hardware=%d, stack=%d, performance=%d.",
            violations.size(),
            valid ? "PASSED" : "FAILED",
            countCategory(violations, DiagnosticCategory.SYNTAX),
            countCategory(violations, DiagnosticCategory.HARDWARE_INVARIANT),
            countCategory(violations, DiagnosticCategory.STACK_INVARIANT),
            countCategory(violations, DiagnosticCategory.PERFORMANCE));
    return new DiagnosticResult(valid, violations, summary);
  }

  private static long countCategory(List<DiagnosticRecord> records, DiagnosticCategory cat) {
    return records.stream().filter(r -> r.category() == cat).count();
  }

  /**
   * Audits a TokenizedFile and collects structured SyntaxDiagnostics across all 3 layers.
   *
   * @param file the tokenized file to audit
   * @return sorted list of syntax diagnostics
   */
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

  /**
   * Audits a TokenizedFile and collects diagnostic records across all 3 layers.
   *
   * @param file the tokenized file to audit
   * @return list of diagnostic records
   */
  public List<DiagnosticRecord> audit(TokenizedFile file) {
    return auditDiagnostics(file).stream().map(DiagnosticRecord::from).toList();
  }

  /**
   * Collects LSP Diagnostic objects for a given workspace document URI.
   *
   * @param uri document URI to collect diagnostics for
   * @return list of LSP diagnostics
   */
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

  /**
   * Audits document and publishes diagnostics to connected LanguageClient if present.
   *
   * @param uri URI of the document to publish diagnostics for
   */
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

  /**
   * Clears diagnostics for closed document.
   *
   * @param uri URI of the closed document
   */
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
