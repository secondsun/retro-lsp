package dev.secondsun.retrolsp;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
import dev.secondsun.retro.util.FileService;
import dev.secondsun.retro.util.ProjectService;
import dev.secondsun.retro.util.SymbolService;
import dev.secondsun.retrolsp.feature.CompletionFeature;
import dev.secondsun.retrolsp.feature.DirectiveCompletionFeature;
import dev.secondsun.retrolsp.feature.DocumentLinkFeature;
import dev.secondsun.retrolsp.feature.Feature;
import dev.secondsun.retrolsp.feature.GoToDefinitionLinkFeature;
import dev.secondsun.retrolsp.feature.HoverFeature;
import dev.secondsun.retrolsp.feature.IncludeCompletionFeature;


public class CC65LanguageServer extends LanguageServer {

    private final FileService fileService;
    
    // TODO Actually make use of language client
    // private final LanguageClient client;
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

    public CC65LanguageServer() {

        try {

            this.fileService = new FileService();

            this.symbolService = new SymbolService();
            this.projectService = new ProjectService(fileService, symbolService);
            this.hoverFeature = new HoverFeature();
            this.documentLinkFeature = new DocumentLinkFeature( this.fileService);
            this.gotoDefinitionLinkFeature = new GoToDefinitionLinkFeature( this.symbolService);
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
        try {
            var libSFXRootParam = params.settings.get("retroca65").getAsJsonObject().get("libSFXRoot").getAsString();
            var sourceDirectoryParam = params.settings.get("retroca65").getAsJsonObject().get("sourceDirectory").getAsString();
            this.libSFXRoot = Path.of(this.workspaceRoot).resolve(libSFXRootParam);
            var sourceDirectoryPath = Path.of(this.workspaceRoot).resolve(sourceDirectoryParam);
            
            if (libSFXRoot.toFile().exists()) {
                projectService.includeDir(libSFXRoot.toUri());
            }
            if (sourceDirectoryPath.toFile().exists()) {
                projectService.includeDir(sourceDirectoryPath.toUri());
            }

        } catch (Exception ignore) {
            // ignore because I don't care about if libSFX root is setup right
        }
    };

    public void didChangeWatchedFiles(dev.secondsun.lsp.DidChangeWatchedFilesParams params) {
        for (var change : params.changes) {
            switch (change.type) {
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
                ((CompletionFeature) feature2)
                        .canComplete(params, file))
                .findFirst();
        if (feature.isEmpty()) {
            return Optional.empty();
        } else {
            return ((CompletionFeature) feature.get()).handle(params, file);
        }

    }

    @Override
    public Optional<Hover> hover(TextDocumentPositionParams params) {
        return hoverFeature.handle(params, projectService.getFileContents(params.textDocument.uri));

    }

    @Override
    public List<DocumentLink> documentLink(DocumentLinkParams params) {
        
        return documentLinkFeature.handle(params, projectService.getFileContents(params.textDocument.uri))
                .orElse(List.of());
    }

    @Override
    public Optional<List<Location>> gotoDefinition(TextDocumentPositionParams params) {
        LOG.info(new Gson().toJson(params));
        return gotoDefinitionLinkFeature.handle(params, projectService.getFileContents(params.textDocument.uri));
    }

}
