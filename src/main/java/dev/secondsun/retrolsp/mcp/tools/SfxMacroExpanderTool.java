package dev.secondsun.retrolsp.mcp.tools;

import dev.secondsun.retro.util.CA65Scanner;
import dev.secondsun.retro.util.FileService;
import dev.secondsun.retro.util.vo.TokenizedFile;
import dev.secondsun.sfxoptimizer.macro.CA65MacroExpander;
import java.util.List;

/**
 * Static analysis tool that expands CA65 and X-GSU macros (.macro, .define, function, for, stack
 * operations) into a concrete instruction stream using CA65MacroExpander.
 */
public class SfxMacroExpanderTool {

  public record MacroExpandResult(
      String expandedCode, int originalLineCount, int expandedLineCount) {}

  public MacroExpandResult expand(String code) {
    if (code == null || code.isBlank()) {
      return new MacroExpandResult("", 0, 0);
    }

    int originalLines = code.split("\r?\n", -1).length;
    CA65Scanner scanner = new CA65Scanner();
    TokenizedFile tokenized = scanner.tokenize(code);

    CA65MacroExpander expander = new CA65MacroExpander(new FileService(), scanner);
    TokenizedFile expanded = expander.expand(tokenized);

    List<String> rawLines = expanded.getRawLines();
    String expandedText =
        (rawLines != null && !rawLines.isEmpty()) ? String.join("\n", rawLines) : "";
    int expandedLines = rawLines != null ? rawLines.size() : 0;

    return new MacroExpandResult(expandedText, originalLines, expandedLines);
  }
}
