package dev.secondsun.retrolsp.mcp.tools;

import dev.secondsun.retro.util.CA65Scanner;
import dev.secondsun.retro.util.FileService;
import dev.secondsun.retro.util.ProjectService;
import dev.secondsun.retro.util.SymbolService;
import dev.secondsun.retro.util.vo.TokenizedFile;
import dev.secondsun.sfxoptimizer.macro.CA65MacroExpander;
import java.net.URI;
import java.util.List;
import java.util.Objects;

/**
 * Static analysis tool that expands CA65 and X-GSU macros (.macro, .define, function, for, stack
 * operations) into a concrete instruction stream using CA65MacroExpander and a shared FileService.
 */
public class SfxMacroExpanderTool {

  private final ProjectService projectService;
  private final FileService fileService;

  public SfxMacroExpanderTool() {
    this(new ProjectService(new FileService(), new SymbolService()));
  }

  public SfxMacroExpanderTool(ProjectService projectService) {
    this.projectService = Objects.requireNonNull(projectService);
    this.fileService = projectService.getFileService();
  }

  public record MacroExpandResult(
      String expandedCode, int originalLineCount, int expandedLineCount) {}

  public MacroExpandResult expand(String code) {
    return expand(code, null);
  }

  public MacroExpandResult expand(String code, String uriStr) {
    URI uri = uriStr != null ? URI.create(uriStr) : null;
    TokenizedFile tokenized;
    int originalLines;

    if (code != null && !code.isBlank()) {
      if (uri != null) {
        projectService.updateFileContents(uri, code);
        tokenized = projectService.getFileContents(uri);
      } else {
        CA65Scanner scanner = new CA65Scanner();
        tokenized = scanner.tokenize(code);
      }
      originalLines = code.split("\r?\n", -1).length;
    } else if (uri != null) {
      tokenized = projectService.getFileContents(uri);
      if (tokenized == null || tokenized == TokenizedFile.EMPTY) {
        throw new IllegalArgumentException("File not found in workspace: " + uri);
      }
      originalLines = tokenized.textLines();
    } else {
      return new MacroExpandResult("", 0, 0);
    }

    CA65Scanner scanner = new CA65Scanner();
    CA65MacroExpander expander = new CA65MacroExpander(fileService, scanner);
    TokenizedFile expanded = expander.expand(tokenized);

    List<String> rawLines = expanded.getRawLines();
    String expandedText =
        (rawLines != null && !rawLines.isEmpty()) ? String.join("\n", rawLines) : "";
    int expandedLines = rawLines != null ? rawLines.size() : 0;

    return new MacroExpandResult(expandedText, originalLines, expandedLines);
  }
}
