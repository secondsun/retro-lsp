package dev.secondsun.retrolsp.feature;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import dev.secondsun.lsp.Location;
import dev.secondsun.lsp.Position;
import dev.secondsun.lsp.Range;
import dev.secondsun.lsp.TextDocumentPositionParams;
import dev.secondsun.retro.util.SymbolService;
import dev.secondsun.retro.util.Token;
import dev.secondsun.retro.util.TokenType;
import dev.secondsun.retro.util.vo.TokenizedFile;


public class GoToDefinitionLinkFeature implements Feature<TextDocumentPositionParams, List<Location>>{

    private SymbolService symbolService;
    public GoToDefinitionLinkFeature( SymbolService symbolService) {
        this.symbolService = symbolService;
    }

   
    public Optional<List<Location>> handle(TextDocumentPositionParams params, TokenizedFile list) {
        var line = list.getLineText(params.position.line);
        var tokens = list.getLine(params.position.line);
        Logger.getAnonymousLogger().info(new Gson().toJson(tokens));
        if (tokens != null && tokens.tokens() != null && tokens.tokens().size() > 0) {
            var column = params.position.character;
            Optional<Token> token = tokens.tokens().stream().filter(it-> it.getStartIndex() <= column && it.getEndIndex() >= column ).filter(it ->it.getType() == TokenType.TOK_IDENT).findFirst();
            
            if (token.isPresent()) {
                var label = line.subSequence(token.get().getStartIndex(), token.get().getEndIndex());
                var location = symbolService.getLocation(label.toString());
                Logger.getAnonymousLogger().info("gotoDefinition");
                Logger.getAnonymousLogger().info(label.toString());
                if (location != null) {
                    Logger.getAnonymousLogger().info(location.toString());
                    var toReturn = new Location(location.filename(), new Range(new Position(location.line(), location.startIndex()), new Position(location.line(), location.endIndex())));
                    return Optional.of(List.of(toReturn));
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
