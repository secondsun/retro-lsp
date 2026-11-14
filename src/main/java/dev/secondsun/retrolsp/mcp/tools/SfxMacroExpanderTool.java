package dev.secondsun.retrolsp.mcp.tools;

import dev.secondsun.retro.util.CA65Scanner;
import dev.secondsun.retro.util.DefaultWorkspaceIndex;
import dev.secondsun.retro.util.WorkspaceIndex;
import dev.secondsun.retro.util.WorkspaceSnapshot;
import dev.secondsun.retro.util.vo.TokenizedFile;
import dev.secondsun.sfxoptimizer.macro.CA65MacroExpander;
import java.net.URI;
import java.util.List;
import java.util.Objects;

/**
 * Static analysis tool that expands CA65 and X-GSU macros ({@code .macro}, {@code .define}, {@code
 * function}, {@code for}, stack operations) into a concrete instruction stream using {@link
 * CA65MacroExpander} and an immutable {@link WorkspaceSnapshot}.
 */
public class SfxMacroExpanderTool {

  private final WorkspaceIndex workspaceIndex;

  /** Default constructor initializing with an empty in-memory {@link DefaultWorkspaceIndex}. */
  public SfxMacroExpanderTool() {
    this(new DefaultWorkspaceIndex());
  }

  /**
   * Constructs tool backed by the provided {@link WorkspaceIndex}.
   *
   * @param workspaceIndex workspace index managing files, macros, and snapshots
   */
  public SfxMacroExpanderTool(WorkspaceIndex workspaceIndex) {
    this.workspaceIndex = Objects.requireNonNull(workspaceIndex, "workspaceIndex must not be null");
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
   * Expands macro directives in inline assembly code (as a transient snippet overlay) or a
   * workspace document URI without mutating persistent workspace state.
   *
   * @param code assembly source code or {@code null}
   * @param uriStr workspace document URI or {@code null}
   * @return macro expansion result
   */
  public MacroExpandResult expand(String code, String uriStr) {
    URI uri = uriStr != null && !uriStr.isBlank() ? URI.create(uriStr) : null;
    WorkspaceSnapshot snapshot = workspaceIndex.snapshotFor(uri, code);
    TokenizedFile tokenized;
    if (code != null && !code.isBlank()) {
      URI lookupUri = uri != null ? uri : WorkspaceSnapshot.TRANSIENT_SNIPPET_URI;
      tokenized = snapshot.file(lookupUri);
    } else if (uri != null) {
      tokenized = workspaceIndex.resolveFile(uri, null);
      if (tokenized == null || tokenized.isEmpty()) {
        throw new IllegalArgumentException("File not found in workspace: " + uri);
      }
    } else {
      return new MacroExpandResult("", 0, 0);
    }

    if (tokenized == null || tokenized.isEmpty()) {
      return new MacroExpandResult("", 0, 0);
    }

    int originalLines =
        (code != null && !code.isBlank()) ? code.split("\r?\n", -1).length : tokenized.textLines();

    CA65Scanner scanner = new CA65Scanner();
    CA65MacroExpander expander = new CA65MacroExpander(snapshot, scanner);
    TokenizedFile expanded = expander.expand(tokenized);

    List<String> rawLines = expanded.getRawLines();
    String expandedText =
        (rawLines != null && !rawLines.isEmpty()) ? String.join("\n", rawLines) : "";
    int expandedLines = rawLines != null ? rawLines.size() : 0;

    return new MacroExpandResult(expandedText, originalLines, expandedLines);
  }
}
