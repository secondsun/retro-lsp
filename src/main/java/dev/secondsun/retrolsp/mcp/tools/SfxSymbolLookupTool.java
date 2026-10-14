package dev.secondsun.retrolsp.mcp.tools;

import dev.secondsun.retro.util.CA65Scanner;
import dev.secondsun.retro.util.SymbolService;
import dev.secondsun.retro.util.vo.Location;
import dev.secondsun.retro.util.vo.TokenizedFile;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * Static analysis tool that resolves symbol definitions, scopes, and documentation in CA65/X-GSU
 * assembly using SymbolService.
 */
public class SfxSymbolLookupTool {

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

    URI uri = uriStr != null ? URI.create(uriStr) : URI.create("file:///source.s");
    SymbolService symbolService = new SymbolService();

    if (code != null && !code.isBlank()) {
      CA65Scanner scanner = new CA65Scanner();
      TokenizedFile tokenized = scanner.tokenize(code);
      tokenized.uri = uri;
      symbolService.extractDefinitions(tokenized);
    }

    Location loc = null;
    String doc = null;
    String scopeName = null;

    if (line != null) {
      loc = symbolService.getLocation(symbol, uri, line);
      doc = symbolService.getDocumentation(symbol, uri, line);
      var scopeOpt = symbolService.getScopeAt(uri, line);
      if (scopeOpt.isPresent()) {
        scopeName = scopeOpt.get().name();
      }
    }

    if (loc == null) {
      loc = symbolService.getLocation(symbol);
    }
    if (doc == null || doc.isBlank()) {
      doc = symbolService.getDocumentation(symbol);
    }

    List<Location> rawLocations = symbolService.getLocations(symbol);
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
