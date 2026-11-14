package dev.secondsun.retrolsp.mcp.tools;

import dev.secondsun.retro.util.DefaultWorkspaceIndex;
import dev.secondsun.retro.util.FunctionSyntaxHelper;
import dev.secondsun.retro.util.Token;
import dev.secondsun.retro.util.TokenType;
import dev.secondsun.retro.util.WorkspaceIndex;
import dev.secondsun.retro.util.WorkspaceSnapshot;
import dev.secondsun.retro.util.diagnostic.DiagnosticSeverity;
import dev.secondsun.retro.util.diagnostic.SyntaxDiagnostic;
import dev.secondsun.retro.util.vo.DialectProfile;
import dev.secondsun.retro.util.vo.TokenizedFile;
import dev.secondsun.retro.util.vo.Tokens;
import dev.secondsun.retrolsp.diagnostic.AuditFinding;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MCP Tool and static analyzer: {@code sfx_audit_invariants}.
 *
 * <p>Statically validates SuperFX 3 (FX3 / GSU) hardware invariants on assembly source code:
 *
 * <ol>
 *   <li><b>8-NOP Pipeline Settling</b>: Requires at least 8 {@code nop} instructions immediately
 *       before {@code stop} (ignoring intervening labels/comments), plus dual {@code rpix} pixel
 *       cache flush checks after {@code plot}.
 *   <li><b>16-bit Word Alignment</b>: Flags odd literal byte offsets/addresses on {@code ldw} and
 *       {@code stw}.
 *   <li><b>No Hardware MERGE</b>: Flags usage of the unsupported {@code merge} instruction on FX3
 *       targets.
 *   <li><b>Modal Instruction Scope</b>: Audits {@code with}, {@code to}, {@code from}, and {@code
 *       alt1}–{@code alt3} composition, overwritten prefixes, dangling prefixes, destructive {@code
 *       with} comparisons, and unintentional {@code from Rn; add/sub #0} writes to {@code R0}.
 *   <li><b>Memory &amp; Immediate Safety</b>: Audits {@code ldw}/{@code ldb} loads from ROM
 *       segments, non-symbolic {@code iwt r15} jump targets, and unmasked negative immediates on
 *       {@code ibt}.
 * </ol>
 */
public class SfxInvariantAuditor {

  /** Severity level for invariant violations. */
  public enum Severity {
    /** Hard hardware constraint violation that causes miscompilation or runtime corruption. */
    ERROR,
    /** Potential hazard or performance anti-pattern. */
    WARNING
  }

  /**
   * Represents a single hardware or performance invariant violation.
   *
   * @param line 1-based line number
   * @param columnStart 0-based start column
   * @param columnEnd 0-based end column
   * @param rule rule identifier
   * @param severity violation severity
   * @param message human-readable explanation
   */
  public record InvariantViolation(
      int line, int columnStart, int columnEnd, String rule, Severity severity, String message) {

    /**
     * Convenience constructor with default columns [0, 80].
     *
     * @param line 1-based line number
     * @param rule rule identifier
     * @param severity violation severity
     * @param message human-readable explanation
     */
    public InvariantViolation(int line, String rule, Severity severity, String message) {
      this(line, 0, 80, rule, severity, message);
    }

    /**
     * Converts this violation to a {@link SyntaxDiagnostic}.
     *
     * @param uri document URI
     * @return converted {@link SyntaxDiagnostic}
     */
    public SyntaxDiagnostic toSyntaxDiagnostic(URI uri) {
      DiagnosticSeverity diagSeverity =
          severity == Severity.ERROR ? DiagnosticSeverity.ERROR : DiagnosticSeverity.WARNING;
      return new SyntaxDiagnostic(uri, line, columnStart, columnEnd, rule, diagSeverity, message);
    }
  }

  /**
   * Overall result of an invariant audit run.
   *
   * @param valid true when zero {@link Severity#ERROR} violations were found
   * @param violations all discovered hardware and performance violations
   * @param summary human-readable summary string
   */
  public record AuditResult(boolean valid, List<InvariantViolation> violations, String summary) {}

  private static final Pattern HEX_LITERAL = Pattern.compile("(?i)(?:\\$|0x)([0-9a-f]+)");
  private static final Pattern IBT_NEGATIVE_IMM =
      Pattern.compile(
          "(?i)\\bibt\\s+r(?:1[0-5]|[0-9])\\s*,\\s*#\\s*(-\\s*(?:\\$[0-9a-f]+|0x[0-9a-f]+|%[01]+|\\d+))");

  private record PendingModal(
      String opcode, int line, int columnStart, int columnEnd, boolean inDelaySlot) {}

  private final WorkspaceIndex workspaceIndex;
  private final SfxPerformanceAuditor performanceAuditor = new SfxPerformanceAuditor();

  /** Default constructor initializing with an empty in-memory {@link DefaultWorkspaceIndex}. */
  public SfxInvariantAuditor() {
    this(new DefaultWorkspaceIndex());
  }

  /**
   * Constructs an auditor backed by the provided {@link WorkspaceIndex}.
   *
   * @param workspaceIndex workspace index for resolving files and cross-file symbol segments
   */
  public SfxInvariantAuditor(WorkspaceIndex workspaceIndex) {
    this.workspaceIndex = Objects.requireNonNull(workspaceIndex);
  }

  /**
   * Audits raw assembly source code without mutating workspace state.
   *
   * @param code raw assembly code string
   * @return audit result
   */
  public AuditResult audit(String code) {
    return audit(code, null);
  }

  /**
   * Audits inline assembly source code (as a transient snippet overlay) or a workspace document
   * URI.
   *
   * @param code raw assembly code string or {@code null}
   * @param uriStr document URI string or {@code null}
   * @return audit result
   */
  public AuditResult audit(String code, String uriStr) {
    URI uri = uriStr != null && !uriStr.isBlank() ? URI.create(uriStr) : null;
    WorkspaceSnapshot snapshot = workspaceIndex.snapshotFor(uri, code);
    TokenizedFile file;
    if (code != null && !code.isBlank()) {
      URI lookupUri = uri != null ? uri : WorkspaceSnapshot.TRANSIENT_SNIPPET_URI;
      file = snapshot.file(lookupUri);
    } else if (uri != null) {
      file = workspaceIndex.resolveFile(uri, null);
      if (file == null || file.isEmpty()) {
        throw new IllegalArgumentException("File not found in workspace: " + uri);
      }
    } else {
      return new AuditResult(true, List.of(), "Empty code: no invariants audited.");
    }

    if (file == null || file.isEmpty()) {
      return new AuditResult(true, List.of(), "Empty code: no invariants audited.");
    }
    return audit(file, snapshot);
  }

  /**
   * Audits a tokenized file and produces a list of {@link SyntaxDiagnostic}s.
   *
   * @param file the tokenized file to audit
   * @return list of syntax diagnostics
   */
  public List<SyntaxDiagnostic> auditDiagnostics(TokenizedFile file) {
    if (file == null || file.isEmpty()) {
      return List.of();
    }
    AuditResult res = audit(file, workspaceIndex.snapshot());
    URI uri = file.uri();
    return res.violations().stream().map(v -> v.toSyntaxDiagnostic(uri)).toList();
  }

  /**
   * Audits a tokenized file against the current workspace snapshot.
   *
   * @param file the tokenized file to audit
   * @return audit result combining hardware and performance checks
   */
  public AuditResult audit(TokenizedFile file) {
    return audit(file, workspaceIndex.snapshot());
  }

  /**
   * Audits a tokenized file against an explicit {@link WorkspaceSnapshot}.
   *
   * @param file the tokenized file to audit
   * @param snapshot point-in-time workspace snapshot for symbol resolution
   * @return audit result combining hardware and performance checks
   */
  public AuditResult audit(TokenizedFile file, WorkspaceSnapshot snapshot) {
    if (file == null || file.isEmpty()) {
      return new AuditResult(true, List.of(), "Empty code: no invariants audited.");
    }

    List<InvariantViolation> violations = new ArrayList<>();
    for (AuditFinding.Hardware hw : auditHardwareFindings(file, snapshot)) {
      Severity sev =
          hw.severity() == dev.secondsun.retrolsp.diagnostic.DiagnosticSeverity.ERROR
              ? Severity.ERROR
              : Severity.WARNING;
      violations.add(
          new InvariantViolation(
              hw.line(), hw.columnStart(), hw.columnEnd(), hw.rule(), sev, hw.message()));
    }
    violations.addAll(performanceAuditor.audit(file));

    boolean valid = violations.stream().noneMatch(v -> v.severity() == Severity.ERROR);
    String summary =
        String.format(
            "SuperFX Invariants Audit: %d violation(s) found (%s).",
            violations.size(), valid ? "PASSED" : "FAILED");

    return new AuditResult(valid, violations, summary);
  }

  /**
   * Audits only SuperFX hardware invariants on {@code file}, returning typed {@link
   * AuditFinding.Hardware} records gated by {@link DialectProfile#SUPERFX}.
   *
   * @param file tokenized file to audit
   * @param snapshot workspace snapshot for resolving cross-file symbol segments
   * @return list of typed hardware invariant findings
   */
  public List<AuditFinding.Hardware> auditHardwareFindings(
      TokenizedFile file, WorkspaceSnapshot snapshot) {
    if (file == null || file.isEmpty()) {
      return List.of();
    }

    URI uri = file.uri();
    List<InvariantViolation> violations = new ArrayList<>();
    String lastMnemonic = null;
    int consecutiveNops = 0;
    boolean hasPlot = false;
    int rpixAfterPlot = 0;
    PendingModal pendingFrom = null;
    PendingModal pendingTo = null;
    PendingModal pendingWith = null;

    int totalLines = file.textLines();
    var scopeTree = file.scopeTree();
    Map<String, String> symbolSegments = collectSymbolSegments(file);
    Map<String, String> regLoadedSymbol = new HashMap<>();

    for (int lineIdx = 0; lineIdx < totalLines; lineIdx++) {
      if (scopeTree.isMacroBodyLine(lineIdx)) {
        continue;
      }
      if (scopeTree.dialectAtLine(lineIdx) != DialectProfile.SUPERFX) {
        consecutiveNops = 0;
        hasPlot = false;
        rpixAfterPlot = 0;
        pendingFrom = null;
        pendingTo = null;
        pendingWith = null;
        lastMnemonic = null;
        regLoadedSymbol.clear();
        continue;
      }

      int lineNum = lineIdx + 1; // 1-based line number
      Tokens lineTokens = file.getLine(lineIdx);
      if (lineTokens == null || lineTokens.tokens().isEmpty()) {
        continue;
      }

      // Labels emit 0 bytes and do not break straight-line NOP settling or modal state
      if (file.isLabelOnly(lineIdx)) {
        regLoadedSymbol.clear();
        continue;
      }

      Optional<Token> opcodeOpt = file.getPrimaryOpcode(lineIdx);
      if (opcodeOpt.isEmpty()) {
        // Directives (.segment, .byte, etc.) or pure comments
        continue;
      }

      Token opcodeToken = opcodeOpt.get();
      List<Token> tokens = lineTokens.tokens();
      String rawLine = file.getLineText(lineIdx) != null ? file.getLineText(lineIdx).trim() : "";
      int opcodeTokenIdx = tokens.indexOf(opcodeToken);
      if (opcodeTokenIdx > 0) {
        regLoadedSymbol.clear();
      }
      String mnemonic = opcodeToken.text().toLowerCase(Locale.ROOT);
      List<Token> tail =
          opcodeTokenIdx >= 0 ? tokens.subList(opcodeTokenIdx, tokens.size()) : tokens;
      boolean isFnHeader = FunctionSyntaxHelper.isFunction(tail);
      boolean isEndFn = FunctionSyntaxHelper.isEndFunction(tail);

      // Check Invariant 3: NO_HARDWARE_MERGE
      if ("merge".equals(mnemonic)) {
        int mergeStart = opcodeToken.getStartIndex();
        int mergeEnd = tokens.get(tokens.size() - 1).getEndIndex();
        violations.add(
            new InvariantViolation(
                lineNum,
                mergeStart,
                mergeEnd,
                "NO_HARDWARE_MERGE",
                Severity.ERROR,
                "Hardware 'MERGE' instruction is not supported on SuperFX 3 (FX3). Use byte manipulation workarounds ('hib', 'lob', 'swap', 'or') instead."));
      }

      // Check Invariant 1: 8-NOP pipeline settling before 'stop' & pixel cache flush after 'plot'
      if ("plot".equals(mnemonic)) {
        hasPlot = true;
        rpixAfterPlot = 0;
      } else if ("rpix".equals(mnemonic) && hasPlot) {
        rpixAfterPlot++;
      }

      if ("stop".equals(mnemonic)) {
        int stopStart = opcodeToken.getStartIndex();
        int stopEnd = tokens.get(tokens.size() - 1).getEndIndex();
        if (hasPlot && rpixAfterPlot < 2) {
          violations.add(
              new InvariantViolation(
                  lineNum,
                  stopStart,
                  stopEnd,
                  "UNFLUSHED_PIXEL_CACHE",
                  Severity.WARNING,
                  String.format(
                      "SuperFX 'plot' buffers pixels in dual 8-pixel caches (primary and secondary). Found %d 'rpix' instruction(s) after 'plot' before 'stop'; execute two 'rpix' instructions before the 8 settling NOPs to flush all plotted pixels to Work RAM.",
                      rpixAfterPlot)));
        }
        if (consecutiveNops < 8) {
          violations.add(
              new InvariantViolation(
                  lineNum,
                  stopStart,
                  stopEnd,
                  "PIPELINE_SETTLING_8_NOP",
                  Severity.ERROR,
                  String.format(
                      "Missing 8-NOP pipeline settling before 'stop' (found %d NOPs). SuperFX Work RAM writes are pipelined asynchronously and require 8 NOPs before halting.",
                      consecutiveNops)));
        }
        consecutiveNops = 0;
      } else if ("nop".equals(mnemonic)) {
        consecutiveNops++;
      } else {
        consecutiveNops = 0;
      }

      // Check Invariant 2: WORD_ALIGNMENT_LDW_STW
      if ("ldw".equals(mnemonic) || "stw".equals(mnemonic)) {
        checkWordAlignment(lineNum, mnemonic, tokens, opcodeTokenIdx, violations);
      }

      // Check Invariant 5: ROM_SYMBOL_RAM_LOAD, IWT_R15_NON_LABEL, IBT_NEGATIVE_IMMEDIATE
      if ("iwt".equals(mnemonic)) {
        handleIwtInstruction(lineNum, tokens, opcodeTokenIdx, regLoadedSymbol, violations);
      } else if ("ldw".equals(mnemonic) || "ldb".equals(mnemonic)) {
        checkRomSymbolRamLoad(
            lineNum,
            mnemonic,
            tokens,
            opcodeTokenIdx,
            file,
            snapshot,
            symbolSegments,
            regLoadedSymbol,
            violations);
        String pendingReg = "r0";
        if (pendingTo != null) {
          pendingReg = extractModalTarget(file, pendingTo.line() - 1);
        } else if (pendingWith != null) {
          pendingReg = extractModalTarget(file, pendingWith.line() - 1);
        }
        if (pendingReg != null) {
          regLoadedSymbol.remove(pendingReg.toLowerCase(Locale.ROOT));
        }
      } else if ("ibt".equals(mnemonic)
          || "move".equals(mnemonic)
          || "moves".equals(mnemonic)
          || "lm".equals(mnemonic)
          || "lms".equals(mnemonic)
          || "inc".equals(mnemonic)
          || "dec".equals(mnemonic)) {
        if (opcodeTokenIdx + 1 < tokens.size()) {
          regLoadedSymbol.remove(tokens.get(opcodeTokenIdx + 1).text().toLowerCase(Locale.ROOT));
        }
        if ("ibt".equals(mnemonic)) {
          String uncommented = rawLine.split(";", 2)[0];
          Matcher ibtNeg = IBT_NEGATIVE_IMM.matcher(uncommented);
          if (ibtNeg.find()) {
            String negLiteral = ibtNeg.group(1).replaceAll("\\s+", "");
            int colStart = opcodeToken.getStartIndex();
            int colEnd = tokens.get(tokens.size() - 1).getEndIndex();
            violations.add(
                new InvariantViolation(
                    lineNum,
                    colStart,
                    colEnd,
                    "IBT_NEGATIVE_IMMEDIATE",
                    Severity.ERROR,
                    String.format(
                        "Unmasked negative immediate '#%s' in 'ibt': ca65's GSU_Assembler.i macro checks '0 <= imm <= 255' and rejects negative literals. Use two's-complement byte masking ('#<(%s)') or unsigned hex ('#$80'–'#$FF').",
                        negLiteral, negLiteral)));
          }
        }
      } else if ("call".equals(mnemonic) || isFnHeader || isEndFn || "return".equals(mnemonic)) {
        regLoadedSymbol.clear();
      }

      // Check Invariant 4: MODAL_INSTRUCTION_SCOPE ('with', 'to', 'from', and 'alt1'..'alt3')
      int modalColStart = opcodeToken.getStartIndex();
      int modalColEnd =
          tokens.isEmpty()
              ? modalColStart + mnemonic.length()
              : tokens.get(tokens.size() - 1).getEndIndex();
      if (modalColEnd <= modalColStart) {
        modalColEnd = modalColStart + mnemonic.length();
      }

      boolean inDelaySlot = isBranchOrLoop(lastMnemonic);
      boolean isRegModal =
          "with".equals(mnemonic) || "to".equals(mnemonic) || "from".equals(mnemonic);
      boolean isAltModal =
          "alt1".equals(mnemonic) || "alt2".equals(mnemonic) || "alt3".equals(mnemonic);

      if (isAltModal) {
        // alt1..alt3 compose with active from/to/with prefixes without consuming them!
      } else if (isRegModal) {
        if ("from".equals(mnemonic)) {
          if (pendingWith != null) {
            violations.add(
                new InvariantViolation(
                    pendingWith.line(),
                    pendingWith.columnStart(),
                    pendingWith.columnEnd(),
                    "MODAL_PREFIX_OVERWRITTEN",
                    Severity.ERROR,
                    String.format(
                        "Modal prefix 'with' on line %d is immediately followed by modal instruction 'from' on line %d without an intervening instruction. Modal prefixes apply to the single immediately following instruction only.",
                        pendingWith.line(), lineNum)));
            pendingWith = null;
          }
          if (pendingFrom != null) {
            violations.add(
                new InvariantViolation(
                    pendingFrom.line(),
                    pendingFrom.columnStart(),
                    pendingFrom.columnEnd(),
                    "MODAL_PREFIX_OVERWRITTEN",
                    Severity.ERROR,
                    String.format(
                        "Modal prefix 'from' on line %d is immediately overwritten by 'from' on line %d without an intervening instruction.",
                        pendingFrom.line(), lineNum)));
          }
          pendingFrom =
              new PendingModal(mnemonic, lineNum, modalColStart, modalColEnd, inDelaySlot);
        } else if ("to".equals(mnemonic)) {
          if (pendingWith != null) {
            violations.add(
                new InvariantViolation(
                    pendingWith.line(),
                    pendingWith.columnStart(),
                    pendingWith.columnEnd(),
                    "MODAL_PREFIX_OVERWRITTEN",
                    Severity.ERROR,
                    String.format(
                        "Modal prefix 'with' on line %d is immediately followed by modal instruction 'to' on line %d without an intervening instruction. Modal prefixes apply to the single immediately following instruction only.",
                        pendingWith.line(), lineNum)));
            pendingWith = null;
          }
          if (pendingTo != null) {
            violations.add(
                new InvariantViolation(
                    pendingTo.line(),
                    pendingTo.columnStart(),
                    pendingTo.columnEnd(),
                    "MODAL_PREFIX_OVERWRITTEN",
                    Severity.ERROR,
                    String.format(
                        "Modal prefix 'to' on line %d is immediately overwritten by 'to' on line %d without an intervening instruction.",
                        pendingTo.line(), lineNum)));
          }
          pendingTo = new PendingModal(mnemonic, lineNum, modalColStart, modalColEnd, inDelaySlot);
        } else if ("with".equals(mnemonic)) {
          if (pendingFrom != null) {
            violations.add(
                new InvariantViolation(
                    pendingFrom.line(),
                    pendingFrom.columnStart(),
                    pendingFrom.columnEnd(),
                    "MODAL_PREFIX_OVERWRITTEN",
                    Severity.ERROR,
                    String.format(
                        "Modal prefix 'from' on line %d is immediately followed by modal instruction 'with' on line %d without an intervening instruction. In SuperFX, 'with' sets both Sreg and Dreg, completely overwriting 'from'.",
                        pendingFrom.line(), lineNum)));
            pendingFrom = null;
          }
          if (pendingTo != null) {
            violations.add(
                new InvariantViolation(
                    pendingTo.line(),
                    pendingTo.columnStart(),
                    pendingTo.columnEnd(),
                    "MODAL_PREFIX_OVERWRITTEN",
                    Severity.ERROR,
                    String.format(
                        "Modal prefix 'to' on line %d is immediately followed by modal instruction 'with' on line %d without an intervening instruction. In SuperFX, 'with' sets both Sreg and Dreg, completely overwriting 'to'.",
                        pendingTo.line(), lineNum)));
            pendingTo = null;
          }
          if (pendingWith != null) {
            violations.add(
                new InvariantViolation(
                    pendingWith.line(),
                    pendingWith.columnStart(),
                    pendingWith.columnEnd(),
                    "MODAL_PREFIX_OVERWRITTEN",
                    Severity.ERROR,
                    String.format(
                        "Modal prefix 'with' on line %d is immediately overwritten by 'with' on line %d without an intervening instruction.",
                        pendingWith.line(), lineNum)));
          }
          pendingWith =
              new PendingModal(mnemonic, lineNum, modalColStart, modalColEnd, inDelaySlot);
        }
      } else {
        List<PendingModal> activeModals = new ArrayList<>();
        if (pendingFrom != null) activeModals.add(pendingFrom);
        if (pendingTo != null) activeModals.add(pendingTo);
        if (pendingWith != null) activeModals.add(pendingWith);

        if (!activeModals.isEmpty()) {
          if ("move".equals(mnemonic)
              || "moves".equals(mnemonic)
              || "moveb".equals(mnemonic)
              || "movew".equals(mnemonic)) {
            for (PendingModal pm : activeModals) {
              String message =
                  String.format(
                      "Modal prefix '%s' on line %d precedes '%s' on line %d. '%s' already specifies both source and destination registers directly and does not accept a modal prefix ('to', 'from', 'with'); the prefix on line %d is immediately overwritten.",
                      pm.opcode(), pm.line(), mnemonic, lineNum, mnemonic, pm.line());
              violations.add(
                  new InvariantViolation(
                      pm.line(),
                      pm.columnStart(),
                      pm.columnEnd(),
                      "MODAL_PREFIX_OVERWRITTEN",
                      Severity.ERROR,
                      message));
            }
          } else if (isControlInstruction(mnemonic) || isFnHeader || isEndFn) {
            for (PendingModal pm : activeModals) {
              if (!pm.inDelaySlot()) {
                violations.add(
                    new InvariantViolation(
                        pm.line(),
                        pm.columnStart(),
                        pm.columnEnd(),
                        "DANGLING_MODAL_PREFIX",
                        Severity.ERROR,
                        String.format(
                            "Modal instruction '%s' on line %d has no valid target instruction (followed by '%s' on line %d). Modal prefixes apply to the single immediately following instruction only.",
                            pm.opcode(), pm.line(), mnemonic, lineNum)));
              }
            }
          }

          if (pendingWith != null) {
            checkDestructiveWithPrefix(
                file,
                pendingWith.line(),
                pendingWith.columnStart(),
                pendingWith.columnEnd(),
                lineNum,
                mnemonic,
                violations);
          }

          if (pendingFrom != null
              && pendingTo == null
              && ("sub".equals(mnemonic) || "add".equals(mnemonic))) {
            if (isImmediateZero(tokens, opcodeTokenIdx)) {
              violations.add(
                  new InvariantViolation(
                      pendingFrom.line(),
                      pendingFrom.columnStart(),
                      pendingFrom.columnEnd(),
                      "DESTRUCTIVE_FROM_ALU_TO_R0",
                      Severity.WARNING,
                      String.format(
                          "Instruction 'from' on line %d followed by '%s #0' on line %d without 'to' writes the result into R0 (default Dreg), clobbering R0. To test flags of Rn without clobbering R0, use 'with Rn; add #0' or 'moves Rn, Rn'.",
                          pendingFrom.line(), mnemonic, lineNum)));
            }
          }

          pendingFrom = null;
          pendingTo = null;
          pendingWith = null;
        }
      }

      if (!isAltModal) {
        lastMnemonic = mnemonic;
      }
    }

    // Trailing dangling modal prefix at end of code
    if (pendingFrom != null && !pendingFrom.inDelaySlot()) {
      violations.add(
          new InvariantViolation(
              pendingFrom.line(),
              pendingFrom.columnStart(),
              pendingFrom.columnEnd(),
              "DANGLING_MODAL_PREFIX",
              Severity.ERROR,
              String.format(
                  "Modal instruction 'from' on line %d has no target instruction (end of file reached). Modal prefixes apply to the single immediately following instruction only.",
                  pendingFrom.line())));
    }
    if (pendingTo != null && !pendingTo.inDelaySlot()) {
      violations.add(
          new InvariantViolation(
              pendingTo.line(),
              pendingTo.columnStart(),
              pendingTo.columnEnd(),
              "DANGLING_MODAL_PREFIX",
              Severity.ERROR,
              String.format(
                  "Modal instruction 'to' on line %d has no target instruction (end of file reached). Modal prefixes apply to the single immediately following instruction only.",
                  pendingTo.line())));
    }
    if (pendingWith != null && !pendingWith.inDelaySlot()) {
      violations.add(
          new InvariantViolation(
              pendingWith.line(),
              pendingWith.columnStart(),
              pendingWith.columnEnd(),
              "DANGLING_MODAL_PREFIX",
              Severity.ERROR,
              String.format(
                  "Modal instruction 'with' on line %d has no target instruction (end of file reached). Modal prefixes apply to the single immediately following instruction only.",
                  pendingWith.line())));
    }

    return violations.stream()
        .map(
            v ->
                new AuditFinding.Hardware(
                    uri,
                    v.line(),
                    v.columnStart(),
                    v.columnEnd(),
                    v.rule(),
                    v.severity() == Severity.ERROR
                        ? dev.secondsun.retrolsp.diagnostic.DiagnosticSeverity.ERROR
                        : dev.secondsun.retrolsp.diagnostic.DiagnosticSeverity.WARNING,
                    v.message()))
        .toList();
  }

  private boolean isBranchOrLoop(String mnemonic) {
    if (mnemonic == null) return false;
    return switch (mnemonic) {
      case "loop",
          "bra",
          "beq",
          "bne",
          "bge",
          "blt",
          "bmi",
          "bpl",
          "bcc",
          "bcs",
          "bvc",
          "bvs",
          "jmp",
          "ljmp" ->
          true;
      default -> false;
    };
  }

  private boolean isControlInstruction(String mnemonic) {
    return switch (mnemonic) {
      case "stop", "return", "ret", "bra", "jmp", "jal", "ljmp" -> true;
      default -> false;
    };
  }

  private void checkWordAlignment(
      int lineNum,
      String mnemonic,
      List<Token> tokens,
      int opcodeIdx,
      List<InvariantViolation> violations) {
    for (int i = opcodeIdx + 1; i < tokens.size(); i++) {
      Token tok = tokens.get(i);

      if (tok.type == TokenType.TOK_INTCON) {
        if (tok.intVal % 2 != 0) {
          String display =
              tok.text() != null && !tok.text().isEmpty()
                  ? tok.text()
                  : ("$" + Integer.toHexString(tok.intVal));
          violations.add(
              new InvariantViolation(
                  lineNum,
                  tok.getStartIndex(),
                  tok.getEndIndex(),
                  "WORD_ALIGNMENT_LDW_STW",
                  Severity.ERROR,
                  String.format(
                      "Unaligned 16-bit word memory access: '%s' must target 16-bit word aligned (even) addresses (found odd offset/address: '%s').",
                      mnemonic, display)));
          return;
        }
      }

      String text = tok.text() != null ? tok.text().trim() : "";
      if (!text.isEmpty()) {
        Matcher hexMatch = HEX_LITERAL.matcher(text);
        if (hexMatch.find()) {
          try {
            long val = Long.parseLong(hexMatch.group(1), 16);
            if (val % 2 != 0) {
              violations.add(
                  new InvariantViolation(
                      lineNum,
                      tok.getStartIndex(),
                      tok.getEndIndex(),
                      "WORD_ALIGNMENT_LDW_STW",
                      Severity.ERROR,
                      String.format(
                          "Unaligned 16-bit word memory access: '%s' must target 16-bit word aligned (even) addresses (found odd offset/address: '%s').",
                          mnemonic, text)));
              return;
            }
          } catch (NumberFormatException ignored) {
          }
        }
      }
    }
  }

  private Map<String, String> collectSymbolSegments(TokenizedFile file) {
    Map<String, String> map = new HashMap<>();
    if (file == null) {
      return map;
    }
    String currentSegment = null;
    int totalLines = file.textLines();
    for (int i = 0; i < totalLines; i++) {
      List<Token> tokens = file.getLineTokens(i);
      if (tokens == null || tokens.isEmpty()) {
        continue;
      }
      Token first = tokens.get(0);
      if (".segment".equalsIgnoreCase(first.text()) && tokens.size() >= 2) {
        String segName = tokens.get(1).text().replace("\"", "").trim().toUpperCase(Locale.ROOT);
        if (!segName.isEmpty()) {
          currentSegment = segName;
        }
        continue;
      }
      if (currentSegment != null) {
        if (first.text().endsWith(":") && first.text().length() > 1) {
          String sym =
              first.text().substring(0, first.text().length() - 1).trim().toLowerCase(Locale.ROOT);
          map.put(sym, currentSegment);
        } else if (tokens.size() >= 2 && tokens.get(1).type == TokenType.TOK_COLON) {
          String sym = first.text().trim().toLowerCase(Locale.ROOT);
          map.put(sym, currentSegment);
        }
      }
    }
    return map;
  }

  private String resolveSymbolSegment(
      String symbol,
      TokenizedFile currentFile,
      WorkspaceSnapshot snapshot,
      Map<String, String> localSegments) {
    if (symbol == null || symbol.isBlank()) {
      return null;
    }
    String key = symbol.toLowerCase(Locale.ROOT);
    String local = localSegments.get(key);
    if (local != null) {
      return local;
    }
    WorkspaceSnapshot activeSnap = snapshot != null ? snapshot : workspaceIndex.snapshot();
    URI callingUri = currentFile != null ? currentFile.uri() : null;
    var loc = activeSnap.resolveLocation(symbol, callingUri, 0);
    if (loc != null && loc.filename() != null) {
      TokenizedFile targetFile = activeSnap.file(loc.filename());
      if ((targetFile == null || targetFile.isEmpty())
          && workspaceIndex.isInsideWorkspace(loc.filename())) {
        targetFile = workspaceIndex.getFileContents(loc.filename());
      }
      if (targetFile != null && !targetFile.isEmpty()) {
        String currentSeg = null;
        int limit = Math.min(loc.line() + 1, targetFile.textLines());
        for (int i = 0; i < limit; i++) {
          List<Token> tokens = targetFile.getLineTokens(i);
          if (tokens != null
              && tokens.size() >= 2
              && ".segment".equalsIgnoreCase(tokens.get(0).text())) {
            String segName = tokens.get(1).text().replace("\"", "").trim().toUpperCase(Locale.ROOT);
            if (!segName.isEmpty()) {
              currentSeg = segName;
            }
          }
        }
        return currentSeg;
      }
    }
    return null;
  }

  private static boolean isRomSegment(String segment) {
    if (segment == null) {
      return false;
    }
    String upper = segment.toUpperCase(Locale.ROOT);
    return "GSUCODE".equals(upper)
        || "RODATA".equals(upper)
        || "GSUDATA".equals(upper)
        || "CODE".equals(upper)
        || upper.startsWith("ROM");
  }

  private String extractModalTarget(TokenizedFile file, int lineIdx) {
    List<Token> tokens = file.getLineTokens(lineIdx);
    if (tokens == null || tokens.size() < 2) {
      return null;
    }
    Optional<Token> op = file.getPrimaryOpcode(lineIdx);
    if (op.isEmpty()) {
      return null;
    }
    int idx = tokens.indexOf(op.get());
    if (idx >= 0 && idx + 1 < tokens.size()) {
      return tokens.get(idx + 1).text();
    }
    return null;
  }

  private void handleIwtInstruction(
      int lineNum,
      List<Token> tokens,
      int opcodeIdx,
      Map<String, String> regLoadedSymbol,
      List<InvariantViolation> violations) {
    if (opcodeIdx + 1 >= tokens.size()) {
      return;
    }
    String dstReg = tokens.get(opcodeIdx + 1).text();
    regLoadedSymbol.remove(dstReg.toLowerCase(Locale.ROOT));

    int hashIdx = -1;
    for (int i = opcodeIdx + 2; i < tokens.size(); i++) {
      if (tokens.get(i).type == TokenType.TOK_HASH || tokens.get(i).text().startsWith("#")) {
        hashIdx = i;
        break;
      }
    }
    if (hashIdx == -1) {
      return;
    }

    List<Token> immTokens = new ArrayList<>();
    for (int i = hashIdx; i < tokens.size(); i++) {
      Token t = tokens.get(i);
      if (t.type == TokenType.TOK_HASH && "#".equals(t.text())) {
        continue;
      }
      immTokens.add(t);
    }

    if ("r15".equalsIgnoreCase(dstReg)) {
      boolean hasLabelIdent =
          immTokens.stream()
              .anyMatch(
                  t ->
                      t.type == TokenType.TOK_IDENT
                          || t.type == TokenType.TOK_LOCAL_IDENT
                          || t.type == TokenType.TOK_ULABEL
                          || t.type == TokenType.TOK_A
                          || t.type == TokenType.TOK_X
                          || t.type == TokenType.TOK_Y
                          || t.type == TokenType.TOK_S);
      if (!immTokens.isEmpty() && !hasLabelIdent) {
        int immStart = immTokens.get(0).getStartIndex();
        int immEnd = immTokens.get(immTokens.size() - 1).getEndIndex();
        violations.add(
            new InvariantViolation(
                lineNum,
                immStart,
                immEnd,
                "IWT_R15_NON_LABEL",
                Severity.ERROR,
                String.format(
                    "Invalid 'iwt r15' jump target '%s': 'iwt r15, #<label>' must target a symbolic label rather than a raw numeric constant.",
                    immTokens.get(0).text())));
      }
      return;
    }

    for (Token t : immTokens) {
      if (t.type == TokenType.TOK_IDENT
          || t.type == TokenType.TOK_LOCAL_IDENT
          || t.type == TokenType.TOK_A
          || t.type == TokenType.TOK_X
          || t.type == TokenType.TOK_Y
          || t.type == TokenType.TOK_S) {
        String text = t.text().startsWith("#") ? t.text().substring(1) : t.text();
        if (!text.startsWith(".")) {
          regLoadedSymbol.put(dstReg.toLowerCase(Locale.ROOT), text);
          break;
        }
      }
    }
  }

  private void checkDestructiveWithPrefix(
      TokenizedFile file,
      int modalLineNum,
      int modalColStart,
      int modalColEnd,
      int targetLineNum,
      String mnemonic,
      List<InvariantViolation> violations) {
    if (!"sub".equals(mnemonic) && !"cmp".equals(mnemonic) && !"add".equals(mnemonic)) {
      return;
    }
    String targetReg = extractModalTarget(file, modalLineNum - 1);
    String regName = targetReg != null ? targetReg.toUpperCase(Locale.ROOT) : "Rn";

    boolean followedByBranch = false;
    int checkLines = Math.min(file.textLines(), targetLineNum + 4);
    for (int l = targetLineNum; l < checkLines; l++) {
      List<Token> lineTokens = file.getLineTokens(l);
      if (lineTokens == null || lineTokens.isEmpty()) continue;
      Optional<Token> op = file.getPrimaryOpcode(l);
      if (op.isEmpty()) continue;
      String m = op.get().text().toLowerCase(Locale.ROOT);
      if (isBranchOrLoop(m) && !"bra".equals(m) && !"jmp".equals(m) && !"loop".equals(m)) {
        followedByBranch = true;
        break;
      }
    }

    if (followedByBranch) {
      violations.add(
          new InvariantViolation(
              modalLineNum,
              modalColStart,
              modalColEnd,
              "DESTRUCTIVE_WITH_COMPARISON",
              Severity.WARNING,
              String.format(
                  "'with %s' followed by '%s' and a conditional branch modifies %s in-place (sets Sreg=%s, Dreg=%s). If this was intended as a non-destructive comparison/test, use 'from %s' instead (which leaves %s intact and writes to R0).",
                  regName, mnemonic, regName, regName, regName, regName, regName)));
    }
  }

  private void checkRomSymbolRamLoad(
      int lineNum,
      String mnemonic,
      List<Token> tokens,
      int opcodeIdx,
      TokenizedFile file,
      WorkspaceSnapshot snapshot,
      Map<String, String> symbolSegments,
      Map<String, String> regLoadedSymbol,
      List<InvariantViolation> violations) {
    String addrReg = null;
    for (int i = opcodeIdx + 1; i < tokens.size(); i++) {
      Token t = tokens.get(i);
      if (t.type == TokenType.TOK_LPAREN && i + 1 < tokens.size()) {
        addrReg = tokens.get(i + 1).text();
        break;
      }
    }
    if (addrReg == null) {
      return;
    }
    String sym = regLoadedSymbol.get(addrReg.toLowerCase(Locale.ROOT));
    if (sym == null) {
      return;
    }
    String seg = resolveSymbolSegment(sym, file, snapshot, symbolSegments);
    if (seg != null && isRomSegment(seg)) {
      int opStart = tokens.get(opcodeIdx).getStartIndex();
      int opEnd = tokens.get(tokens.size() - 1).getEndIndex();
      violations.add(
          new InvariantViolation(
              lineNum,
              opStart,
              opEnd,
              "ROM_SYMBOL_RAM_LOAD",
              Severity.ERROR,
              String.format(
                  "Invalid RAM load '%s (%s)' from symbol '%s' defined in ROM segment '%s'. SuperFX 'ldw'/'ldb' only read GSURAM ($700000, masked to 8 KB $1FFF); use 'romb' + 'r14' + 'getb'/'getbh' to read ROM data or place the symbol in '.segment \"GSURAM\"'.",
                  mnemonic, addrReg, sym, seg)));
    }
  }

  private boolean isImmediateZero(List<Token> tokens, int opcodeIdx) {
    for (int i = opcodeIdx + 1; i < tokens.size(); i++) {
      Token t = tokens.get(i);
      if (t.type == TokenType.TOK_HASH || "#".equals(t.text())) {
        if (i + 1 < tokens.size()) {
          Token next = tokens.get(i + 1);
          String txt = next.text() != null ? next.text().trim().toLowerCase(Locale.ROOT) : "";
          if (isZeroText(txt) || (next.type == TokenType.TOK_INTCON && next.intVal == 0)) {
            return true;
          }
        }
      } else if (t.text() != null && t.text().startsWith("#")) {
        String txt = t.text().substring(1).trim().toLowerCase(Locale.ROOT);
        if (isZeroText(txt)) {
          return true;
        }
      }
    }
    return false;
  }

  private boolean isZeroText(String txt) {
    if (txt == null || txt.isEmpty()) {
      return false;
    }
    if ("0".equals(txt)
        || "$0".equals(txt)
        || "$00".equals(txt)
        || "$000".equals(txt)
        || "$0000".equals(txt)
        || "%0".equals(txt)
        || "00".equals(txt)
        || "0000".equals(txt)) {
      return true;
    }
    try {
      if (txt.startsWith("$")) {
        return Integer.parseInt(txt.substring(1), 16) == 0;
      } else if (txt.startsWith("%")) {
        return Integer.parseInt(txt.substring(1), 2) == 0;
      } else {
        return Integer.parseInt(txt, 10) == 0;
      }
    } catch (NumberFormatException e) {
      return false;
    }
  }
}
