package dev.secondsun.retrolsp.mcp.tools;

import dev.secondsun.retro.util.DefaultWorkspaceIndex;
import dev.secondsun.retro.util.WorkspaceIndex;
import dev.secondsun.retro.util.WorkspaceSnapshot;
import java.net.URI;
import java.util.List;
import java.util.Objects;

/**
 * Static analysis tool that finds references and usages of symbols across the workspace index in
 * CA65/X-GSU assembly by querying immutable {@link WorkspaceSnapshot}s.
 */
public class SfxFindReferencesTool {

  private final WorkspaceIndex workspaceIndex;

  /** Default constructor initializing with an empty in-memory {@link DefaultWorkspaceIndex}. */
  public SfxFindReferencesTool() {
    this(new DefaultWorkspaceIndex());
  }

  /**
   * Constructs tool backed by the provided {@link WorkspaceIndex}.
   *
   * @param workspaceIndex workspace index managing files, snapshots, and occurrence indexes
   */
  public SfxFindReferencesTool(WorkspaceIndex workspaceIndex) {
    this.workspaceIndex = Objects.requireNonNull(workspaceIndex, "workspaceIndex must not be null");
  }

  /** Default cap on the number of entries returned in {@code references}. */
  public static final int DEFAULT_MAX_RESULTS = 50;

  /**
   * A symbol reference location.
   *
   * @param uri canonical {@code file:///} URI
   * @param path workspace-relative path (absolute path if outside the workspace), or null
   * @param line zero-based line
   * @param lineNumber one-based line, matching editors, {@code grep -n} and {@code read_file}
   * @param startIndex start column
   * @param endIndex end column
   * @param kind reference kind ("definition", "call", "read", "write", "macro-invocation",
   *     "include", "use")
   * @param text source line text when contextLines &gt; 0, otherwise null
   */
  public record ReferenceLocation(
      String uri,
      String path,
      int line,
      int lineNumber,
      int startIndex,
      int endIndex,
      String kind,
      String text) {}

  /**
   * Result of a symbol reference search.
   *
   * @param symbol the queried symbol name
   * @param totalReferences total count of references matching the query
   * @param references list of reference locations up to maxResults
   * @param ambiguous whether multiple definitions were found
   * @param definitionCount number of distinct definition sites found
   */
  public record ReferenceResult(
      String symbol,
      int totalReferences,
      List<ReferenceLocation> references,
      boolean ambiguous,
      int definitionCount) {}

  /**
   * Finds references and usages of the given symbol across the workspace without mutating
   * persistent workspace state when inline {@code code} is supplied.
   *
   * @param symbol name of the symbol to find
   * @param code optional inline assembly source code (evaluated as a transient snippet)
   * @param uriStr optional document URI
   * @param line optional 0-based line number for scoped disambiguation
   * @param pathFilter optional workspace-relative path or directory filter
   * @param maxResults maximum number of reference locations to return
   * @param includeDefinitions whether to include definition sites in results
   * @param contextLines number of surrounding context lines to include
   * @return reference search result
   */
  public ReferenceResult find(
      String symbol,
      String code,
      String uriStr,
      Integer line,
      String pathFilter,
      Integer maxResults,
      Boolean includeDefinitions,
      Integer contextLines) {
    URI uri = uriStr != null && !uriStr.isBlank() ? URI.create(uriStr) : null;
    WorkspaceSnapshot snapshot = workspaceIndex.snapshotFor(uri, code);
    String effectiveUriStr =
        (uri == null && code != null && !code.isBlank())
            ? WorkspaceSnapshot.TRANSIENT_SNIPPET_URI.toString()
            : uriStr;

    dev.secondsun.retro.util.vo.ReferenceResult result =
        snapshot.findReferencesResult(
            symbol,
            null,
            effectiveUriStr,
            line,
            pathFilter,
            maxResults,
            includeDefinitions,
            contextLines);

    List<ReferenceLocation> mapped =
        result.references().stream()
            .map(
                rl ->
                    new ReferenceLocation(
                        rl.uri(),
                        rl.path(),
                        rl.line(),
                        rl.lineNumber(),
                        rl.startIndex(),
                        rl.endIndex(),
                        rl.kind(),
                        rl.text()))
            .toList();
    return new ReferenceResult(
        result.symbol(),
        result.totalReferences(),
        mapped,
        result.ambiguous(),
        result.definitionCount());
  }
}
