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

  /** Default constructor initializing with an empty in-memory ProjectService. */
  public SfxMacroExpanderTool() {
    this(new ProjectService(new FileService(), new SymbolService()));
  }

  /**
   * Constructs tool with the provided project service.
   *
   * @param projectService project service managing files and symbol lookups
   */
  public SfxMacroExpanderTool(ProjectService projectService) {
    this.projectService = Objects.requireNonNull(projectService);
    this.fileService = projectService.getFileService();
  }

  /**
   * Result of a macro expansion.
   *
   * @param expandedCode the expanded assembly source code
   * @param originalLineCount line count of the source code
   * @param expandedLineCount line count of the expanded code
   */
  public record MacroExpandResult(
      String expandedCode, int originalLineCount, int expandedLineCount) {}

  /**
   * Expands macro directives in raw assembly code string.
   *
   * @param code assembly source code
   * @return macro expansion result
   */
  public MacroExpandResult expand(String code) {
    return expand(code, null);
  }

  /**
   * Expands macro directives in assembly code or workspace document URI.
   *
   * @param code assembly source code or null
   * @param uriStr workspace document URI or null
   * @return macro expansion result
   */
  public MacroExpandResult expand(String code, String uriStr) {
    URI uri = uriStr != null ? URI.create(uriStr) : null;
    TokenizedFile tokenized = projectService.resolveFile(uri, code);

    if (tokenized == null || tokenized.isEmpty()) {
      if (uri != null && (code == null || code.isBlank())) {
        throw new IllegalArgumentException("File not found in workspace: " + uri);
      }
      return new MacroExpandResult("", 0, 0);
    }

    int originalLines =
        (code != null && !code.isBlank()) ? code.split("\r?\n", -1).length : tokenized.textLines();

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
