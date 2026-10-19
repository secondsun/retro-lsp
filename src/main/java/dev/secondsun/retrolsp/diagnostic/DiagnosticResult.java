package dev.secondsun.retrolsp.diagnostic;

import java.util.List;

/** Result of a unified diagnostics audit containing status, violation records, and summary. */
public record DiagnosticResult(boolean valid, List<DiagnosticRecord> violations, String summary) {}
