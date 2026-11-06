package dev.secondsun.retrolsp.diagnostic;

/** Severity level of diagnostic messages. */
public enum DiagnosticSeverity {
  /** Error-level diagnostic indicating broken code or invalid hardware instructions. */
  ERROR,
  /** Warning-level diagnostic indicating potential bugs or bad practices. */
  WARNING,
  /** Informational diagnostic or hint. */
  INFO
}
