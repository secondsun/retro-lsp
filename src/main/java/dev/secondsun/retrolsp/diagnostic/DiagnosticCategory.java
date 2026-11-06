package dev.secondsun.retrolsp.diagnostic;

/** Categorization of diagnostic findings in SuperFX and CA65 assembly code. */
public enum DiagnosticCategory {
  /** Syntax and language invariants such as block balancing and directive arity. */
  SYNTAX,
  /** SuperFX hardware constraints such as 8-NOP settling and word alignment. */
  HARDWARE_INVARIANT,
  /** Stack balance and calling convention invariants across subroutines. */
  STACK_INVARIANT,
  /** Performance optimization recommendations and register pressure warnings. */
  PERFORMANCE
}
