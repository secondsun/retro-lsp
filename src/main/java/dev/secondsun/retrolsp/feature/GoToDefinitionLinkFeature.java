package dev.secondsun.retrolsp.feature;

import com.google.gson.JsonObject;
import dev.secondsun.lsp.Location;
import dev.secondsun.lsp.Position;
import dev.secondsun.lsp.Range;
import dev.secondsun.lsp.TextDocumentPositionParams;
import dev.secondsun.retro.util.SymbolService;
import dev.secondsun.retro.util.Token;
import dev.secondsun.retro.util.TokenType;
import dev.secondsun.retro.util.vo.TokenizedFile;
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

  public Optional<List<Location>> handle(TextDocumentPositionParams params, TokenizedFile list) {
    if (list == null || params.position.line < 0 || params.position.line >= list.textLines()) {
      return Optional.empty();
    }
    var tokens = list.getLine(params.position.line);
    if (tokens != null && tokens.tokens() != null && !tokens.tokens().isEmpty()) {
      var column = params.position.character;
      Optional<Token> token =
          tokens.tokens().stream()
              .filter(it -> it.getStartIndex() <= column && it.getEndIndex() >= column)
              .filter(it -> it.getType() == TokenType.TOK_IDENT)
              .findFirst();
      if (token.isPresent()) {
        var label = token.get().text();
        if (label != null) {
          var location = symbolService.getLocation(label.trim());
          if (location != null) {
            var toReturn =
                new Location(
                    location.filename(),
                    new Range(
                        new Position(location.line(), location.startIndex()),
                        new Position(location.line(), location.endIndex())));
            return Optional.of(List.of(toReturn));
          }
        }
      }
    }
    return Optional.empty();
  }

  @Override
  public void initialize(JsonObject initializationData) {
    var definitionOptions = new JsonObject();
    definitionOptions.addProperty("workDoneProgress", false);
    initializationData.add("definitionProvider", definitionOptions);
  }
}
