package dev.secondsun.retrolsp.diagnostic;

import dev.secondsun.lsp.Diagnostic;
import dev.secondsun.lsp.LanguageClient;
import dev.secondsun.lsp.PublishDiagnosticsParams;
import dev.secondsun.retro.util.WorkspaceIndex;
import dev.secondsun.retro.util.WorkspaceSnapshot;
import dev.secondsun.retro.util.diagnostic.SyntaxDiagnostic;
import dev.secondsun.retro.util.diagnostic.SyntaxValidator;
import dev.secondsun.retro.util.vo.DialectProfile;
import dev.secondsun.retro.util.vo.TokenizedFile;
import dev.secondsun.retrolsp.mcp.tools.SfxInvariantAuditor;
import dev.secondsun.retrolsp.mcp.tools.SfxPerformanceAuditor;
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
 * Coordinates diagnostic passes across four typed validation layers:
 *
 * <ol>
 *   <li>Syntax and Language Invariants ({@code retro-common} {@link SyntaxValidator} &rarr; {@link
 *       AuditFinding.Syntax})
 *   <li>FX3 Hardware Invariants ({@link SfxInvariantAuditor} &rarr; {@link AuditFinding.Hardware})
 *   <li>SuperFX Performance Heuristics ({@link SfxPerformanceAuditor} &rarr; {@link
 *       AuditFinding.Performance})
 *   <li>Stack Displacement &amp; Calling Convention Invariants ({@code sfx-optimizer} {@link
 *       StackDepthTracker} &rarr; {@link AuditFinding.Stack})
 * </ol>
 *
 * <p>Emits diagnostics to a connected LSP {@link LanguageClient} and formats typed results for MCP
 * tools without mutating persistent workspace state on inline code snippets.
 */
public class DiagnosticCoordinator {

  private static final Logger LOG = Logger.getLogger(DiagnosticCoordinator.class.getName());

  private final WorkspaceIndex workspaceIndex;
  private final LanguageClient client;
  private final SyntaxValidator syntaxValidator;
  private final SfxInvariantAuditor invariantAuditor;
  private final SfxPerformanceAuditor performanceAuditor;
  private final StackDepthTracker stackTracker;

  /**
   * Constructs a {@link DiagnosticCoordinator} backed by a {@link WorkspaceIndex} and optional LSP
   * client.
   *
   * @param workspaceIndex workspace index managing files, open buffers, and snapshots
   * @param client optional LSP client to publish diagnostics to
   */
  public DiagnosticCoordinator(WorkspaceIndex workspaceIndex, LanguageClient client) {
    this.workspaceIndex = Objects.requireNonNull(workspaceIndex);
    this.client = client;
    this.syntaxValidator = new SyntaxValidator();
    this.invariantAuditor = new SfxInvariantAuditor(this.workspaceIndex);
    this.performanceAuditor = new SfxPerformanceAuditor();
    this.stackTracker = new StackDepthTracker();
  }

  /**
   * Constructs a standalone {@link DiagnosticCoordinator} without an LSP client.
   *
   * @param workspaceIndex workspace index managing files, open buffers, and snapshots
   */
  public DiagnosticCoordinator(WorkspaceIndex workspaceIndex) {
    this(workspaceIndex, null);
  }

  /**
   * Returns the underlying {@link WorkspaceIndex}.
   *
   * @return workspace index
   */
  public WorkspaceIndex getWorkspaceIndex() {
    return workspaceIndex;
  }

  /**
   * Returns the connected language client, or {@code null} if running standalone.
   *
   * @return language client or {@code null}
   */
  public LanguageClient getLanguageClient() {
    return client;
  }

