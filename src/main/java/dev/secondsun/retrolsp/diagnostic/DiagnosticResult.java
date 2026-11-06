package dev.secondsun.retrolsp.diagnostic;

import java.util.List;

/**
 * Result of a unified diagnostics audit containing status, violation records, and summary.
 *
 * @param valid whether the audit passed without error-severity violations
 * @param violations list of diagnostic violation records detected during the audit
 * @param summary human-readable summary description of the audit results
 */
public record DiagnosticResult(boolean valid, List<DiagnosticRecord> violations, String summary) {}
