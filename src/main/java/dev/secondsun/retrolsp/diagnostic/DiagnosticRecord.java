package dev.secondsun.retrolsp.diagnostic;

/**
 * Diagnostic violation record describing a syntax error, hardware invariant violation, or stack
 * displacement violation.
 */
public record DiagnosticRecord(
    int line,
    int column,
    int length,
    String rule,
    DiagnosticCategory category,
    DiagnosticSeverity severity,
    String message) {

  public DiagnosticRecord(
      int line,
      String rule,
      DiagnosticCategory category,
      DiagnosticSeverity severity,
      String message) {
    this(line, 1, 1, rule, category, severity, message);
  }
}
