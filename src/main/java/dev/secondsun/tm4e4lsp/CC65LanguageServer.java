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
import dev.secondsun.lsp.LanguageServer;
import dev.secondsun.lsp.Location;
import dev.secondsun.lsp.TextDocumentPositionParams;
import dev.secondsun.tm4e.core.grammar.IGrammar;
import dev.secondsun.tm4e.core.registry.Registry;
import dev.secondsun.tm4e4lsp.feature.CompletionFeature;
import dev.secondsun.tm4e4lsp.feature.DirectiveCompletionFeature;
import dev.secondsun.tm4e4lsp.feature.DocumentLinkFeature;
import dev.secondsun.tm4e4lsp.feature.Feature;
import dev.secondsun.tm4e4lsp.feature.GoToDefinitionLinkFeature;
import dev.secondsun.tm4e4lsp.feature.HoverFeature;
import dev.secondsun.tm4e4lsp.feature.IncludeCompletionFeature;
import dev.secondsun.tm4e4lsp.util.FileService;
import dev.secondsun.tm4e4lsp.util.ProjectService;
import dev.secondsun.tm4e4lsp.util.SymbolService;

public class CC65LanguageServer extends LanguageServer {

    
    private final FileService fileService;
    private Registry registry;
    private IGrammar grammar;
    //TODO Actually make use of language client
    //private final LanguageClient client;
    private URI workspaceRoot;
    
    private final HoverFeature hoverFeature;
    private DocumentLinkFeature documentLinkFeature;
    private final GoToDefinitionLinkFeature gotoDefinitionLinkFeature;
    private final List<Feature<?, ?>> features = new ArrayList<>();
    private IncludeCompletionFeature includeCompletionFeature;
    private DirectiveCompletionFeature commandCompletionFeature;
    private Path libSFXRoot;
    private SymbolService symbolService;
    private ProjectService projectService;

    private static final Logger LOG = Logger.getLogger(CC65LanguageServer.class.getName());
    public static final Gson GSON = new Gson();
    public CC65LanguageServer() {
        
        try {
            
            this.fileService = new FileService();
            
            this.registry = new Registry();

            this.grammar = registry.loadGrammarFromPathSync("snes.json",
            CC65LanguageServer.class.getClassLoader().getResourceAsStream("snes.json"));
    
            this.symbolService = new SymbolService(registry, grammar);
            this.projectService = new ProjectService(fileService, symbolService);
            this.hoverFeature = new HoverFeature(grammar);
            this.documentLinkFeature = new DocumentLinkFeature(grammar, this.fileService);
            this.gotoDefinitionLinkFeature = new GoToDefinitionLinkFeature(grammar,  this.symbolService);
            this.includeCompletionFeature = new IncludeCompletionFeature();
            this.commandCompletionFeature = new DirectiveCompletionFeature();

            features.add(includeCompletionFeature);
            features.add(gotoDefinitionLinkFeature);
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
        this.projectService.includeDir(this.workspaceRoot);
        
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
        this.libSFXRoot = Path.of(this.workspaceRoot).resolve(libSFXRootParam);
        projectService.includeDir(this.libSFXRoot.toUri());
        LOG.info(new Gson().toJson(params));
        LOG.info(this.libSFXRoot.toString());

    };

    public void didChangeWatchedFiles(dev.secondsun.lsp.DidChangeWatchedFilesParams params) {
        LOG.info("didChangeWatchedFiles(");
        LOG.info(GSON.toJson(params));
        for (var change : params.changes) {
            switch(change.type) {
                case FileChangeType.Changed: 
                    projectService.refreshFileContents(change.uri);
                break;
                default:
                continue;
            }
        }
    };

    @Override
    public Optional<CompletionList> completion(TextDocumentPositionParams params) {
        var file = projectService.getFileContents(params.textDocument.uri);
        var feature = features.stream().filter(feature2 -> feature2 instanceof CompletionFeature && 
                                                        ((CompletionFeature)feature2)
                                                        .canComplete(params, file)).findFirst();
        if (feature.isEmpty()) {
            return Optional.empty();
        } else {
            return ((CompletionFeature)feature.get()).handle(params, file);
        }
        
    }
    

    @Override
    public Optional<Hover> hover(TextDocumentPositionParams params) {
        LOG.info("hover " + GSON.toJson(params));

        return hoverFeature.handle(params, projectService.getFileContents(params.textDocument.uri));

    }

    @Override
    public List<DocumentLink> documentLink(DocumentLinkParams params) {
        LOG.info("DocumentLink " + GSON.toJson(params));

        return documentLinkFeature.handle(params, projectService.getFileContents(params.textDocument.uri)).orElse(new ArrayList<>());
    }

    @Override
    public Optional<List<Location>> gotoDefinition(TextDocumentPositionParams params) {
        LOG.info("Goto Definition  " + GSON.toJson(params));

        return gotoDefinitionLinkFeature.handle(params, projectService.getFileContents(params.textDocument.uri));
    }


}
