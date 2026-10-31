package dev.secondsun.retrolsp.mcp.tools;

import dev.secondsun.retro.util.Token;
import dev.secondsun.retro.util.TokenType;
import dev.secondsun.retro.util.diagnostic.DiagnosticSeverity;
import dev.secondsun.retro.util.diagnostic.SyntaxDiagnostic;
import dev.secondsun.retro.util.vo.TokenizedFile;
import dev.secondsun.retro.util.vo.Tokens;
import java.net.URI;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Audits SuperFX (FX3) assembly code for critical hardware invariants: 1. 8-NOP pipeline settling
 * before 'stop' 2. 16-bit word aligned addresses for 'ldw' / 'stw' 3. Absence of unsupported
 * hardware 'MERGE' instruction 4. Correct modal instruction scope ('with', 'to', 'from')
 */
public class SfxInvariantAuditor {

  public enum Severity {
    ERROR,
    WARNING
  }

  public record InvariantViolation(int line, String rule, Severity severity, String message) {
    public SyntaxDiagnostic toSyntaxDiagnostic(URI uri) {
      DiagnosticSeverity sev =
          severity == Severity.ERROR ? DiagnosticSeverity.ERROR : DiagnosticSeverity.WARNING;
      return new SyntaxDiagnostic(uri, Math.max(1, line), 0, 1, rule, sev, message);
    }
  }

  public record AuditResult(boolean valid, List<InvariantViolation> violations, String summary) {}

  private static final Pattern HEX_LITERAL = Pattern.compile("(?i)(?:\\$|0x)([0-9a-f]+)");
  private static final Pattern DEC_LITERAL = Pattern.compile("\\b(\\d+)\\b");
  private static final Pattern BIN_LITERAL = Pattern.compile("%([01]+)");

  private record PendingModal(String opcode, int line, boolean inDelaySlot) {}

  private final dev.secondsun.retro.util.ProjectService projectService;

  public SfxInvariantAuditor() {
    this(
        new dev.secondsun.retro.util.ProjectService(
            new dev.secondsun.retro.util.FileService(),
            new dev.secondsun.retro.util.SymbolService()));
  }

  public SfxInvariantAuditor(dev.secondsun.retro.util.ProjectService projectService) {
    this.projectService = Objects.requireNonNull(projectService);
  }

  public AuditResult audit(String code) {
    return audit(code, null);
  }

  public AuditResult audit(String code, String uriStr) {
    java.net.URI uri = uriStr != null ? java.net.URI.create(uriStr) : null;
    TokenizedFile file = projectService.resolveFile(uri, code);
    if (file == null || file.isEmpty()) {
      if (uri != null && (code == null || code.isBlank())) {
        throw new IllegalArgumentException("File not found in workspace: " + uri);
      }
      return new AuditResult(true, List.of(), "Empty code: no invariants audited.");
    }
    return audit(file);
  }

  public List<SyntaxDiagnostic> auditDiagnostics(TokenizedFile file) {
    if (file == null || file.isEmpty()) {
      return List.of();
    }
    AuditResult res = audit(file);
    URI uri = file.uri();
    return res.violations().stream().map(v -> v.toSyntaxDiagnostic(uri)).toList();
  }

