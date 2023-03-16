package dev.secondsun.tm4e4lsp;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;


import dev.secondsun.lsp.Location;
import dev.secondsun.lsp.Position;
import dev.secondsun.lsp.Range;
import dev.secondsun.lsp.TextDocumentPositionParams;
import dev.secondsun.tm4e.core.grammar.IGrammar;
import dev.secondsun.tm4e.core.grammar.IToken;
import dev.secondsun.tm4e.core.grammar.ITokenizeLineResult;
import dev.secondsun.tm4e4lsp.util.FileService;
import dev.secondsun.tm4e4lsp.util.SymbolService;

public class GoToDefinitionLinkFeature {

    private IGrammar grammar;
    private FileService fileService;
    private SymbolService symbolService;
    public GoToDefinitionLinkFeature(IGrammar grammar, FileService fileService, SymbolService symbolService) {
        this.grammar = grammar;
        this.fileService = fileService;
        this.symbolService = symbolService;
    }

    public Optional<List<Location>> handle(TextDocumentPositionParams params, List<String> list) {
        var line = list.get(params.position.line);
        var tokens = grammar.tokenizeLine(line);
        if (tokens != null && tokens.getTokens() != null && tokens.getTokens().length > 0) {
            var column = params.position.character;
            Optional<IToken> token = Arrays.stream(tokens.getTokens()).filter(it-> it.getStartIndex() <= column && it.getEndIndex() >= column ).filter(it ->it.getScopes().contains("variable.other.readwrite.assembly")).findFirst();
            
            if (token.isPresent()) {
                var label = line.subSequence(token.get().getStartIndex(), token.get().getEndIndex());
                var location = symbolService.getLocation(label.toString());
                if (location != null) {
                    var toReturn = new Location(location.filename(), new Range(new Position(location.line(), location.startIndex()), new Position(location.line(), location.endIndex())));
                    return Optional.of(List.of(toReturn));
                }
            }
        }
        return Optional.empty();
    }

}
