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

    private static Logger LOG = Logger.getLogger(GoToDefinitionLinkFeature.class.getName());

    private SymbolService symbolService;
    public GoToDefinitionLinkFeature( SymbolService symbolService) {
        this.symbolService = symbolService;
    }

   
    public Optional<List<Location>> handle(TextDocumentPositionParams params, TokenizedFile list) {
        var line = list.getLineText(params.position.line);
        var tokens = list.getLine(params.position.line);
        LOG.info("Handle GoToDefinition");
        LOG.info(new Gson().toJson(line));
        LOG.info(new Gson().toJson(tokens));
        if (tokens != null && tokens.tokens() != null && tokens.tokens().size() > 0) {
            var column = params.position.character;
            LOG.info("column" + column);
            Optional<Token> token = tokens.tokens().stream().filter(it-> it.getStartIndex() <= column && it.getEndIndex() >= column ).filter(it ->it.getType() == TokenType.TOK_IDENT).findFirst();
            LOG.info("token.ispresent:" + new Gson().toJson(token.isPresent()));
            if (token.isPresent()) {
                var label = line.subSequence(token.get().getStartIndex(), token.get().getEndIndex());
                LOG.info("token is Present label:" + new Gson().toJson(label));
                var location = symbolService.getLocation(label.toString().trim());
                LOG.info("token is Present location:" +  new Gson().toJson(location));
                if (location != null) {
                    var toReturn = new Location(location.filename(), new Range(new Position(location.line(), location.startIndex()), new Position(location.line(), location.endIndex())));
                    LOG.info("toReturn:" + new Gson().toJson(toReturn));
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
