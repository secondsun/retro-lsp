package dev.secondsun.retrolsp.diagnostic;

import dev.secondsun.retro.util.diagnostic.SyntaxDiagnostic;
import java.util.Locale;

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

  public static DiagnosticRecord from(SyntaxDiagnostic sd) {
    DiagnosticCategory cat;
    String code = sd.code() != null ? sd.code().toUpperCase(Locale.ROOT) : "";
    if (code.equals("CALL_IN_HOT_LOOP")
        || code.equals("CACHE_BLOCK_OVERFLOW")
        || code.equals("REDUNDANT_RPIX_IN_SUBROUTINE")
        || code.equals("ROMB_IN_HOT_LOOP")) {
      cat = DiagnosticCategory.PERFORMANCE;
    } else if (code.startsWith("STACK_")
        || code.startsWith("RETURN_")
        || code.contains("DIVERGENT")) {
      cat = DiagnosticCategory.STACK_INVARIANT;
    } else if (code.startsWith("UNSETTLED_")
        || code.startsWith("UNALIGNED_")
        || code.startsWith("MERGE_")
        || code.startsWith("MODAL_")
        || code.startsWith("PIPELINE_")
        || code.startsWith("WORD_ALIGNMENT")
        || code.startsWith("NO_HARDWARE_")
        || code.startsWith("DANGLING_MODAL")
        || code.startsWith("UNFLUSHED_")
        || code.startsWith("ROM_SYMBOL_")
        || code.startsWith("IWT_R15_")) {
      cat = DiagnosticCategory.HARDWARE_INVARIANT;
    } else {
      cat = DiagnosticCategory.SYNTAX;
    }

    DiagnosticSeverity sev =
        switch (sd.severity()) {
          case ERROR -> DiagnosticSeverity.ERROR;
          case WARNING -> DiagnosticSeverity.WARNING;
          case INFO, HINT -> DiagnosticSeverity.INFO;
        };

    int col = Math.max(1, sd.columnStart() + 1);
    int len = Math.max(1, sd.columnEnd() - sd.columnStart());
    return new DiagnosticRecord(sd.line(), col, len, sd.code(), cat, sev, sd.message());
  }
}
