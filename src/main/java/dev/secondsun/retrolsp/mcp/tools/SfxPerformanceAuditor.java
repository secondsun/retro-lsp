package dev.secondsun.retrolsp.mcp.tools;

import dev.secondsun.retro.util.FunctionSyntaxHelper;
import dev.secondsun.retro.util.Token;
import dev.secondsun.retro.util.TokenType;
import dev.secondsun.retro.util.sfx.SuperFxIsa;
import dev.secondsun.retro.util.vo.DialectProfile;
import dev.secondsun.retro.util.vo.TokenizedFile;
import dev.secondsun.retro.util.vo.Tokens;
import dev.secondsun.retrolsp.diagnostic.AuditFinding;
import dev.secondsun.retrolsp.diagnostic.DiagnosticSeverity;
import dev.secondsun.retrolsp.mcp.tools.SfxInvariantAuditor.InvariantViolation;
import dev.secondsun.retrolsp.mcp.tools.SfxInvariantAuditor.Severity;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Static SuperFX performance anti-pattern checks. All findings are warnings ({@link
 * DiagnosticSeverity#WARNING} / {@link AuditFinding.Performance}).
 *
 * <ul>
 *   <li>{@code CACHE_IN_LOOP}: {@code cache} inside a {@code for}/{@code forR}/{@code loop} body
 *   <li>{@code CALL_IN_HOT_LOOP}: {@code call} inside a {@code for}/{@code forR}/{@code loop} body
 *       or after {@code cache}
 *   <li>{@code CACHE_BLOCK_OVERFLOW}: estimated cached block exceeds the 512-byte GSU cache
 *   <li>{@code REDUNDANT_RPIX_IN_SUBROUTINE}: {@code rpix} inside a {@code [inline] function} not
 *       preceding {@code stop}
 *   <li>{@code ROMB_IN_HOT_LOOP}: {@code romb} inside a loop body
 * </ul>
 *
 * <p>A line containing {@code perf:ignore} in a comment suppresses warnings on that line.
 */
public class SfxPerformanceAuditor {

  /** Default constructor for SfxPerformanceAuditor. */
  public SfxPerformanceAuditor() {}

  static final int CACHE_SIZE_BYTES = 512;

  /** Threshold at which a cache block containing unresolved calls is considered at risk. */
  static final int CACHE_NEAR_LIMIT_BYTES = 448;

  private record Stmt(
      int line,
      String mnemonic,
      Token opcode,
      List<Token> tokens,
      int opIdx,
      boolean isFunctionHeader,
      boolean isEndFunction) {}

  private record Range(int start, int end) {
    boolean containsExclusiveStart(int line) {
      return line > start && line <= end;
    }
  }

  /**
   * Audits a tokenized file and returns typed {@link AuditFinding.Performance} records.
   *
   * @param file the tokenized file to audit
   * @return list of typed performance findings
   */
  public List<AuditFinding.Performance> auditFindings(TokenizedFile file) {
    if (file == null || file.isEmpty()) {
      return List.of();
    }

    URI uri = file.uri();
    List<Stmt> stmts = new ArrayList<>();
    Map<String, Integer> labelLines = new HashMap<>();
    int total = file.textLines();
    var scopeTree = file.scopeTree();

    for (int i = 0; i < total; i++) {
      if (scopeTree.isMacroBodyLine(i) || scopeTree.dialectAtLine(i) != DialectProfile.SUPERFX) {
        continue;
      }
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
      List<Token> tail = tokens.subList(opIdx, tokens.size());
      boolean isFn = FunctionSyntaxHelper.isFunction(tail);
      boolean isEndFn = FunctionSyntaxHelper.isEndFunction(tail);
      Token op = tokens.get(opIdx);
      stmts.add(
          new Stmt(i + 1, op.text().toLowerCase(Locale.ROOT), op, tokens, opIdx, isFn, isEndFn));
    }

    // Hot loop ranges (for/endfor and label..loop / move r13,r15..loop) and function/cache scopes
    List<Range> hotRanges = new ArrayList<>();
    Deque<Integer> forStack = new ArrayDeque<>();
    Integer lastR13LoopStartExclusive = null;
    int lastLine = stmts.isEmpty() ? 0 : stmts.get(stmts.size() - 1).line();
    Deque<Integer> fnStack = new ArrayDeque<>();
    Map<Integer, Integer> fnEndByStart = new HashMap<>();
    for (Stmt s : stmts) {
      if (s.isFunctionHeader()) {
        fnStack.push(s.line());
        continue;
      }
      if (s.isEndFunction()) {
        if (!fnStack.isEmpty()) {
          fnEndByStart.put(fnStack.pop(), s.line());
        }
        continue;
      }
      switch (s.mnemonic()) {
        case "for", "forr" -> forStack.push(s.line());
        case "endfor" -> {
          if (!forStack.isEmpty()) {
            hotRanges.add(new Range(forStack.pop(), s.line()));
          }
        }
        case "iwt" -> {
          if (isR13(s)) {
            String targetName = r13Target(s);
            Integer targetLine = targetName != null ? labelLines.get(targetName) : null;
            lastR13LoopStartExclusive = targetLine != null ? targetLine - 1 : null;
          }
        }
        case "move" -> {
          if (isMoveR13R15(s)) {
            lastR13LoopStartExclusive = s.line();
          }
        }
        case "loop" -> {
          if (lastR13LoopStartExclusive != null && lastR13LoopStartExclusive < s.line()) {
            hotRanges.add(new Range(lastR13LoopStartExclusive, s.line()));
          }
        }
        default -> {}
      }
    }

    List<AuditFinding.Performance> out = new ArrayList<>();
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
        checkCacheOverflow(stmts, idx, scopeEnd, file, uri, out);
      }
    }

    int fnDepth = 0;
    for (int idx = 0; idx < stmtCount; idx++) {
      Stmt s = stmts.get(idx);
      if (s.isFunctionHeader()) {
        fnDepth++;
        continue;
      }
      if (s.isEndFunction()) {
        fnDepth = Math.max(0, fnDepth - 1);
        continue;
      }
      if (isSuppressed(file, s.line())) {
        continue;
      }
      switch (s.mnemonic()) {
        case "cache" -> {
          boolean inLoop = hotRanges.stream().anyMatch(r -> r.containsExclusiveStart(s.line()));
          if (inLoop) {
            int colStart = s.opcode().getStartIndex();
            int colEnd = s.tokens().get(s.tokens().size() - 1).getEndIndex();
            out.add(
                new AuditFinding.Performance(
                    uri,
                    s.line(),
                    colStart,
                    colEnd,
                    "CACHE_IN_LOOP",
                    DiagnosticSeverity.WARNING,
                    "'cache' inside a loop body resets CBR and invalidates all 32 cache lines on every iteration. Hoist 'cache' outside the loop."));
          }
        }
        case "call" -> {
          boolean inLoop = hotRanges.stream().anyMatch(r -> r.containsExclusiveStart(s.line()));
          boolean inCache =
              cacheRegions.stream().anyMatch(c -> s.line() > c[0] && s.line() <= c[1]);
          if (inLoop || inCache) {
            int colStart = s.opcode().getStartIndex();
            int colEnd = s.tokens().get(s.tokens().size() - 1).getEndIndex();
            out.add(
                new AuditFinding.Performance(
                    uri,
                    s.line(),
                    colStart,
                    colEnd,
                    "CALL_IN_HOT_LOOP",
                    DiagnosticSeverity.WARNING,
                    "'call' inside a hardware loop or cache block incurs caller-save stack traffic and executes at un-cached ROM/RAM speeds if outside the 512-byte window. Consider an inline function or macro expansion."));
          }
        }
        case "romb" -> {
          if (hotRanges.stream().anyMatch(r -> r.containsExclusiveStart(s.line()))) {
            int colStart = s.opcode().getStartIndex();
            int colEnd = s.tokens().get(s.tokens().size() - 1).getEndIndex();
            out.add(
                new AuditFinding.Performance(
                    uri,
                    s.line(),
                    colStart,
                    colEnd,
                    "ROMB_IN_HOT_LOOP",
                    DiagnosticSeverity.WARNING,
                    "'romb' inside a loop body switches the ROM bank on every iteration. Hoist ROM bank selection outside the loop or pre-cache the data in GSURAM."));
          }
        }
        case "rpix" -> {
          if (fnDepth > 0 && !precedesStop(stmts, idx)) {
            int colStart = s.opcode().getStartIndex();
            int colEnd = s.tokens().get(s.tokens().size() - 1).getEndIndex();
            out.add(
                new AuditFinding.Performance(
                    uri,
                    s.line(),
                    colStart,
                    colEnd,
                    "REDUNDANT_RPIX_IN_SUBROUTINE",
                    DiagnosticSeverity.WARNING,
                    "'rpix' inside a function flushes the dual 8-pixel caches on every invocation. Execute 'rpix; rpix' once at the end of the rendering pass before the 8 settling NOPs and 'stop'."));
          }
        }
        default -> {}
      }
    }
    return out;
  }

  /**
   * Audits a tokenized file for SuperFX performance anti-patterns and returns compatibility {@link
   * InvariantViolation} records.
   *
   * @param file the tokenized file to audit
   * @return list of performance invariant violations
   */
  public List<InvariantViolation> audit(TokenizedFile file) {
    return auditFindings(file).stream()
        .map(
            f ->
                new InvariantViolation(
                    f.line(),
                    f.columnStart(),
                    f.columnEnd(),
                    f.rule(),
                    Severity.WARNING,
                    f.message()))
        .toList();
  }

  private void checkCacheOverflow(
      List<Stmt> stmts,
      int cacheIdx,
      int scopeEnd,
      TokenizedFile file,
      URI uri,
      List<AuditFinding.Performance> out) {
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
      bytes += byteWeight(s, file);
    }
    int cacheColStart = cache.opcode().getStartIndex();
    int cacheColEnd = cache.tokens().get(cache.tokens().size() - 1).getEndIndex();
    if (bytes > CACHE_SIZE_BYTES) {
      out.add(
          new AuditFinding.Performance(
              uri,
              cache.line(),
              cacheColStart,
              cacheColEnd,
              "CACHE_BLOCK_OVERFLOW",
              DiagnosticSeverity.WARNING,
              String.format(
                  "Estimated cached block is %d bytes, exceeding the 512-byte GSU instruction cache; the loop will spill into slow ROM fetches.",
                  bytes)));
    } else if (hasCall && bytes >= CACHE_NEAR_LIMIT_BYTES) {
      out.add(
          new AuditFinding.Performance(
              uri,
              cache.line(),
              cacheColStart,
              cacheColEnd,
              "CACHE_BLOCK_OVERFLOW",
              DiagnosticSeverity.WARNING,
              String.format(
                  "Estimated cached block is %d bytes plus unresolved 'call' targets, approaching the 512-byte GSU instruction cache limit.",
                  bytes)));
    }
  }

  /** Assembled size in bytes of a statement via {@link SuperFxIsa#byteWeight}. */
  static int byteWeight(Stmt s, TokenizedFile file) {
    if (s.isFunctionHeader() || s.isEndFunction()) {
      return 0;
    }
    String lineText = file != null ? file.getLineText(s.line() - 1) : null;
    return SuperFxIsa.byteWeight(s.tokens(), s.opIdx(), lineText);
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

  private static boolean isMoveR13R15(Stmt s) {
    if (s.tokens().size() <= s.opIdx() + 2) {
      return false;
    }
    if (!"r13".equalsIgnoreCase(s.tokens().get(s.opIdx() + 1).text())) {
      return false;
    }
    for (int i = s.opIdx() + 2; i < s.tokens().size(); i++) {
      Token t = s.tokens().get(i);
      if (t.type == TokenType.TOK_COMMA) {
        continue;
      }
      return "r15".equalsIgnoreCase(t.text());
    }
    return false;
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
