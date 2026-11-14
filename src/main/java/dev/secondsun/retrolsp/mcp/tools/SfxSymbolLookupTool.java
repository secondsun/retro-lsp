package dev.secondsun.retrolsp.mcp.tools;

import dev.secondsun.retro.util.DefaultWorkspaceIndex;
import dev.secondsun.retro.util.WorkspaceIndex;
import dev.secondsun.retro.util.WorkspaceSnapshot;
import dev.secondsun.retro.util.vo.Location;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Static analysis tool that resolves symbol definitions, scopes, and documentation in CA65/X-GSU
 * assembly using immutable {@link WorkspaceSnapshot}s from a {@link WorkspaceIndex}.
 */
public class SfxSymbolLookupTool {

  private final WorkspaceIndex workspaceIndex;

  /** Default constructor initializing with an empty in-memory {@link DefaultWorkspaceIndex}. */
  public SfxSymbolLookupTool() {
    this(new DefaultWorkspaceIndex());
  }

  /**
   * Constructs tool backed by the provided {@link WorkspaceIndex}.
   *
   * @param workspaceIndex workspace index for resolving files, snapshots, and symbols
   */
  public SfxSymbolLookupTool(WorkspaceIndex workspaceIndex) {
    this.workspaceIndex = Objects.requireNonNull(workspaceIndex, "workspaceIndex must not be null");
  }

  /** Default cap on the number of entries returned in {@code allLocations}. */
  public static final int DEFAULT_MAX_RESULTS = 20;

  /**
   * Path fragments identifying fixture copies and generated output that should never outrank real
   * sources when resolving a symbol.
   */
  private static final List<String> LOW_PRIORITY_FRAGMENTS =
      List.of("/src/test/resources/", "/.build/", "/.build_libsfx/", "/build_tools/");

  /**
   * A symbol location.
   *
   * @param uri canonical {@code file:///} URI
   * @param path workspace-relative path (absolute path if outside the workspace), or null
   * @param line zero-based line (kept for backward compatibility)
   * @param lineNumber one-based line, matching editors, {@code grep -n} and {@code read_file}
   * @param startIndex start column
   * @param endIndex end column
   */
  public record SymbolLocation(
      String uri, String path, int line, int lineNumber, int startIndex, int endIndex) {}

  /**
   * Result of a symbol lookup containing definition site, doc comments, and occurrences.
   *
   * @param symbol name of the looked up symbol
   * @param found whether the symbol definition was found
   * @param location primary definition location
   * @param documentation doc comments preceding definition
   * @param scope enclosing scope name
   * @param allLocations all matching symbol locations up to maxResults
   * @param totalLocations total number of matching locations found
   */
  public record SymbolLookupResult(
      String symbol,
      boolean found,
      SymbolLocation location,
      String documentation,
      String scope,
      List<SymbolLocation> allLocations,
      int totalLocations) {}

  /**
   * Resolves a symbol with default path filter and result limit.
   *
   * @param symbol name of the symbol to lookup
   * @param code optional inline assembly source code (evaluated as a transient snippet)
   * @param uriStr optional document URI string
   * @param line optional line number for contextual resolution
   * @return symbol lookup result
   */
  public SymbolLookupResult lookup(String symbol, String code, String uriStr, Integer line) {
    return lookup(symbol, code, uriStr, line, null, null);
  }

  /**
   * Resolves a symbol definition and documentation without mutating workspace state when inline
   * {@code code} is supplied.
   *
   * @param symbol name of the symbol to lookup
   * @param code optional inline assembly source code (evaluated as a transient snippet)
   * @param uriStr optional document URI string
   * @param line optional line number for contextual resolution
   * @param pathFilter optional workspace-relative folder/file prefix; only locations under it are
   *     returned
   * @param maxResults optional cap on {@code allLocations} (default {@link #DEFAULT_MAX_RESULTS})
   * @return symbol lookup result
   */
  public SymbolLookupResult lookup(
      String symbol,
      String code,
      String uriStr,
      Integer line,
      String pathFilter,
      Integer maxResults) {
    if (symbol == null || symbol.isBlank()) {
      return new SymbolLookupResult("", false, null, null, null, List.of(), 0);
    }

    URI uri = uriStr != null && !uriStr.isBlank() ? URI.create(uriStr) : null;
    WorkspaceSnapshot snapshot = workspaceIndex.snapshotFor(uri, code);
    URI lookupUri =
        (uri == null && code != null && !code.isBlank())
            ? WorkspaceSnapshot.TRANSIENT_SNIPPET_URI
            : uri;

    Location loc = snapshot.resolveLocation(symbol, lookupUri, line);
    String doc = snapshot.resolveDocumentation(symbol, lookupUri, line);
    String scopeName = null;
    if (line != null && lookupUri != null) {
      var scopeOpt = snapshot.getScopeAt(lookupUri, line);
      if (scopeOpt.isPresent()) {
        scopeName = scopeOpt.get().name();
      }
    }

    List<Location> rawLocations = snapshot.resolveLocations(symbol, lookupUri, line);
    List<SymbolLocation> ordered = new ArrayList<>();
    if (rawLocations != null) {
      for (Location l : rawLocations) {
        SymbolLocation sl = toSymbolLocation(l);
        if (sl != null) {
          ordered.add(sl);
        }
      }
    }
    ordered.sort(Comparator.comparingInt(sl -> isLowPriority(sl.uri()) ? 1 : 0));

    if (pathFilter != null && !pathFilter.isBlank()) {
      String prefix = pathFilter.replaceFirst("^\\./", "");
      ordered.removeIf(sl -> sl.path() == null || !sl.path().startsWith(prefix));
    }

    SymbolLocation primary = ordered.isEmpty() ? toSymbolLocation(loc) : ordered.get(0);
    if (!ordered.isEmpty() && loc != null) {
      SymbolLocation raw = toSymbolLocation(loc);
      if (raw != null && !isLowPriority(raw.uri()) && ordered.contains(raw)) {
        primary = raw;
      }
    }

    int total = ordered.size();
    int cap = maxResults != null && maxResults > 0 ? maxResults : DEFAULT_MAX_RESULTS;
    List<SymbolLocation> capped = total > cap ? new ArrayList<>(ordered.subList(0, cap)) : ordered;

    boolean found = (primary != null || (doc != null && !doc.isBlank()) || total > 0);
    return new SymbolLookupResult(symbol, found, primary, doc, scopeName, capped, total);
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

  private SymbolLocation toSymbolLocation(Location loc) {
    if (loc == null) {
      return null;
    }
    String uriStr = null;
    String path = null;
    if (loc.filename() != null) {
      URI u = loc.filename();
      uriStr = u.toString();
      try {
        if ("file".equalsIgnoreCase(u.getScheme()) && u.getPath() != null) {
          Path abs = Path.of(u).toAbsolutePath().normalize();
          uriStr = abs.toUri().toString();
          Path root = workspaceIndex.getWorkspaceRoot();
          path =
              root != null && abs.startsWith(root)
                  ? root.relativize(abs).toString()
                  : abs.toString();
        }
      } catch (RuntimeException e) {
        // keep the raw URI string
      }
    }
    return new SymbolLocation(
        uriStr, path, loc.line(), loc.line() + 1, loc.startIndex(), loc.endIndex());
  }
}
