package dev.secondsun.retrolsp.mcp.tools;

import dev.secondsun.retro.util.Token;
import dev.secondsun.retro.util.TokenType;
import dev.secondsun.retro.util.instruction.GSUInstruction;
import dev.secondsun.retro.util.vo.TokenizedFile;
import dev.secondsun.retro.util.vo.Tokens;
import dev.secondsun.retrolsp.mcp.tools.SfxInvariantAuditor.InvariantViolation;
import dev.secondsun.retrolsp.mcp.tools.SfxInvariantAuditor.Severity;
import java.util.*;

/**
 * Static SuperFX performance anti-pattern checks. All findings are {@link Severity#WARNING}.
 *
 * <ul>
 *   <li>{@code CALL_IN_HOT_LOOP}: {@code call} inside a for/forR/loop body or after {@code cache}
 *   <li>{@code CACHE_BLOCK_OVERFLOW}: estimated cached block exceeds the 512-byte GSU cache
 *   <li>{@code REDUNDANT_RPIX_IN_SUBROUTINE}: {@code rpix} inside a function not preceding {@code
 *       stop}
 *   <li>{@code ROMB_IN_HOT_LOOP}: {@code romb} inside a loop body
 * </ul>
 *
 * A line containing {@code perf:ignore} in a comment suppresses warnings on that line.
 */
public class SfxPerformanceAuditor {

  static final int CACHE_SIZE_BYTES = 512;

  /** Threshold at which a cache block containing unresolved calls is considered at risk. */
  static final int CACHE_NEAR_LIMIT_BYTES = 448;

  private record Stmt(int line, String mnemonic, Token opcode, List<Token> tokens, int opIdx) {}

  private record Range(int start, int end) {
    boolean containsExclusiveStart(int line) {
      return line > start && line <= end;
    }
  }

