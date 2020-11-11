package dev.secondsun.tm4e4lsp;

import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import dev.secondsun.tm4e.core.grammar.IGrammar;
import dev.secondsun.tm4e.core.grammar.IToken;
import dev.secondsun.tm4e.core.grammar.ITokenizeLineResult;
import dev.secondsun.tm4e.core.registry.Registry;

import dev.secondsun.lsp.Hover;
import dev.secondsun.lsp.InitializeParams;
import dev.secondsun.lsp.InitializeResult;
import dev.secondsun.lsp.LanguageClient;
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
    private final LanguageClient client;
    private URI workspaceRoot;
    private static final Logger LOG = Logger.getLogger(CC65LanguageServer.class.getName());

    public CC65LanguageServer(FileService fileService, LanguageClient client) {
        this.client = client;
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
    public InitializeResult initialize(InitializeParams params) {
        this.workspaceRoot = params.rootUri;
            
        var initializeData = new JsonObject();
        initializeData.add("hoverProvider", new JsonPrimitive(true));
        return new InitializeResult(initializeData);
    }

    @Override
    public void initialized() {
        LOG.info("initialized");
    }

    public void didChangeConfiguration(dev.secondsun.lsp.DidChangeConfigurationParams params) {

    };

    public void didChangeWatchedFiles(dev.secondsun.lsp.DidChangeWatchedFilesParams params) {};

    @Override
    public Optional<Hover> hover(TextDocumentPositionParams params) {
        LOG.info("hover" + params.toString());
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
        MarkedString res;
        switch (tokenText) {
            case "nop":
                res = new MarkedString("No operation");
                break;
            default:
                res = null;
                break;

        }
        ;
        return res;
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
                return fileService.readLines(key, workspaceRoot);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

}
