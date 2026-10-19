package dev.secondsun.retrolsp.mcp.tools;

import dev.secondsun.retro.util.FileService;
import dev.secondsun.retro.util.ProjectService;
import dev.secondsun.retro.util.SymbolService;
import dev.secondsun.retro.util.vo.Location;
import dev.secondsun.retro.util.vo.TokenizedFile;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Static analysis tool that resolves symbol definitions, scopes, and documentation in CA65/X-GSU
 * assembly using a shared SymbolService and ProjectService.
 */
public class SfxSymbolLookupTool {

  private final ProjectService projectService;
  private final SymbolService symbolService;

  public SfxSymbolLookupTool() {
    this(new ProjectService(new FileService(), new SymbolService()));
  }

  public SfxSymbolLookupTool(ProjectService projectService) {
    this.projectService = Objects.requireNonNull(projectService);
    this.symbolService = projectService.getSymbolService();
  }

  public record SymbolLocation(String uri, int line, int startIndex, int endIndex) {
    public static SymbolLocation from(Location loc) {
      if (loc == null) return null;
      return new SymbolLocation(
          loc.filename() != null ? loc.filename().toString() : null,
          loc.line(),
          loc.startIndex(),
          loc.endIndex());
    }
  }

  public record SymbolLookupResult(
      String symbol,
      boolean found,
      SymbolLocation location,
      String documentation,
      String scope,
      List<SymbolLocation> allLocations) {}

  public SymbolLookupResult lookup(String symbol, String code, String uriStr, Integer line) {
    if (symbol == null || symbol.isBlank()) {
      return new SymbolLookupResult("", false, null, null, null, List.of());
    }

    URI uri = uriStr != null ? URI.create(uriStr) : null;
    TokenizedFile file = projectService.resolveFile(uri, code);
    if (file != null && file.uri != null) {
      uri = file.uri;
    }

    Location loc = projectService.resolveLocation(symbol, uri, line);
    String doc = projectService.resolveDocumentation(symbol, uri, line);
    String scopeName = null;
    if (line != null && uri != null) {
      var scopeOpt = projectService.getScopeAt(uri, line);
      if (scopeOpt.isPresent()) {
        scopeName = scopeOpt.get().name();
      }
    }

    List<Location> rawLocations = projectService.resolveLocations(symbol, uri, line);
    List<SymbolLocation> allLocations = new ArrayList<>();
    if (rawLocations != null) {
      for (Location l : rawLocations) {
        allLocations.add(SymbolLocation.from(l));
      }
    }

    boolean found = (loc != null || (doc != null && !doc.isBlank()) || !allLocations.isEmpty());
    return new SymbolLookupResult(
        symbol, found, SymbolLocation.from(loc), doc, scopeName, allLocations);
  }
}
