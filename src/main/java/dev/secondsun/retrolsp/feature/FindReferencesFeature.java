package dev.secondsun.retrolsp.feature;

import com.google.gson.JsonObject;
import dev.secondsun.lsp.Location;
import dev.secondsun.lsp.Position;
import dev.secondsun.lsp.Range;
import dev.secondsun.lsp.ReferenceParams;
import dev.secondsun.retro.util.DefaultWorkspaceIndex;
import dev.secondsun.retro.util.WorkspaceIndex;
import dev.secondsun.retro.util.WorkspaceSnapshot;
import dev.secondsun.retro.util.vo.TokenizedFile;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Feature providing "Find All References" ({@code textDocument/references}) for CA65 and X-GSU
 * assembly symbols, backed by {@link WorkspaceIndex} and {@link WorkspaceSnapshot}.
 */
public class FindReferencesFeature implements Feature<ReferenceParams, List<Location>> {

  private final WorkspaceIndex workspaceIndex;

  /** Constructs a standalone {@code FindReferencesFeature} with a default workspace index. */
  public FindReferencesFeature() {
    this(new DefaultWorkspaceIndex());
  }

  /**
   * Constructs a new {@code FindReferencesFeature} backed by the specified {@link WorkspaceIndex}.
   *
   * @param workspaceIndex the workspace index managing workspace files, scopes, and references
   */
  public FindReferencesFeature(WorkspaceIndex workspaceIndex) {
    this.workspaceIndex = Objects.requireNonNull(workspaceIndex, "workspaceIndex must not be null");
  }

  @Override
  public void initialize(JsonObject initializationData) {
    var referencesOptions = new JsonObject();
    referencesOptions.addProperty("workDoneProgress", false);
    initializationData.add("referencesProvider", referencesOptions);
  }

  @Override
  public Optional<List<Location>> handle(ReferenceParams params, TokenizedFile file) {
    if (params == null
        || params.position == null
        || file == null
        || file.isEmpty()
        || params.position.line < 0
        || params.position.line >= file.textLines()) {
      return Optional.empty();
    }

    URI uri = params.textDocument != null ? params.textDocument.uri : null;
    int line = params.position.line;
    int column = params.position.character;
    boolean includeDeclaration = params.context != null && params.context.includeDeclaration;

    WorkspaceSnapshot snap = workspaceIndex.snapshot();
    if (uri != null && snap.file(uri).isEmpty()) {
      snap = snap.overlay(uri, String.join("\n", file.getRawLines()));
    }

    List<dev.secondsun.retro.util.vo.Location> matches =
        snap.findReferencesAt(file, uri, line, column, includeDeclaration);

    if (matches == null || matches.isEmpty()) {
      return Optional.empty();
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
    return Optional.of(result);
  }
}