  public List<InvariantViolation> audit(TokenizedFile file) {
    if (file == null || file.isEmpty()) {
      return List.of();
    }

    List<Stmt> stmts = new ArrayList<>();
    Map<String, Integer> labelLines = new HashMap<>();
    int total = file.textLines();
    for (int i = 0; i < total; i++) {
      Tokens lt = file.getLine(i);
      if (lt == null || lt.tokens().isEmpty()) {
        continue;
      }
      List<Token> tokens = lt.tokens();
      int opIdx = 0;
      if (tokens.size() > 1 && tokens.get(1).type == TokenType.TOK_COLON) {
        labelLines.put(stripLabel(tokens.get(0).text()), i + 1);
        opIdx = 2;
      } else if (tokens.get(0).text().endsWith(":") && tokens.get(0).text().length() > 1) {
        labelLines.put(stripLabel(tokens.get(0).text()), i + 1);
        opIdx = 1;
      }
      if (opIdx >= tokens.size()) {
        continue;
      }
      Token op = tokens.get(opIdx);
      stmts.add(new Stmt(i + 1, op.text().toLowerCase(Locale.ROOT), op, tokens, opIdx));
    }

    // Hot loop ranges (for/endfor and label..loop) and function/cache scopes
    List<Range> hotRanges = new ArrayList<>();
    Deque<Integer> forStack = new ArrayDeque<>();
    String lastR13Label = null;
    int lastLine = stmts.isEmpty() ? 0 : stmts.get(stmts.size() - 1).line();
    Deque<Integer> fnStack = new ArrayDeque<>();
    Map<Integer, Integer> fnEndByStart = new HashMap<>();
    for (Stmt s : stmts) {
      switch (s.mnemonic()) {
        case "for", "forr" -> forStack.push(s.line());
        case "endfor" -> {
          if (!forStack.isEmpty()) {
            hotRanges.add(new Range(forStack.pop(), s.line()));
          }
        }
        case "function" -> fnStack.push(s.line());
        case "endfunction" -> {
          if (!fnStack.isEmpty()) {
            fnEndByStart.put(fnStack.pop(), s.line());
          }
        }
        case "iwt" -> {
          if (isR13(s)) {
            lastR13Label = r13Target(s);
          }
        }
        case "loop" -> {
          Integer target = lastR13Label != null ? labelLines.get(lastR13Label) : null;
          if (target != null && target <= s.line()) {
            hotRanges.add(new Range(target - 1, s.line()));
          }
        }
        default -> {}
      }
    }

    List<InvariantViolation> out = new ArrayList<>();
    int stmtCount = stmts.size();
    List<int[]> cacheRegions = new ArrayList<>(); // {cacheLine, scopeEnd}
    for (int idx = 0; idx < stmtCount; idx++) {
      Stmt s = stmts.get(idx);
      if ("cache".equals(s.mnemonic())) {
        int scopeEnd = lastLine;
        for (Map.Entry<Integer, Integer> e : fnEndByStart.entrySet()) {
          if (e.getKey() <= s.line() && e.getValue() >= s.line()) {
            scopeEnd = Math.min(scopeEnd, e.getValue());
          }
        }
        cacheRegions.add(new int[] {s.line(), scopeEnd});
        checkCacheOverflow(stmts, idx, scopeEnd, file, out);
      }
    }

    int fnDepth = 0;
    for (int idx = 0; idx < stmtCount; idx++) {
      Stmt s = stmts.get(idx);
      if (isSuppressed(file, s.line())) {
        continue;
      }
      switch (s.mnemonic()) {
        case "function" -> fnDepth++;
        case "endfunction" -> fnDepth = Math.max(0, fnDepth - 1);
        case "cache" -> {
          boolean inLoop = hotRanges.stream().anyMatch(r -> r.containsExclusiveStart(s.line()));
          if (inLoop) {
            out.add(
                new InvariantViolation(
                    s.line(),
                    "CACHE_IN_LOOP",
                    Severity.WARNING,
                    "'cache' inside a loop body resets CBR and invalidates all 32 cache lines on every iteration. Hoist 'cache' outside the loop."));
          }
        }
        case "call" -> {
          boolean inLoop = hotRanges.stream().anyMatch(r -> r.containsExclusiveStart(s.line()));
          boolean inCache =
              cacheRegions.stream().anyMatch(c -> s.line() > c[0] && s.line() <= c[1]);
          if (inLoop || inCache) {
            out.add(
                new InvariantViolation(
                    s.line(),
                    "CALL_IN_HOT_LOOP",
                    Severity.WARNING,
                    "'call' inside a hardware loop or cache block incurs caller-save stack traffic and executes at un-cached ROM/RAM speeds if outside the 512-byte window. Consider an inline function or macro expansion."));
          }
        }
        case "romb" -> {
          if (hotRanges.stream().anyMatch(r -> r.containsExclusiveStart(s.line()))) {
            out.add(
                new InvariantViolation(
                    s.line(),
                    "ROMB_IN_HOT_LOOP",
                    Severity.WARNING,
                    "'romb' inside a loop body switches the ROM bank on every iteration. Hoist ROM bank selection outside the loop or pre-cache the data in GSURAM."));
          }
        }
        case "rpix" -> {
          if (fnDepth > 0 && !precedesStop(stmts, idx)) {
            out.add(
                new InvariantViolation(
                    s.line(),
                    "REDUNDANT_RPIX_IN_SUBROUTINE",
                    Severity.WARNING,
                    "'rpix' inside a function flushes the dual 8-pixel caches on every invocation. Execute 'rpix; rpix' once at the end of the rendering pass before the 8 settling NOPs and 'stop'."));
          }
        }
        default -> {}
      }
    }
    return out;
  }

