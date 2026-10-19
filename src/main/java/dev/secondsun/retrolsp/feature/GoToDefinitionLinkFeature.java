package dev.secondsun.retrolsp.feature;

import com.google.gson.JsonObject;
import dev.secondsun.lsp.Location;
import dev.secondsun.lsp.Position;
import dev.secondsun.lsp.Range;
import dev.secondsun.lsp.TextDocumentPositionParams;
import dev.secondsun.retro.util.SymbolService;
import dev.secondsun.retro.util.Token;
import dev.secondsun.retro.util.vo.TokenizedFile;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Feature providing navigation to symbol definitions for CA65 assembly code. */
public class GoToDefinitionLinkFeature
    implements Feature<TextDocumentPositionParams, List<Location>> {

  private final SymbolService symbolService;

  /**
   * Constructs a new {@code GoToDefinitionLinkFeature} with the specified symbol service.
   *
   * @param symbolService the symbol service used to look up symbol definitions
   */
  public GoToDefinitionLinkFeature(SymbolService symbolService) {
    this.symbolService = symbolService;
  }

  @Override
  public Optional<List<Location>> handle(TextDocumentPositionParams params, TokenizedFile file) {
    if (file == null
        || file.isEmpty()
        || params.position.line < 0
        || params.position.line >= file.textLines()) {
      return Optional.empty();
    }

    int line = params.position.line;
    int column = params.position.character;
    URI uri = params.textDocument != null ? params.textDocument.uri : null;

    // 1. First attempt lookup of qualified symbol at cursor (e.g. Point::coordX, ::globalFunc,
    // mySymbol)
    Optional<String> qualifiedSymbol = file.getQualifiedSymbolAt(line, column);
    if (qualifiedSymbol.isPresent()) {
      var locations = findLocations(qualifiedSymbol.get(), uri, line);
      if (!locations.isEmpty()) {
        return Optional.of(locations);
      }
    }

    // 2. Fallback to individual token text at cursor (e.g. scope name if qualified symbol didn't
    // resolve)
    Optional<Token> token = file.getTokenAt(line, column);
    if (token.isPresent()) {
      String text = token.get().text();
      if (text != null && !text.isBlank()) {
        var locations = findLocations(text.trim(), uri, line);
        if (!locations.isEmpty()) {
          return Optional.of(locations);
        }
      }
    }

    return Optional.empty();
  }

  private List<Location> findLocations(String name, URI uri, int line) {
    List<dev.secondsun.retro.util.vo.Location> matches = null;
    if (uri != null) {
      matches = symbolService.getLocations(name, uri, line);
      if (matches == null || matches.isEmpty()) {
        var single = symbolService.getLocation(name, uri, line);
        if (single != null) {
          matches = List.of(single);
        }
      }
    } else {
      matches = symbolService.getLocations(name);
      if (matches == null || matches.isEmpty()) {
        var single = symbolService.getLocation(name);
        if (single != null) {
          matches = List.of(single);
        }
      }
    }

    if (matches == null || matches.isEmpty()) {
      return List.of();
    }

    List<Location> result = new ArrayList<>(matches.size());
    for (var loc : matches) {
      result.add(
          new Location(
              loc.filename(),
              new Range(
                  new Position(loc.line(), loc.startIndex()),
                  new Position(loc.line(), loc.endIndex()))));
    }
    return result;
  }

  @Override
  public void initialize(JsonObject initializationData) {
    var definitionOptions = new JsonObject();
    definitionOptions.addProperty("workDoneProgress", false);
    initializationData.add("definitionProvider", definitionOptions);
  }
}
