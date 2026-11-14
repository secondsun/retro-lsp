package dev.secondsun.retrolsp.diagnostic;

import dev.secondsun.retro.util.diagnostic.SyntaxDiagnostic;

/**
 * Diagnostic violation record describing a syntax error, hardware invariant violation, stack
 * displacement violation, or performance advisory.
 *
 * @param line line number of the diagnostic (1-based)
 * @param column column index of the diagnostic (1-based)
 * @param length length of the token or span in characters
 * @param rule unique rule identifier or diagnostic code
 * @param category diagnostic categorization
 * @param severity severity level (ERROR, WARNING, INFO)
 * @param message human-readable diagnostic explanation
 */
public record DiagnosticRecord(
    int line,
    int column,
    int length,
    String rule,
    DiagnosticCategory category,
    DiagnosticSeverity severity,
    String message) {

  /**
   * Convenience constructor defaulting column and length to 1.
   *
   * @param line line number of the diagnostic
   * @param rule unique rule identifier
   * @param category diagnostic categorization
   * @param severity severity level
   * @param message human-readable diagnostic explanation
   */
  public DiagnosticRecord(
      int line,
      String rule,
      DiagnosticCategory category,
      DiagnosticSeverity severity,
      String message) {
    this(line, 1, 1, rule, category, severity, message);
  }

  /**
   * Converts a typed {@link AuditFinding} to a {@link DiagnosticRecord} using the finding's exact
   * {@link DiagnosticCategory} without rule-name string prefix guessing.
   *
   * @param finding typed audit finding
   * @return converted diagnostic record
   */
  public static DiagnosticRecord from(AuditFinding finding) {
    int col = Math.max(1, finding.columnStart() + 1);
    int len = Math.max(1, finding.columnEnd() - finding.columnStart());
    return new DiagnosticRecord(
        finding.line(),
        col,
        len,
        finding.rule(),
        finding.category(),
        finding.severity(),
        finding.message());
  }

  /**
   * Converts a {@code retro-common} {@link SyntaxDiagnostic} directly into a syntax {@link
   * DiagnosticRecord}.
   *
   * @param sd core syntax diagnostic
   * @return converted syntax diagnostic record
   */
  public static DiagnosticRecord from(SyntaxDiagnostic sd) {
    return from(AuditFinding.Syntax.from(sd));
  }
}
