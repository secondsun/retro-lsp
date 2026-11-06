package dev.secondsun.retrolsp.mcp.tools;

import dev.secondsun.retro.util.FileService;
import dev.secondsun.retro.util.ProjectService;
import dev.secondsun.retro.util.SymbolService;
import dev.secondsun.retro.util.vo.Location;
import dev.secondsun.retro.util.vo.SymbolReference;
import dev.secondsun.retro.util.vo.TokenizedFile;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Static analysis tool that finds references and usages of symbols across the workspace index in
 * CA65/X-GSU assembly.
 */
public class SfxFindReferencesTool {

  private final ProjectService projectService;
  private final SymbolService symbolService;

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
    this.symbolService = projectService.getSymbolService();
  }

  /** Default cap on the number of entries returned in {@code references}. */
  public static final int DEFAULT_MAX_RESULTS = 50;

  /**
   * Path fragments identifying fixture copies and generated output that should never outrank real
   * sources when resolving references.
   */
  private static final List<String> LOW_PRIORITY_FRAGMENTS =
      List.of("/src/test/resources/", "/.build/", "/.build_libsfx/", "/build_tools/");

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
   * @param line optional 1-based line number
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
    if (symbol == null || symbol.isBlank()) {
      return new ReferenceResult("", 0, List.of(), false, 0);
    }

    URI uri = uriStr != null ? URI.create(uriStr) : null;
    TokenizedFile file = projectService.resolveFile(uri, code);
    if (file != null && file.uri != null) {
      uri = file.uri;
    }

    boolean includeDefs = Boolean.TRUE.equals(includeDefinitions);
    int ctxLines = contextLines != null ? contextLines : 0;

    List<Location> allDefs = projectService.resolveLocations(symbol, uri, line);
    int defCount = allDefs != null ? allDefs.size() : 0;
    boolean ambiguous = (line == null && defCount > 1);

    List<SymbolReference> rawReferences =
        projectService.findDetailedReferences(symbol, uri, line, includeDefs);

    List<ReferenceLocation> ordered = new ArrayList<>();
    if (rawReferences != null) {
      for (SymbolReference ref : rawReferences) {
        ReferenceLocation rl = toReferenceLocation(ref, ctxLines);
        if (rl != null) {
          ordered.add(rl);
        }
      }
    }

    // Stable sort: real sources first, fixture copies/generated output last.
    ordered.sort(Comparator.comparingInt(rl -> isLowPriority(rl.uri()) ? 1 : 0));

    if (pathFilter != null && !pathFilter.isBlank()) {
      String prefix = pathFilter.replaceFirst("^\\./", "");
      ordered.removeIf(rl -> rl.path() == null || !rl.path().startsWith(prefix));
    }

    int total = ordered.size();
    int cap = maxResults != null && maxResults > 0 ? maxResults : DEFAULT_MAX_RESULTS;
    List<ReferenceLocation> capped =
        total > cap ? new ArrayList<>(ordered.subList(0, cap)) : ordered;

    return new ReferenceResult(symbol, total, capped, ambiguous, defCount);
  }

  private static boolean isLowPriority(String uri) {
    if (uri == null) {
      return false;
    }
    for (String fragment : LOW_PRIORITY_FRAGMENTS) {
      if (uri.contains(fragment)) {
        return true;
      }
    }
    return false;
  }

  private ReferenceLocation toReferenceLocation(SymbolReference ref, int contextLines) {
    if (ref == null) {
      return null;
    }
    String uriStr = null;
    String path = null;
    if (ref.filename() != null) {
      URI u = ref.filename();
      uriStr = u.toString();
      try {
        if ("file".equalsIgnoreCase(u.getScheme()) && u.getPath() != null) {
          Path abs = Path.of(u).toAbsolutePath().normalize();
          uriStr = abs.toUri().toString();
          Path root = projectService.getWorkspaceRoot();
          path =
              root != null && abs.startsWith(root)
                  ? root.relativize(abs).toString()
                  : abs.toString();
        }
      } catch (RuntimeException e) {
        // keep the raw URI string
      }
    }

    String text = null;
    if (contextLines > 0 && ref.lineText() != null) {
      text = ref.lineText().stripTrailing();
    }

    return new ReferenceLocation(
        uriStr,
        path,
        ref.line(),
        ref.line() + 1,
        ref.startIndex(),
        ref.endIndex(),
        ref.kind(),
        text);
  }
}
