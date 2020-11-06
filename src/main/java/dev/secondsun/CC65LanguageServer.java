package dev.secondsun;

import java.io.IOException;
import java.lang.StackWalker.Option;
import java.net.URI;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collector;
import java.util.stream.Collectors;

import org.eclipse.tm4e.core.grammar.IGrammar;
import org.eclipse.tm4e.core.grammar.IToken;
import org.eclipse.tm4e.core.grammar.ITokenizeLineResult;
import org.eclipse.tm4e.core.registry.Registry;

import dev.secondsun.lsp.Hover;
import dev.secondsun.lsp.LanguageServer;
import dev.secondsun.lsp.MarkedString;
import dev.secondsun.lsp.Position;
import dev.secondsun.lsp.Range;
import dev.secondsun.lsp.TextDocumentPositionParams;

public class CC65LanguageServer extends LanguageServer {

    Map<URI, List<String>> files = new HashMap<>();
    private final FileService fileService;
    private Registry registry;
    private IGrammar grammar;

    public CC65LanguageServer(FileService fileService) {
        try {
            this.fileService = fileService;
            this.registry = new Registry();

            this.grammar = registry.loadGrammarFromPathSync("snes.json",
                    CC65LanguageServer.class.getClassLoader().getResourceAsStream("snes.json"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

    }

    @Override
    public Optional<Hover> hover(TextDocumentPositionParams params) {
        prepareFile(params.textDocument.uri);

        String line = files.get(params.textDocument.uri).get(params.position.line);
        ITokenizeLineResult lineTokens = grammar.tokenizeLine(line);

        Optional<IToken> maybeToken = getTokenAt(lineTokens, params.position.character);

        if (maybeToken.isPresent()) {
            var token = maybeToken.get();
            String tokenText = getTokenText(line, token);
            var hover = new Hover();
            hover.range = new Range(new Position(params.position.line, token.getStartIndex()),
                    new Position(params.position.line, token.getEndIndex()));
            var result = lookup(tokenText);                    
            if (result != null) {
                hover.contents = Arrays.asList(result);
            }
            return Optional.of(hover);
        } else {
            return Optional.empty();
        }
    }

    private MarkedString lookup(String tokenText) {
        return switch (tokenText) {
            case "nop" -> new MarkedString("No operation");

            default -> null;

        };
    }

    private String getTokenText(String line, IToken token) {
        var start = token.getStartIndex();
        var end = token.getEndIndex();
        return line.substring(start, end);
    }

    private Optional<IToken> getTokenAt(ITokenizeLineResult lineTokens, int position) {
        var tokenList = Arrays.asList(lineTokens.getTokens());
        return tokenList.stream().filter(token -> token.getStartIndex() < position && token.getEndIndex() >= position)
                .findFirst();

    }

    /**
     * If a file for the URI has not been read, read it.
     * 
     * @param uri the file path to load from the workspace root
     */
    private void prepareFile(URI uri) {
        files.computeIfAbsent(uri, (key) -> {
            try {
                return fileService.readLines(key);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

}
