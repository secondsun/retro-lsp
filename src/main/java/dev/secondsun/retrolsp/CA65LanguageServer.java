package dev.secondsun.retrolsp;

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
import dev.secondsun.retrolsp.feature.SymbolCompletionFeature;
import dev.secondsun.sfxoptimizer.graphnode.*;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Language Server implementation for CA65 assembly and libSFX projects. Coordinates language server
 * capabilities and delegates requests to registered features.
 */
public class CA65LanguageServer extends LanguageServer {

  private final FileService fileService;

  // TODO Actually make use of language client
  // private final LanguageClient client;
  private URI workspaceRoot;
  private CompletableFuture<Void> indexingFuture = CompletableFuture.completedFuture(null);

  private final HoverFeature hoverFeature;
  private DocumentLinkFeature documentLinkFeature;
  private final GoToDefinitionLinkFeature gotoDefinitionLinkFeature;
  private final List<Feature<?, ?>> features = new ArrayList<>();
  private IncludeCompletionFeature includeCompletionFeature;
  private DirectiveCompletionFeature commandCompletionFeature;
  private SymbolCompletionFeature symbolCompletionFeature;
  private Path libSFXRoot;
  private SymbolService symbolService;
  private ProjectService projectService;

  private CodeGraph graph;

  private static final Logger LOG = Logger.getLogger(CA65LanguageServer.class.getName());

  /**
   * Constructs a new {@code CA65LanguageServer} instance and initializes default services and LSP
   * features.
   */
  public CA65LanguageServer() {

    try {

      this.fileService = new FileService();

      this.symbolService = new SymbolService();
      this.projectService = new ProjectService(fileService, symbolService);
      this.hoverFeature = new HoverFeature(fileService, symbolService, projectService);
      this.documentLinkFeature = new DocumentLinkFeature(this.fileService);
      this.gotoDefinitionLinkFeature = new GoToDefinitionLinkFeature(this.symbolService);
      this.includeCompletionFeature = new IncludeCompletionFeature();
      this.commandCompletionFeature = new DirectiveCompletionFeature();
      this.symbolCompletionFeature = new SymbolCompletionFeature(this.symbolService);

      features.add(includeCompletionFeature);
      features.add(gotoDefinitionLinkFeature);
      features.add(hoverFeature);
      features.add(documentLinkFeature);
      features.add(commandCompletionFeature);
      features.add(symbolCompletionFeature);

    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public InitializeResult initialize(InitializeParams params) {
    this.workspaceRoot = params.rootUri;
    if (this.workspaceRoot == null
        && params.workspaceFolders != null
        && !params.workspaceFolders.isEmpty()) {
      this.workspaceRoot = params.workspaceFolders.get(0).uri;
    }

    if (this.workspaceRoot != null) {
      this.indexingFuture =
          CompletableFuture.runAsync(
              () -> {
                try {
                  this.projectService.includeDir(this.workspaceRoot);
                } catch (Exception e) {
                  LOG.log(Level.WARNING, "Failed to index workspace: " + e.getMessage(), e);
                }
              });
    }

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

  @Override
  public void didChangeConfiguration(dev.secondsun.lsp.DidChangeConfigurationParams params) {
    if (this.workspaceRoot == null || params == null || params.settings == null) {
      return;
    }
    try {
      var retroca65Config = params.settings.get("retroca65");
      if (retroca65Config != null && retroca65Config.isJsonObject()) {
        var obj = retroca65Config.getAsJsonObject();
        if (obj.has("libSFXRoot") && !obj.get("libSFXRoot").isJsonNull()) {
          var libSFXRootParam = obj.get("libSFXRoot").getAsString();
          this.libSFXRoot = Path.of(this.workspaceRoot).resolve(libSFXRootParam);
          if (libSFXRoot.toFile().exists()) {
            Thread.ofVirtual().start(() -> projectService.includeDir(libSFXRoot.toUri()));
          }
        }
        if (obj.has("sourceDirectory") && !obj.get("sourceDirectory").isJsonNull()) {
          var sourceDirectoryParam = obj.get("sourceDirectory").getAsString();
          var sourceDirectoryPath = Path.of(this.workspaceRoot).resolve(sourceDirectoryParam);
          if (sourceDirectoryPath.toFile().exists()) {
            Thread.ofVirtual().start(() -> projectService.includeDir(sourceDirectoryPath.toUri()));
          }
        }
      }
    } catch (Exception ignore) {
      // ignore
    }
  }

  @Override
  public void didOpenTextDocument(dev.secondsun.lsp.DidOpenTextDocumentParams params) {
    if (params != null && params.textDocument != null && params.textDocument.uri != null) {
      if (params.textDocument.text != null && !params.textDocument.text.isEmpty()) {
        projectService.updateFileContents(params.textDocument.uri, params.textDocument.text);
      } else {
        projectService.refreshFileContents(params.textDocument.uri);
      }
    }
  }

  @Override
  public void didChangeTextDocument(dev.secondsun.lsp.DidChangeTextDocumentParams params) {
    if (params != null && params.textDocument != null && params.textDocument.uri != null) {
      if (params.contentChanges != null && !params.contentChanges.isEmpty()) {
        var lastChange = params.contentChanges.get(params.contentChanges.size() - 1);
        if (lastChange != null && lastChange.text != null) {
          projectService.updateFileContents(params.textDocument.uri, lastChange.text);
          return;
        }
      }
      projectService.refreshFileContents(params.textDocument.uri);
    }
  }

  @Override
  public void didSaveTextDocument(dev.secondsun.lsp.DidSaveTextDocumentParams params) {
    if (params != null && params.textDocument != null && params.textDocument.uri != null) {
      projectService.refreshFileContents(params.textDocument.uri);
    }
  }

  @Override
  public void didCloseTextDocument(dev.secondsun.lsp.DidCloseTextDocumentParams params) {
    // no-op
  }

  @Override
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
  }

  public void waitForIndexing() {
    try {
      if (indexingFuture != null && !indexingFuture.isDone()) {
        indexingFuture.get(10, TimeUnit.SECONDS);
      }
    } catch (Exception e) {
      LOG.log(Level.FINE, "Indexing wait finished or timed out: " + e.getMessage());
    }
  }

  @Override
  public Optional<CompletionList> completion(TextDocumentPositionParams params) {
    waitForIndexing();
    var file = projectService.getFileContents(params.textDocument.uri);
    List<dev.secondsun.lsp.CompletionItem> allItems = new ArrayList<>();
    for (var feature2 : features) {
      if (feature2 instanceof CompletionFeature cf && cf.canComplete(params, file)) {
        var res = cf.handle(params, file);
        if (res.isPresent() && res.get().items != null) {
          allItems.addAll(res.get().items);
        }
      }
    }
    if (allItems.isEmpty()) {
      return Optional.empty();
    }
    var list = new CompletionList();
    list.items = allItems;
    return Optional.of(list);
  }

  @Override
  public Optional<Hover> hover(TextDocumentPositionParams params) {
    waitForIndexing();
    return hoverFeature.handle(params, projectService.getFileContents(params.textDocument.uri));
  }

  @Override
  public List<DocumentLink> documentLink(DocumentLinkParams params) {
    waitForIndexing();
    return documentLinkFeature
        .handle(params, projectService.getFileContents(params.textDocument.uri))
        .orElse(List.of());
  }

  @Override
  public Optional<List<Location>> gotoDefinition(TextDocumentPositionParams params) {
    waitForIndexing();
    return gotoDefinitionLinkFeature.handle(
        params, projectService.getFileContents(params.textDocument.uri));
  }
}
