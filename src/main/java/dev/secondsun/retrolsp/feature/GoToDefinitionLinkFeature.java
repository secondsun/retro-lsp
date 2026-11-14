package dev.secondsun.retrolsp.feature;

import com.google.gson.JsonObject;
import dev.secondsun.lsp.Location;
import dev.secondsun.lsp.Position;
import dev.secondsun.lsp.Range;
import dev.secondsun.lsp.TextDocumentPositionParams;
import dev.secondsun.retro.util.DefaultWorkspaceIndex;
import dev.secondsun.retro.util.Token;
import dev.secondsun.retro.util.WorkspaceIndex;
import dev.secondsun.retro.util.WorkspaceSnapshot;
import dev.secondsun.retro.util.vo.TokenizedFile;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Feature providing navigation to symbol definitions for CA65 and X-GSU assembly code. */
public class GoToDefinitionLinkFeature
    implements Feature<TextDocumentPositionParams, List<Location>> {

  private final WorkspaceIndex workspaceIndex;

  /** Constructs a standalone {@code GoToDefinitionLinkFeature} with a default workspace index. */
  public GoToDefinitionLinkFeature() {
    this(new DefaultWorkspaceIndex());
  }

  /**
   * Constructs a new {@code GoToDefinitionLinkFeature} backed by the specified {@link
   * WorkspaceIndex}.
   *
   * @param workspaceIndex workspace index used to look up symbol definitions
   */
  public GoToDefinitionLinkFeature(WorkspaceIndex workspaceIndex) {
    this.workspaceIndex = Objects.requireNonNull(workspaceIndex, "workspaceIndex must not be null");
  }

  @Override
  public Optional<List<Location>> handle(TextDocumentPositionParams params, TokenizedFile file) {
    if (file == null
        || file.isEmpty()
        || params == null
        || params.position == null
        || params.position.line < 0
        || params.position.line >= file.textLines()) {
      return Optional.empty();
    }

    int line = params.position.line;
    int column = params.position.character;
    URI uri = params.textDocument != null ? params.textDocument.uri : null;

    WorkspaceSnapshot snapshot = workspaceIndex.snapshot();
    if (uri != null && snapshot.file(uri).isEmpty()) {
      snapshot = snapshot.overlay(uri, String.join("\n", file.getRawLines()));
    }

    // 1. Attempt lookup of qualified symbol at cursor (e.g. Point::coordX, ::globalFunc, mySymbol)
    Optional<String> qualifiedSymbol = file.getQualifiedSymbolAt(line, column);
    if (qualifiedSymbol.isPresent()) {
      var locations = findLocations(snapshot, qualifiedSymbol.get(), uri, line);
      if (!locations.isEmpty()) {
        return Optional.of(locations);
      }
    }

    // 2. Fallback to individual token text at cursor
    Optional<Token> token = file.getTokenAt(line, column);
    if (token.isPresent()) {
      String text = token.get().text();
      if (text != null && !text.isBlank()) {
        var locations = findLocations(snapshot, text.trim(), uri, line);
        if (!locations.isEmpty()) {
          return Optional.of(locations);
        }
      }
    }

    return Optional.empty();
  }

  private List<Location> findLocations(WorkspaceSnapshot snapshot, String name, URI uri, int line) {
    List<dev.secondsun.retro.util.vo.Location> matches;
    if (uri != null) {
      matches = snapshot.resolveLocations(name, uri, line);
      if (matches == null || matches.isEmpty()) {
        var single = snapshot.resolveLocation(name, uri, line);
        if (single != null) {
          matches = List.of(single);
        }
      }
    } else {
      matches = snapshot.resolveLocations(name);
      if (matches == null || matches.isEmpty()) {
        var single = snapshot.resolveLocation(name);
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
