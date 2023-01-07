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

import dev.secondsun.lsp.CompletionList;
import dev.secondsun.lsp.DocumentLink;
import dev.secondsun.lsp.DocumentLinkParams;
import dev.secondsun.lsp.FileChangeType;
import dev.secondsun.lsp.Hover;
import dev.secondsun.lsp.InitializeParams;
import dev.secondsun.lsp.InitializeResult;
import dev.secondsun.lsp.LanguageClient;
import dev.secondsun.lsp.LanguageServer;
import dev.secondsun.lsp.TextDocumentPositionParams;
import dev.secondsun.tm4e.core.grammar.IGrammar;
import dev.secondsun.tm4e.core.registry.Registry;
import dev.secondsun.tm4e4lsp.feature.DocumentLinkFeature;
import dev.secondsun.tm4e4lsp.feature.Feature;
import dev.secondsun.tm4e4lsp.feature.HoverFeature;
import dev.secondsun.tm4e4lsp.feature.IncludeCompletionFeature;

public class CC65LanguageServer extends LanguageServer {

    Map<URI, List<String>> files = new HashMap<>();
    private final FileService fileService;
    private Registry registry;
    private IGrammar grammar;
    private final LanguageClient client;
    private URI workspaceRoot;
    
    private final HoverFeature hoverFeature;
    private DocumentLinkFeature documentLinkFeature;
    
    private final List<Feature<?, ?>> features = new ArrayList<>();
    private IncludeCompletionFeature includeCompletionFeature;

    private static final Logger LOG = Logger.getLogger(CC65LanguageServer.class.getName());

    public CC65LanguageServer(FileService fileService, LanguageClient client) {
        this.client = client;
        try {
            this.fileService = fileService;
            this.registry = new Registry();

            this.grammar = registry.loadGrammarFromPathSync("snes.json",
                    CC65LanguageServer.class.getClassLoader().getResourceAsStream("snes.json"));
            
            this.hoverFeature = new HoverFeature(grammar);
            this.documentLinkFeature = new DocumentLinkFeature(grammar);
            this.includeCompletionFeature = new IncludeCompletionFeature();

            features.add(includeCompletionFeature);
            features.add(hoverFeature);
            features.add(documentLinkFeature);
            

        } catch (Exception e) {
            throw new RuntimeException(e);
        }

    }

    @Override
    public InitializeResult initialize(InitializeParams params) {
        this.workspaceRoot = params.rootUri;
            
        var initializeData = new JsonObject();
        for (Feature<?, ?> feature : features) {
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

    public void didChangeWatchedFiles(dev.secondsun.lsp.DidChangeWatchedFilesParams params) {
        for (var change : params.changes) {
            switch(change.type) {
                case FileChangeType.Changed: 
                    files.remove(change.uri);
                break;
                default:
                continue;
            }
        }
    };

    @Override
    public Optional<CompletionList> completion(TextDocumentPositionParams params) {
        prepareFile(params.textDocument.uri);
        return includeCompletionFeature.handle(params, files.get(params.textDocument.uri));
    }
    

    @Override
    public Optional<Hover> hover(TextDocumentPositionParams params) {
        LOG.info("hover" + params.toString());
        prepareFile(params.textDocument.uri);

        return hoverFeature.handle(params, files.get(params.textDocument.uri));

    }

    @Override
    public List<DocumentLink> documentLink(DocumentLinkParams params) {
        LOG.info("DocumentLink " + params.toString());
        prepareFile(params.textDocument.uri);

        return documentLinkFeature.handle(params, files.get(params.textDocument.uri)).orElse(new ArrayList<>());
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
