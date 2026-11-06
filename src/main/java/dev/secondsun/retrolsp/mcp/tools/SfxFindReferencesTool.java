package dev.secondsun.retrolsp.mcp.tools;

import dev.secondsun.retro.util.FileService;
import dev.secondsun.retro.util.ProjectService;
import dev.secondsun.retro.util.ReferenceService;
import dev.secondsun.retro.util.SymbolService;
import java.util.List;
import java.util.Objects;

/**
 * Static analysis tool that finds references and usages of symbols across the workspace index in
 * CA65/X-GSU assembly by delegating to {@link ReferenceService} in {@code retro-common}.
 */
public class SfxFindReferencesTool {

  private final ProjectService projectService;
  private final ReferenceService referenceService;

  /** Default constructor initializing with an empty in-memory ProjectService. */
  public SfxFindReferencesTool() {
    this(new ProjectService(new FileService(), new SymbolService()));
  }

  /**
   * Constructs tool with the provided project service.
   *
   * @param projectService the project service managing files and symbol indexes
   */
  public SfxFindReferencesTool(ProjectService projectService) {
    this.projectService = Objects.requireNonNull(projectService);
    this.referenceService = projectService.getReferenceService();
  }

  /** Default cap on the number of entries returned in {@code references}. */
  public static final int DEFAULT_MAX_RESULTS = ReferenceService.DEFAULT_MAX_RESULTS;

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
   * @param text source line text when contextLines > 0, otherwise null
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
   * Finds references and usages of the given symbol across the workspace.
   *
   * @param symbol name of the symbol to find
   * @param code optional assembly source code to resolve context from
   * @param uriStr optional document URI
   * @param line optional line number
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
    ReferenceService.ReferenceResult result =
        referenceService.find(
            symbol, code, uriStr, line, pathFilter, maxResults, includeDefinitions, contextLines);
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
