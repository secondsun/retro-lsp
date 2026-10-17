package dev.secondsun.retrolsp.mcp.tools;

import dev.secondsun.retro.util.CA65Scanner;
import dev.secondsun.retro.util.Token;
import dev.secondsun.retro.util.TokenType;
import dev.secondsun.retro.util.vo.TokenizedFile;
import dev.secondsun.retro.util.vo.Tokens;
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

  public record InvariantViolation(int line, String rule, Severity severity, String message) {}

  public record AuditResult(boolean valid, List<InvariantViolation> violations, String summary) {}

  private static final Pattern HEX_LITERAL = Pattern.compile("(?i)(?:\\$|0x)([0-9a-f]+)");
  private static final Pattern DEC_LITERAL = Pattern.compile("\\b(\\d+)\\b");
  private static final Pattern BIN_LITERAL = Pattern.compile("%([01]+)");

  private record PendingModal(String opcode, int line) {}

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
    TokenizedFile file;

    if (code != null && !code.isBlank()) {
      if (uri != null) {
        projectService.updateFileContents(uri, code);
        file = projectService.getFileContents(uri);
      } else {
        CA65Scanner scanner = new CA65Scanner();
        file = scanner.tokenize(code);
      }
    } else if (uri != null) {
      file = projectService.getFileContents(uri);
      if (file == null || file == TokenizedFile.EMPTY) {
        throw new IllegalArgumentException("File not found in workspace: " + uri);
      }
    } else {
      return new AuditResult(true, List.of(), "Empty code: no invariants audited.");
    }

    List<InvariantViolation> violations = new ArrayList<>();
    int consecutiveNops = 0;
    PendingModal pendingModal = null;

    int totalLines = file.textLines();
    for (int lineIdx = 0; lineIdx < totalLines; lineIdx++) {
      int lineNum = lineIdx + 1; // 1-based line number
      Tokens lineTokens = file.getLine(lineIdx);
      if (lineTokens == null || lineTokens.tokens().isEmpty()) {
        continue;
      }

      List<Token> tokens = lineTokens.tokens();
      String rawLine = file.getLineText(lineIdx) != null ? file.getLineText(lineIdx).trim() : "";

      // Check if line defines a label
      boolean isLabelOnly = isLabelDefinitionLine(tokens, rawLine);
      if (isLabelOnly) {
        if (pendingModal != null) {
          violations.add(
              new InvariantViolation(
                  pendingModal.line(),
                  "DANGLING_MODAL_PREFIX",
                  Severity.ERROR,
                  String.format(
                      "Modal instruction '%s' on line %d has no valid target instruction (followed by label on line %d). Modal prefixes apply to the single immediately following instruction only.",
                      pendingModal.opcode(), pendingModal.line(), lineNum)));
          pendingModal = null;
        }
        consecutiveNops = 0; // Labels break straight-line NOP settling
        continue;
      }

      // Find primary mnemonic/instruction
      int opcodeTokenIdx = findOpcodeIndex(tokens);
      if (opcodeTokenIdx < 0) {
        // Directives (.segment, .byte, etc.) or pure comments
        continue;
      }

      Token opcodeToken = tokens.get(opcodeTokenIdx);
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

      // Check Invariant 1: 8-NOP pipeline settling before 'stop'
      if ("stop".equals(mnemonic)) {
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
        pendingModal = new PendingModal(mnemonic, lineNum);
      } else {
        if (pendingModal != null) {
          // Check if instruction is a terminal/control instruction where modal is invalid
          if (isControlInstruction(mnemonic)) {
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
    }

    // Trailing dangling modal prefix at end of code
    if (pendingModal != null) {
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

  private boolean isLabelDefinitionLine(List<Token> tokens, String rawLine) {
    if (tokens.isEmpty()) return false;
    if (rawLine.endsWith(":")) {
      return true;
    }
    return tokens.size() >= 2 && tokens.get(tokens.size() - 1).type == TokenType.TOK_COLON;
  }

  private int findOpcodeIndex(List<Token> tokens) {
    for (int i = 0; i < tokens.size(); i++) {
      Token t = tokens.get(i);
      if (t.text().startsWith(".")) {
        return -1; // Directive line
      }
      if (t.text().endsWith(":") || t.type == TokenType.TOK_COLON) {
        continue;
      }
      // If previous token was an identifier and this is a colon, it was a label
      if (i + 1 < tokens.size() && tokens.get(i + 1).type == TokenType.TOK_COLON) {
        i++; // skip label and colon
        continue;
      }
      return i;
    }
    return -1;
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
}