  public AuditResult audit(TokenizedFile file) {
    if (file == null || file.isEmpty()) {
      return new AuditResult(true, List.of(), "Empty code: no invariants audited.");
    }

    List<InvariantViolation> violations = new ArrayList<>();
    String lastMnemonic = null;
    int consecutiveNops = 0;
    boolean hasPlot = false;
    int rpixAfterPlot = 0;
    PendingModal pendingModal = null;

    int totalLines = file.textLines();
    Map<String, String> symbolSegments = collectSymbolSegments(file);
    Map<String, String> regLoadedSymbol = new HashMap<>();

    for (int lineIdx = 0; lineIdx < totalLines; lineIdx++) {
      int lineNum = lineIdx + 1; // 1-based line number
      Tokens lineTokens = file.getLine(lineIdx);
      if (lineTokens == null || lineTokens.tokens().isEmpty()) {
        continue;
      }

      // Check if line defines a label (labels emit 0 bytes and do not break straight-line NOP
      // settling)
      if (file.isLabelOnly(lineIdx)) {
        regLoadedSymbol.clear();
        continue;
      }

      // Find primary mnemonic/instruction
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
        // Line has a leading label before the opcode
        regLoadedSymbol.clear();
      }
      String mnemonic = opcodeToken.text().toLowerCase(Locale.ROOT);

      // Check Invariant 3: NO_HARDWARE_MERGE
      if ("merge".equals(mnemonic)) {
        violations.add(
            new InvariantViolation(
                lineNum,
                "NO_HARDWARE_MERGE",
                Severity.ERROR,
                "Hardware 'MERGE' instruction is not supported on SuperFX 3 (FX3). Use byte manipulation workarounds ('hib', 'lob', 'swap', 'or') instead."));
      }

      // Check Invariant 1: 8-NOP pipeline settling before 'stop' (and pixel cache flush when 'plot'
      // is used)
      if ("plot".equals(mnemonic)) {
        hasPlot = true;
        rpixAfterPlot = 0;
      } else if ("rpix".equals(mnemonic) && hasPlot) {
        rpixAfterPlot++;
      }

      if ("stop".equals(mnemonic)) {
        if (hasPlot && rpixAfterPlot < 2) {
          violations.add(
              new InvariantViolation(
                  lineNum,
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
        checkWordAlignment(lineNum, mnemonic, tokens, opcodeTokenIdx, rawLine, violations);
      }

      // Check Invariant 5: ROM_SYMBOL_RAM_LOAD & IWT_R15_NON_LABEL
      if ("iwt".equals(mnemonic)) {
        handleIwtInstruction(
            lineNum, tokens, opcodeTokenIdx, file, symbolSegments, regLoadedSymbol, violations);
      } else if ("ldw".equals(mnemonic) || "ldb".equals(mnemonic)) {
        checkRomSymbolRamLoad(
            lineNum,
            mnemonic,
            tokens,
            opcodeTokenIdx,
            file,
            symbolSegments,
            regLoadedSymbol,
            violations);
        // If ldw/ldb clobbers a target register via 'to'/'with' or R0, clear its tracked symbol
        String pendingReg =
            pendingModal != null
                    && ("to".equals(pendingModal.opcode()) || "with".equals(pendingModal.opcode()))
                ? extractModalTarget(file, pendingModal.line() - 1)
                : "r0";
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
      } else if ("call".equals(mnemonic)
          || "function".equals(mnemonic)
          || "endfunction".equals(mnemonic)
          || "return".equals(mnemonic)) {
        regLoadedSymbol.clear();
      }

      // Check Invariant 4: MODAL_INSTRUCTION_SCOPE ('with', 'to', 'from')
      boolean isModal = "with".equals(mnemonic) || "to".equals(mnemonic) || "from".equals(mnemonic);
      if (isModal) {
        if (pendingModal != null) {
          if (pendingModal.opcode().equals(mnemonic)) {
            violations.add(
                new InvariantViolation(
                    pendingModal.line(),
                    "MODAL_PREFIX_OVERWRITTEN",
                    Severity.WARNING,
                    String.format(
                        "Modal prefix '%s' on line %d is immediately overwritten by '%s' on line %d without an intervening instruction.",
                        pendingModal.opcode(), pendingModal.line(), mnemonic, lineNum)));
          }
        }
        boolean inDelaySlot = isBranchOrLoop(lastMnemonic);
        pendingModal = new PendingModal(mnemonic, lineNum, inDelaySlot);
      } else {
        if (pendingModal != null) {
          // Check if instruction is a terminal/control instruction where modal is invalid
          if (isControlInstruction(mnemonic) && !pendingModal.inDelaySlot()) {
            violations.add(
                new InvariantViolation(
                    pendingModal.line(),
                    "DANGLING_MODAL_PREFIX",
                    Severity.ERROR,
                    String.format(
                        "Modal instruction '%s' on line %d has no valid target instruction (followed by '%s' on line %d). Modal prefixes apply to the single immediately following instruction only.",
                        pendingModal.opcode(), pendingModal.line(), mnemonic, lineNum)));
          }
          // The modal prefix is consumed by this instruction
          pendingModal = null;
        }
      }

      lastMnemonic = mnemonic;
    }

    // Trailing dangling modal prefix at end of code
    if (pendingModal != null && !pendingModal.inDelaySlot()) {
      violations.add(
          new InvariantViolation(
              pendingModal.line(),
              "DANGLING_MODAL_PREFIX",
              Severity.ERROR,
              String.format(
                  "Modal instruction '%s' on line %d has no target instruction (end of file reached). Modal prefixes apply to the single immediately following instruction only.",
                  pendingModal.opcode(), pendingModal.line())));
    }

    boolean valid = violations.stream().noneMatch(v -> v.severity() == Severity.ERROR);
    String summary =
        String.format(
            "SuperFX Invariants Audit: %d violation(s) found (%s).",
            violations.size(), valid ? "PASSED" : "FAILED");

    return new AuditResult(valid, violations, summary);
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
      String rawLine,
      List<InvariantViolation> violations) {
    // Inspect operand tokens after opcode
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
        // Check hex literal ($xxxx or 0x...)
        Matcher hexMatch = HEX_LITERAL.matcher(text);
        if (hexMatch.find()) {
          try {
            long val = Long.parseLong(hexMatch.group(1), 16);
            if (val % 2 != 0) {
              violations.add(
                  new InvariantViolation(
                      lineNum,
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
      String symbol, TokenizedFile currentFile, Map<String, String> localSegments) {
    if (symbol == null || symbol.isBlank()) {
      return null;
    }
    String key = symbol.toLowerCase(Locale.ROOT);
    String local = localSegments.get(key);
    if (local != null) {
      return local;
    }
    if (projectService != null && projectService.getSymbolService() != null) {
      var loc =
          projectService
              .getSymbolService()
              .getLocation(symbol, currentFile != null ? currentFile.uri() : null, 0);
      if (loc != null && loc.filename() != null) {
        TokenizedFile targetFile = projectService.getFileContents(loc.filename());
        if (targetFile != null) {
          String currentSeg = null;
          int limit = Math.min(loc.line() + 1, targetFile.textLines());
          for (int i = 0; i < limit; i++) {
            List<Token> tokens = targetFile.getLineTokens(i);
            if (tokens != null
                && tokens.size() >= 2
                && ".segment".equalsIgnoreCase(tokens.get(0).text())) {
              String segName =
                  tokens.get(1).text().replace("\"", "").trim().toUpperCase(Locale.ROOT);
              if (!segName.isEmpty()) {
                currentSeg = segName;
              }
            }
          }
          return currentSeg;
        }
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
      TokenizedFile file,
      Map<String, String> symbolSegments,
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
        violations.add(
            new InvariantViolation(
                lineNum,
                "IWT_R15_NON_LABEL",
                Severity.ERROR,
                String.format(
                    "Invalid 'iwt r15' jump target '%s': 'iwt r15, #<label>' must target a symbolic label rather than a raw numeric constant.",
                    immTokens.get(0).text())));
      }
      return;
    }

    // Extract primary symbol in #<symbol> or #(symbol + offset)
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

  private void checkRomSymbolRamLoad(
      int lineNum,
      String mnemonic,
      List<Token> tokens,
      int opcodeIdx,
      TokenizedFile file,
      Map<String, String> symbolSegments,
      Map<String, String> regLoadedSymbol,
      List<InvariantViolation> violations) {
    // Find register inside (Rn)
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
    String seg = resolveSymbolSegment(sym, file, symbolSegments);
    if (seg != null && isRomSegment(seg)) {
      violations.add(
          new InvariantViolation(
              lineNum,
              "ROM_SYMBOL_RAM_LOAD",
              Severity.ERROR,
              String.format(
                  "Invalid RAM load '%s (%s)' from symbol '%s' defined in ROM segment '%s'. SuperFX 'ldw'/'ldb' only read GSURAM ($700000, masked to 8 KB $1FFF); use 'romb' + 'r14' + 'getb'/'getbh' to read ROM data or place the symbol in '.segment \"GSURAM\"'.",
                  mnemonic, addrReg, sym, seg)));
    }
  }
}
