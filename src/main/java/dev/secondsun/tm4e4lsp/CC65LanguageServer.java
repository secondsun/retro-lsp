package dev.secondsun.tm4e4lsp;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

import com.google.gson.Gson;
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
import dev.secondsun.lsp.Location;
import dev.secondsun.lsp.TextDocumentPositionParams;
import dev.secondsun.tm4e.core.grammar.IGrammar;
import dev.secondsun.tm4e.core.registry.Registry;
import dev.secondsun.tm4e4lsp.feature.CompletionFeature;
import dev.secondsun.tm4e4lsp.feature.DirectiveCompletionFeature;
import dev.secondsun.tm4e4lsp.feature.DocumentLinkFeature;
import dev.secondsun.tm4e4lsp.feature.Feature;
import dev.secondsun.tm4e4lsp.feature.HoverFeature;
import dev.secondsun.tm4e4lsp.feature.IncludeCompletionFeature;
import dev.secondsun.tm4e4lsp.util.FileService;
import dev.secondsun.tm4e4lsp.util.SymbolService;

public class CC65LanguageServer extends LanguageServer {

    Map<URI, List<String>> files = new HashMap<>();
    private final FileService fileService;
    private Registry registry;
    private IGrammar grammar;
    private final LanguageClient client;
    private URI workspaceRoot;
    
    private final HoverFeature hoverFeature;
    private DocumentLinkFeature documentLinkFeature;
    private final GoToDefinitionLinkFeature gotoDefinitionLinkFeature;
    private final List<Feature<?, ?>> features = new ArrayList<>();
    private IncludeCompletionFeature includeCompletionFeature;
    private DirectiveCompletionFeature commandCompletionFeature;
    private Path libSFXRoot;
    private SymbolService symbolService;

    private static final Logger LOG = Logger.getLogger(CC65LanguageServer.class.getName());

    public CC65LanguageServer(FileService fileService, LanguageClient client) {
        this.client = client;
        try {
            
            this.fileService = fileService;
            
            this.registry = new Registry();

            this.grammar = registry.loadGrammarFromPathSync("snes.json",
            CC65LanguageServer.class.getClassLoader().getResourceAsStream("snes.json"));
    
            this.symbolService = new SymbolService(registry, grammar);

            this.hoverFeature = new HoverFeature(grammar);
            this.documentLinkFeature = new DocumentLinkFeature(grammar, this.fileService);
            this.gotoDefinitionLinkFeature = new GoToDefinitionLinkFeature(grammar, this.fileService, this.symbolService);
            this.includeCompletionFeature = new IncludeCompletionFeature();
            this.commandCompletionFeature = new DirectiveCompletionFeature();

            features.add(includeCompletionFeature);
            features.add(hoverFeature);
            features.add(documentLinkFeature);
            features.add(commandCompletionFeature);
            

        } catch (Exception e) {
            throw new RuntimeException(e);
        }

    }

    @Override
    public InitializeResult initialize(InitializeParams params) {

        
        LOG.info("initialize");
        LOG.info(new Gson().toJson(params));
        this.workspaceRoot = params.rootUri;

        this.fileService.addSearchPath(workspaceRoot);
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
        LOG.info("didChangeConfiguration");
        
        var libSFXRootParam = params.settings.get("retroca65").getAsJsonObject().get("libSFXRoot").getAsString();
        this.libSFXRoot = Paths.get(this.workspaceRoot).resolve(libSFXRootParam);
        this.fileService.addSearchPath(this.libSFXRoot.toUri());
        LOG.info(new Gson().toJson(params));
        LOG.info(this.libSFXRoot.toString());

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
        var feature = features.stream().filter(feature2 -> feature2 instanceof CompletionFeature &&((CompletionFeature)feature2).canComplete(params, files.get(params.textDocument.uri))).findFirst();
        if (feature.isEmpty()) {
            return Optional.empty();
        } else {
            return ((CompletionFeature)feature.get()).handle(params, files.get(params.textDocument.uri));
        }
        
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

    @Override
    public Optional<List<Location>> gotoDefinition(TextDocumentPositionParams params) {
        LOG.info("Goto Definition  " + params.toString());
        prepareFile(params.textDocument.uri);

        return gotoDefinitionLinkFeature.handle(params, files.get(params.textDocument.uri));
    }

    /**
     * If a file for the URI has not been read, read it.
     * 
     * @param uri the file path to load from the workspace root
     */
    private void prepareFile(URI uri) {
        files.computeIfAbsent(uri, (key) -> {
            try {
                var lines = fileService.readLines(key);
                symbolService.extractDefinitions(uri, lines);
                return lines;
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

}
