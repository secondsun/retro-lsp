package dev.secondsun.tm4e4lsp;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

import com.google.gson.JsonObject;

import dev.secondsun.lsp.Hover;
import dev.secondsun.lsp.InitializeParams;
import dev.secondsun.lsp.InitializeResult;
import dev.secondsun.lsp.LanguageClient;
import dev.secondsun.lsp.LanguageServer;
import dev.secondsun.lsp.TextDocumentPositionParams;
import dev.secondsun.tm4e.core.grammar.IGrammar;
import dev.secondsun.tm4e.core.registry.Registry;
import dev.secondsun.tm4e4lsp.feature.Feature;
import dev.secondsun.tm4e4lsp.feature.HoverFeature;

public class CC65LanguageServer extends LanguageServer {

    Map<URI, List<String>> files = new HashMap<>();
    private final FileService fileService;
    private Registry registry;
    private IGrammar grammar;
    private final LanguageClient client;
    private URI workspaceRoot;
    
    private final HoverFeature hoverFeature;
    
    private final List<Feature<?>> features = new ArrayList<>();

    private static final Logger LOG = Logger.getLogger(CC65LanguageServer.class.getName());

    public CC65LanguageServer(FileService fileService, LanguageClient client) {
        this.client = client;
        try {
            this.fileService = fileService;
            this.registry = new Registry();

            this.grammar = registry.loadGrammarFromPathSync("snes.json",
                    CC65LanguageServer.class.getClassLoader().getResourceAsStream("snes.json"));
            
            this.hoverFeature = new HoverFeature(grammar);
            
            features.add(hoverFeature);

        } catch (Exception e) {
            throw new RuntimeException(e);
        }

    }

    @Override
    public InitializeResult initialize(InitializeParams params) {
        this.workspaceRoot = params.rootUri;
            
        var initializeData = new JsonObject();
        for (Feature<?> feature : features) {
            feature.initialize(initializeData);
        }
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

        return hoverFeature.executeFeature(params, files.get(params.textDocument.uri));

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