  private void checkCacheOverflow(
      List<Stmt> stmts,
      int cacheIdx,
      int scopeEnd,
      TokenizedFile file,
      List<InvariantViolation> out) {
    Stmt cache = stmts.get(cacheIdx);
    if (isSuppressed(file, cache.line())) {
      return;
    }
    int end = scopeEnd;
    int lastLoopLine = -1;
    for (int i = cacheIdx + 1; i < stmts.size(); i++) {
      Stmt s = stmts.get(i);
      if (s.line() > scopeEnd) {
        break;
      }
      if ("loop".equals(s.mnemonic()) || "endfor".equals(s.mnemonic())) {
        lastLoopLine = s.line();
      }
    }
    if (lastLoopLine > 0) {
      end = lastLoopLine;
    }
    int bytes = 0;
    boolean hasCall = false;
    for (int i = cacheIdx + 1; i < stmts.size(); i++) {
      Stmt s = stmts.get(i);
      if (s.line() > end) {
        break;
      }
      if ("call".equals(s.mnemonic())) {
        hasCall = true;
      }
      bytes += byteWeight(s);
    }
    if (bytes > CACHE_SIZE_BYTES) {
      out.add(
          new InvariantViolation(
              cache.line(),
              "CACHE_BLOCK_OVERFLOW",
              Severity.WARNING,
              String.format(
                  "Estimated cached block is %d bytes, exceeding the 512-byte GSU instruction cache; the loop will spill into slow ROM fetches.",
                  bytes)));
    } else if (hasCall && bytes >= CACHE_NEAR_LIMIT_BYTES) {
      out.add(
          new InvariantViolation(
              cache.line(),
              "CACHE_BLOCK_OVERFLOW",
              Severity.WARNING,
              String.format(
                  "Estimated cached block is %d bytes plus unresolved 'call' targets, approaching the 512-byte GSU instruction cache limit.",
                  bytes)));
    }
  }

  /** Approximate assembled size in bytes of a statement. */
  static int byteWeight(Stmt s) {
    String m = s.mnemonic();
    if (m.startsWith(".") || m.equals("function") || m.equals("endfunction")) {
      return 0;
    }
    boolean hasImmediate =
        s.tokens().stream().skip(s.opIdx() + 1L).anyMatch(t -> t.type == TokenType.TOK_HASH);
    switch (m) {
      case "iwt":
        return 3;
      case "lm":
      case "sm":
        return 4;
      case "lms":
      case "sms":
      case "for":
        return 3;
      case "forr":
        return 5;
      case "ibt":
      case "bra":
      case "beq":
      case "bne":
      case "bcc":
      case "bcs":
      case "bpl":
      case "bmi":
      case "bvc":
      case "bvs":
      case "bge":
      case "blt":
      case "endfor":
      case "return":
        return 2;
      case "call":
        return 4;
      default:
        break;
    }
    if (GSUInstruction.isInstruction(s.opcode())) {
      return hasImmediate ? 2 : 1;
    }
    return 4; // unknown macro invocation: conservative estimate
  }

  private static boolean precedesStop(List<Stmt> stmts, int idx) {
    for (int i = idx + 1; i < stmts.size(); i++) {
      String m = stmts.get(i).mnemonic();
      if ("rpix".equals(m) || "nop".equals(m)) {
        continue;
      }
      return "stop".equals(m);
    }
    return false;
  }

  private static boolean isSuppressed(TokenizedFile file, int line) {
    String text = file.getLineText(line - 1);
    return text != null && text.contains("perf:ignore");
  }

  private static boolean isR13(Stmt s) {
    return s.tokens().size() > s.opIdx() + 1
        && "r13".equalsIgnoreCase(s.tokens().get(s.opIdx() + 1).text());
  }

  /** Returns the lowercase label name if the statement is {@code iwt r13, #label}. */
  private static String r13Target(Stmt s) {
    if (!isR13(s)) {
      return null;
    }
    for (int i = s.opIdx() + 2; i < s.tokens().size(); i++) {
      Token t = s.tokens().get(i);
      String text = t.text().replace("#", "").trim();
      if (text.isEmpty() || t.type == TokenType.TOK_COMMA || t.type == TokenType.TOK_HASH) {
        continue;
      }
      return stripLabel(text);
    }
    return null;
  }

  private static String stripLabel(String text) {
    String t = text.trim();
    if (t.endsWith(":")) {
      t = t.substring(0, t.length() - 1);
    }
    return t.toLowerCase(Locale.ROOT);
  }
}