  /**
   * Audits inline assembly source code (as an ephemeral transient snippet overlay) or a workspace
   * document URI and returns a unified {@link DiagnosticResult}.
   *
   * @param code raw assembly source code, or {@code null} to read from workspace
   * @param uriStr workspace document URI string, or {@code null}
   * @return unified diagnostic audit result
   */
  public DiagnosticResult auditResult(String code, String uriStr) {
    URI uri = uriStr != null && !uriStr.isBlank() ? URI.create(uriStr) : null;
    WorkspaceSnapshot snapshot = workspaceIndex.snapshotFor(uri, code);
    TokenizedFile file;
    if (code != null && !code.isBlank()) {
      URI targetUri = uri != null ? uri : WorkspaceSnapshot.TRANSIENT_SNIPPET_URI;
      file = snapshot.file(targetUri);
    } else if (uri != null) {
      file = workspaceIndex.resolveFile(uri, null);
      if (file == null || file.isEmpty()) {
        throw new IllegalArgumentException("File not found in workspace: " + uri);
      }
    } else {
      return new DiagnosticResult(true, List.of(), "Empty code: no diagnostics audited.");
    }

    if (file == null || file.isEmpty()) {
      return new DiagnosticResult(true, List.of(), "Empty code: no diagnostics audited.");
    }

    List<DiagnosticRecord> violations = audit(file, snapshot);
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
   * Audits a {@link TokenizedFile} against {@code snapshot} and returns typed {@link AuditFinding}
   * records across all four validation passes.
   *
   * @param file tokenized file to audit
   * @param snapshot point-in-time workspace snapshot
   * @return sorted list of typed audit findings
   */
  public List<AuditFinding> auditFindings(TokenizedFile file, WorkspaceSnapshot snapshot) {
    if (file == null || file.isEmpty()) {
      return List.of();
    }

    WorkspaceSnapshot activeSnap = snapshot != null ? snapshot : workspaceIndex.snapshot();
    List<AuditFinding> findings = new ArrayList<>();

    // Layer 1: Syntax & Language Invariants
    try {
      for (SyntaxDiagnostic sd : syntaxValidator.validate(file)) {
        findings.add(AuditFinding.Syntax.from(sd));
      }
    } catch (Exception e) {
      LOG.log(Level.WARNING, "Syntax validation error: " + e.getMessage(), e);
    }

    // Layer 2: FX3 Hardware Invariants (gated by DialectProfile.SUPERFX)
    try {
      findings.addAll(invariantAuditor.auditHardwareFindings(file, activeSnap));
    } catch (Exception e) {
      LOG.log(Level.WARNING, "Hardware invariant audit error: " + e.getMessage(), e);
    }

    // Layer 3: SuperFX Performance Heuristics (gated by DialectProfile.SUPERFX)
    try {
      findings.addAll(performanceAuditor.auditFindings(file));
    } catch (Exception e) {
      LOG.log(Level.WARNING, "Performance audit error: " + e.getMessage(), e);
    }

    // Layer 4: Stack Displacement & Calling Convention Invariants
    if (hasSuperFxLines(file)) {
      try {
        for (StackViolation sv : stackTracker.auditFindings(file, activeSnap)) {
          findings.add(AuditFinding.Stack.from(sv, file.uri()));
        }
      } catch (Throwable t) {
        LOG.log(Level.FINE, "Stack displacement tracking skipped: " + t.getMessage(), t);
      }
    }

    findings.sort(
        Comparator.comparingInt(AuditFinding::line).thenComparingInt(AuditFinding::columnStart));
    return findings;
  }

  /**
   * Audits a {@link TokenizedFile} against the current workspace snapshot and returns typed {@link
   * AuditFinding} records.
   *
   * @param file tokenized file to audit
   * @return sorted list of typed audit findings
   */
  public List<AuditFinding> auditFindings(TokenizedFile file) {
    return auditFindings(file, workspaceIndex.snapshot());
  }

  /**
   * Audits a {@link TokenizedFile} and collects structured {@link SyntaxDiagnostic}s across all
   * layers.
   *
   * @param file tokenized file to audit
   * @return sorted list of syntax diagnostics
   */
  public List<SyntaxDiagnostic> auditDiagnostics(TokenizedFile file) {
    return auditFindings(file).stream().map(AuditFinding::toSyntaxDiagnostic).toList();
  }

  /**
   * Audits a {@link TokenizedFile} against the current workspace snapshot and collects {@link
   * DiagnosticRecord}s.
   *
   * @param file tokenized file to audit
   * @return list of diagnostic records
   */
  public List<DiagnosticRecord> audit(TokenizedFile file) {
    return audit(file, workspaceIndex.snapshot());
  }

  /**
   * Audits a {@link TokenizedFile} against an explicit {@link WorkspaceSnapshot} and collects
   * {@link DiagnosticRecord}s.
   *
   * @param file tokenized file to audit
   * @param snapshot workspace snapshot
   * @return list of diagnostic records
   */
  public List<DiagnosticRecord> audit(TokenizedFile file, WorkspaceSnapshot snapshot) {
    return auditFindings(file, snapshot).stream().map(DiagnosticRecord::from).toList();
  }

  /**
   * Collects LSP {@link Diagnostic} objects for a given workspace document URI.
   *
   * @param uri document URI to collect diagnostics for
   * @return list of LSP diagnostics
   */
  public List<Diagnostic> collectLspDiagnostics(URI uri) {
    if (uri == null) {
      return List.of();
    }
    WorkspaceSnapshot snapshot = workspaceIndex.snapshot();
    TokenizedFile file = snapshot.file(uri);
    if ((file == null || file.isEmpty()) && workspaceIndex.isInsideWorkspace(uri)) {
      file = workspaceIndex.getFileContents(uri);
      snapshot = workspaceIndex.snapshot();
    }
    if (file == null || file.isEmpty()) {
      return List.of();
    }
    return auditFindings(file, snapshot).stream().map(AuditFinding::toLspDiagnostic).toList();
  }

  /**
   * Audits document and publishes diagnostics to connected {@link LanguageClient} if present.
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
   * Clears diagnostics for a closed document.
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

  private static boolean hasSuperFxLines(TokenizedFile file) {
    var tree = file.scopeTree();
    int total = file.textLines();
    for (int i = 0; i < total; i++) {
      if (!tree.isMacroBodyLine(i) && tree.dialectAtLine(i) == DialectProfile.SUPERFX) {
        return true;
      }
    }
    return false;
  }
}
