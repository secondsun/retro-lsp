package dev.secondsun.retrolsp.diagnostic;

import dev.secondsun.lsp.Diagnostic;
import dev.secondsun.lsp.Position;
import dev.secondsun.lsp.Range;
import dev.secondsun.retro.util.diagnostic.SyntaxDiagnostic;
import dev.secondsun.sfxoptimizer.stack.StackViolation;
import java.net.URI;

/**
 * Typed sealed representation of a diagnostic finding produced by one of the four {@code retro-lsp}
 * validation passes (syntax, SuperFX hardware invariants, SuperFX stack invariants, or SuperFX
 * performance heuristics).
 *
 * <p>Keeping {@code AuditFinding} in {@code dev.secondsun.retrolsp.diagnostic} preserves {@code
 * retro-common}'s SuperFX-agnostic domain boundary while eliminating string-prefix rule guessing in
 * {@link DiagnosticRecord}.
 */
public sealed interface AuditFinding
    permits AuditFinding.Syntax,
        AuditFinding.Hardware,
        AuditFinding.Stack,
        AuditFinding.Performance {

  /**
   * Source URI associated with this finding, or {@code null} when unspecified.
   *
   * @return document URI or {@code null}
   */
  URI uri();

  /**
   * 1-based source line number.
   *
   * @return 1-based line number
   */
  int line();

  /**
   * 0-based inclusive start column on {@link #line()}.
   *
   * @return 0-based start column
   */
  int columnStart();

  /**
   * 0-based exclusive end column on {@link #line()}.
   *
   * @return 0-based end column
   */
  int columnEnd();

  /**
   * Machine-readable diagnostic rule identifier (e.g. {@code "CACHE_IN_LOOP"}).
   *
   * @return rule code
   */
  String rule();

  /**
   * Typed diagnostic category owned by the emitting pass.
   *
   * @return diagnostic category
   */
  DiagnosticCategory category();

  /**
   * Diagnostic severity level.
   *
   * @return diagnostic severity
   */
  DiagnosticSeverity severity();

  /**
   * Human-readable diagnostic message.
   *
   * @return message text
   */
  String message();

  /**
   * Converts this finding to a {@link SyntaxDiagnostic} value object.
   *
   * @return equivalent {@link SyntaxDiagnostic}
   */
  default SyntaxDiagnostic toSyntaxDiagnostic() {
    var coreSeverity =
        switch (severity()) {
          case ERROR -> dev.secondsun.retro.util.diagnostic.DiagnosticSeverity.ERROR;
          case WARNING -> dev.secondsun.retro.util.diagnostic.DiagnosticSeverity.WARNING;
          case INFO -> dev.secondsun.retro.util.diagnostic.DiagnosticSeverity.INFO;
        };
    return new SyntaxDiagnostic(
        uri(), line(), columnStart(), columnEnd(), rule(), coreSeverity, message());
  }

  /**
   * Converts this finding to an LSP {@link Diagnostic} (0-based line and character coordinates).
   *
   * @return LSP {@link Diagnostic}
   */
  default Diagnostic toLspDiagnostic() {
    int lspLine = Math.max(0, line() - 1);
    int startChar = Math.max(0, columnStart());
    int endChar = Math.max(startChar + 1, columnEnd());
    Range range = new Range(new Position(lspLine, startChar), new Position(lspLine, endChar));
    var lspSeverity =
        switch (severity()) {
          case ERROR -> dev.secondsun.lsp.DiagnosticSeverity.Error;
          case WARNING -> dev.secondsun.lsp.DiagnosticSeverity.Warning;
          case INFO -> dev.secondsun.lsp.DiagnosticSeverity.Information;
        };
    Diagnostic diag = new Diagnostic();
    diag.range = range;
    diag.severity = lspSeverity;
    diag.code = rule();
    diag.source = "retroca65";
    diag.message = message();
    return diag;
  }

  /**
   * Maps a {@code retro-common} severity enum to {@link DiagnosticSeverity}.
   *
   * @param severity core diagnostic severity
   * @return mapped {@link DiagnosticSeverity}
   */
  static DiagnosticSeverity mapSeverity(
      dev.secondsun.retro.util.diagnostic.DiagnosticSeverity severity) {
    if (severity == null) {
      return DiagnosticSeverity.ERROR;
    }
    return switch (severity) {
      case ERROR -> DiagnosticSeverity.ERROR;
      case WARNING -> DiagnosticSeverity.WARNING;
      case INFO, HINT -> DiagnosticSeverity.INFO;
    };
  }

  /**
   * Syntax, block-balancing, arity, or lexical diagnostic finding emitted by {@code
   * SyntaxValidator}.
   *
   * @param uri document URI
   * @param line 1-based line number
   * @param columnStart 0-based start column
   * @param columnEnd 0-based end column
   * @param rule rule identifier
   * @param severity diagnostic severity
   * @param message human-readable description
   */
  record Syntax(
      URI uri,
      int line,
      int columnStart,
      int columnEnd,
      String rule,
      DiagnosticSeverity severity,
      String message)
      implements AuditFinding {

    @Override
    public DiagnosticCategory category() {
      return DiagnosticCategory.SYNTAX;
    }

    /**
     * Wraps a {@link SyntaxDiagnostic} emitted by {@code SyntaxValidator} as a typed syntax
     * finding.
     *
     * @param diag core syntax diagnostic
     * @return typed {@link Syntax} finding
     */
    public static Syntax from(SyntaxDiagnostic diag) {
      return new Syntax(
          diag.uri(),
          diag.line(),
          diag.columnStart(),
          diag.columnEnd(),
          diag.code(),
          mapSeverity(diag.severity()),
          diag.message());
    }
  }

  /**
   * SuperFX (GSU / FX3) hardware invariant violation emitted by {@code SfxInvariantAuditor}.
   *
   * @param uri document URI
   * @param line 1-based line number
   * @param columnStart 0-based start column
   * @param columnEnd 0-based end column
   * @param rule rule identifier
   * @param severity diagnostic severity
   * @param message human-readable description
   */
  record Hardware(
      URI uri,
      int line,
      int columnStart,
      int columnEnd,
      String rule,
      DiagnosticSeverity severity,
      String message)
      implements AuditFinding {

    @Override
    public DiagnosticCategory category() {
      return DiagnosticCategory.HARDWARE_INVARIANT;
    }
  }

  /**
   * SuperFX software stack (`R10`) displacement invariant violation emitted by {@code
   * StackDepthTracker}.
   *
   * @param uri document URI
   * @param line 1-based line number
   * @param columnStart 0-based start column
   * @param columnEnd 0-based end column
   * @param rule rule identifier
   * @param severity diagnostic severity
   * @param message human-readable description
   */
  record Stack(
      URI uri,
      int line,
      int columnStart,
      int columnEnd,
      String rule,
      DiagnosticSeverity severity,
      String message)
      implements AuditFinding {

    @Override
    public DiagnosticCategory category() {
      return DiagnosticCategory.STACK_INVARIANT;
    }

    /**
     * Converts a {@link StackViolation} from {@code sfx-optimizer} into a typed stack finding.
     *
     * @param violation stack depth violation
     * @param fallbackUri fallback URI when the violation URI is null
     * @return typed {@link Stack} finding
     */
    public static Stack from(StackViolation violation, URI fallbackUri) {
      URI resolvedUri = violation.getUri() != null ? violation.getUri() : fallbackUri;
      return new Stack(
          resolvedUri,
          Math.max(1, violation.getLine()),
          0,
          80,
          violation.getRule(),
          DiagnosticSeverity.ERROR,
          violation.getMessage());
    }
  }

  /**
   * SuperFX instruction cache or hot-loop performance advisory emitted by {@code
   * SfxPerformanceAuditor}.
   *
   * @param uri document URI
   * @param line 1-based line number
   * @param columnStart 0-based start column
   * @param columnEnd 0-based end column
   * @param rule rule identifier
   * @param severity diagnostic severity
   * @param message human-readable description
   */
  record Performance(
      URI uri,
      int line,
      int columnStart,
      int columnEnd,
      String rule,
      DiagnosticSeverity severity,
      String message)
      implements AuditFinding {

    @Override
    public DiagnosticCategory category() {
      return DiagnosticCategory.PERFORMANCE;
    }
  }
}
