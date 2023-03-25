package dev.secondsun.tm4e4lsp.feature;

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
import dev.secondsun.tm4e.core.grammar.IGrammar;
import dev.secondsun.tm4e.core.grammar.IToken;
import dev.secondsun.tm4e4lsp.util.SymbolService;

public class GoToDefinitionLinkFeature implements Feature<TextDocumentPositionParams, List<Location>>{

    private IGrammar grammar;
    private SymbolService symbolService;
    public GoToDefinitionLinkFeature(IGrammar grammar, SymbolService symbolService) {
        this.grammar = grammar;
        this.symbolService = symbolService;
    }

   
    public Optional<List<Location>> handle(TextDocumentPositionParams params, List<String> list) {
        var line = list.get(params.position.line);
        var tokens = grammar.tokenizeLine(line);
        Logger.getAnonymousLogger().info(new Gson().toJson(tokens));
        if (tokens != null && tokens.getTokens() != null && tokens.getTokens().length > 0) {
            var column = params.position.character;
            Optional<IToken> token = Arrays.stream(tokens.getTokens()).filter(it-> it.getStartIndex() <= column && it.getEndIndex() >= column ).filter(it ->it.getScopes().contains("variable.other.readwrite.assembly")).findFirst();
            
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
