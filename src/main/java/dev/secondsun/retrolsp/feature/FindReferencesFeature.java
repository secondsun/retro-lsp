package dev.secondsun.retrolsp.feature;

import com.google.gson.JsonObject;
import dev.secondsun.lsp.Location;
import dev.secondsun.lsp.Position;
import dev.secondsun.lsp.Range;
import dev.secondsun.lsp.ReferenceParams;
import dev.secondsun.retro.util.FileService;
import dev.secondsun.retro.util.ProjectService;
import dev.secondsun.retro.util.SymbolService;
import dev.secondsun.retro.util.vo.TokenizedFile;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Feature providing "Find All References" ({@code textDocument/references}) for CA65 and X-GSU
 * assembly symbols, backed by {@code retro-common}'s {@link ProjectService} and {@link
 * dev.secondsun.retro.util.ReferenceService}.
 */
public class FindReferencesFeature implements Feature<ReferenceParams, List<Location>> {

  private final ProjectService projectService;

  /**
   * Constructs a new {@code FindReferencesFeature} with the specified project service.
   *
   * @param projectService the project service managing workspace files, scopes, and references
   */
  public FindReferencesFeature(ProjectService projectService) {
    this.projectService = Objects.requireNonNull(projectService, "projectService must not be null");
  }

  /**
   * Constructs a new {@code FindReferencesFeature} with the specified file and symbol services.
   *
   * @param fileService the file service used to read source files
   * @param symbolService the symbol service managing symbol definitions
   */
  public FindReferencesFeature(FileService fileService, SymbolService symbolService) {
    this(new ProjectService(fileService, symbolService));
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

    List<dev.secondsun.retro.util.vo.Location> matches =
        projectService.findReferencesAt(file, uri, line, column, includeDeclaration);

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
