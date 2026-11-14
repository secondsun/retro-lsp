package dev.secondsun.retrolsp;

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
import dev.secondsun.lsp.ReferenceParams;
import dev.secondsun.lsp.TextDocumentPositionParams;
import dev.secondsun.retro.util.CA65Scanner;
import dev.secondsun.retro.util.DefaultWorkspaceIndex;
import dev.secondsun.retro.util.DiskEventKind;
import dev.secondsun.retro.util.WorkspaceIndex;
import dev.secondsun.retro.util.WorkspaceSnapshot;
import dev.secondsun.retro.util.vo.TokenizedFile;
import dev.secondsun.retrolsp.diagnostic.DiagnosticCoordinator;
import dev.secondsun.retrolsp.feature.CompletionFeature;
import dev.secondsun.retrolsp.feature.DirectiveCompletionFeature;
import dev.secondsun.retrolsp.feature.DocumentLinkFeature;
import dev.secondsun.retrolsp.feature.Feature;
import dev.secondsun.retrolsp.feature.FindReferencesFeature;
import dev.secondsun.retrolsp.feature.GoToDefinitionLinkFeature;
import dev.secondsun.retrolsp.feature.HoverFeature;
import dev.secondsun.retrolsp.feature.IncludeCompletionFeature;
import dev.secondsun.retrolsp.feature.SymbolCompletionFeature;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Language Server implementation for CA65 assembly, libSFX, and X-GSU SuperFX projects. Coordinates
 * language server capabilities, open-buffer synchronization, and immutable {@link
 * WorkspaceSnapshot} queries across registered LSP features.
 */
public class CA65LanguageServer extends LanguageServer {

  private static final Logger LOG = Logger.getLogger(CA65LanguageServer.class.getName());

  private final DelegatingWorkspaceIndex workspaceIndex;
  private final LanguageClient client;
  private final DiagnosticCoordinator diagnosticCoordinator;
  private URI workspaceRoot;
  private Path libSFXRoot;
  private CompletableFuture<Void> indexingFuture = CompletableFuture.completedFuture(null);

  private final HoverFeature hoverFeature;
  private final DocumentLinkFeature documentLinkFeature;
  private final GoToDefinitionLinkFeature gotoDefinitionLinkFeature;
  private final FindReferencesFeature findReferencesFeature;
  private final IncludeCompletionFeature includeCompletionFeature;
  private final DirectiveCompletionFeature commandCompletionFeature;
  private final SymbolCompletionFeature symbolCompletionFeature;
  private final List<Feature<?, ?>> features = new ArrayList<>();

  /** Constructs a new {@code CA65LanguageServer} instance with a {@code null} client. */
  public CA65LanguageServer() {
    this(null);
  }

  /**
   * Constructs a new {@code CA65LanguageServer} instance with the given {@link LanguageClient}.
   *
   * @param client LSP client for publishing notifications and diagnostics
   */
  public CA65LanguageServer(LanguageClient client) {
    this.client = client;
    this.workspaceIndex = new DelegatingWorkspaceIndex(new DefaultWorkspaceIndex());
    this.diagnosticCoordinator = new DiagnosticCoordinator(this.workspaceIndex, this.client);
    this.hoverFeature = new HoverFeature(this.workspaceIndex);
    this.documentLinkFeature = new DocumentLinkFeature(this.workspaceIndex);
    this.gotoDefinitionLinkFeature = new GoToDefinitionLinkFeature(this.workspaceIndex);
    this.findReferencesFeature = new FindReferencesFeature(this.workspaceIndex);
    this.includeCompletionFeature = new IncludeCompletionFeature(this.workspaceIndex);
    this.commandCompletionFeature = new DirectiveCompletionFeature();
    this.symbolCompletionFeature = new SymbolCompletionFeature(this.workspaceIndex);

    features.add(includeCompletionFeature);
    features.add(gotoDefinitionLinkFeature);
    features.add(findReferencesFeature);
    features.add(hoverFeature);
    features.add(documentLinkFeature);
    features.add(commandCompletionFeature);
    features.add(symbolCompletionFeature);
  }

  /**
   * Returns the {@link WorkspaceIndex} backing this language server.
   *
   * @return workspace index
   */
  public WorkspaceIndex getWorkspaceIndex() {
    return workspaceIndex;
  }

  /**
   * Returns the diagnostic coordinator managing syntax, hardware, performance, and stack audits.
   *
   * @return the diagnostic coordinator
   */
  public DiagnosticCoordinator getDiagnosticCoordinator() {
    return diagnosticCoordinator;
  }

  /**
   * Returns the connected LSP language client.
   *
   * @return the language client or {@code null} if not connected
   */
  public LanguageClient getClient() {
    return client;
  }

  @Override
  public InitializeResult initialize(InitializeParams params) {
    if (params != null) {
      this.workspaceRoot = params.rootUri;
      if (this.workspaceRoot == null
          && params.workspaceFolders != null
          && !params.workspaceFolders.isEmpty()
          && params.workspaceFolders.get(0) != null) {
        this.workspaceRoot = params.workspaceFolders.get(0).uri;
      }
    }

    if (this.workspaceRoot != null) {
      if ("file".equalsIgnoreCase(this.workspaceRoot.getScheme())) {
        try {
          Path rootPath = Path.of(this.workspaceRoot).toAbsolutePath().normalize();
          this.workspaceIndex.setDelegate(new DefaultWorkspaceIndex(rootPath));
        } catch (Exception e) {
          LOG.log(Level.WARNING, "Failed to bind workspace root: " + e.getMessage(), e);
        }
      }
      final URI rootUri = this.workspaceRoot;
      this.indexingFuture =
          CompletableFuture.runAsync(
              () -> {
                try {
                  this.workspaceIndex.includeDir(rootUri);
                } catch (Exception e) {
                  LOG.log(Level.WARNING, "Failed to index workspace: " + e.getMessage(), e);
                }
              });
    }

    var initializeData = new JsonObject();
    for (Feature<?, ?> feature : features) {
      feature.initialize(initializeData);
    }

    var textDocumentSync = new JsonObject();
    textDocumentSync.addProperty("openClose", true);
    textDocumentSync.addProperty("change", 1); // 1 = Full sync
    var saveOptions = new JsonObject();
    saveOptions.addProperty("includeText", true);
    textDocumentSync.add("save", saveOptions);
    initializeData.add("textDocumentSync", textDocumentSync);

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
          if (libSFXRoot.toFile().exists()
              && workspaceIndex.isInsideWorkspace(libSFXRoot.toUri())) {
            Thread.ofVirtual().start(() -> workspaceIndex.includeDir(libSFXRoot.toUri()));
          }
        }
        if (obj.has("sourceDirectory") && !obj.get("sourceDirectory").isJsonNull()) {
          var sourceDirectoryParam = obj.get("sourceDirectory").getAsString();
          var sourceDirectoryPath = Path.of(this.workspaceRoot).resolve(sourceDirectoryParam);
          if (sourceDirectoryPath.toFile().exists()
              && workspaceIndex.isInsideWorkspace(sourceDirectoryPath.toUri())) {
            Thread.ofVirtual().start(() -> workspaceIndex.includeDir(sourceDirectoryPath.toUri()));
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
      URI uri = params.textDocument.uri;
      if (workspaceIndex.isInsideWorkspace(uri)) {
        if (params.textDocument.text != null) {
          workspaceIndex.openOrUpdateBuffer(uri, params.textDocument.text);
        } else {
          workspaceIndex.notifyDiskEvent(uri, DiskEventKind.MODIFIED);
        }
      }
      diagnosticCoordinator.publishDiagnostics(uri);
    }
  }

  @Override
  public void didChangeTextDocument(dev.secondsun.lsp.DidChangeTextDocumentParams params) {
    if (params != null && params.textDocument != null && params.textDocument.uri != null) {
      URI uri = params.textDocument.uri;
      if (workspaceIndex.isInsideWorkspace(uri)) {
        if (params.contentChanges != null && !params.contentChanges.isEmpty()) {
          var lastChange = params.contentChanges.get(params.contentChanges.size() - 1);
          if (lastChange != null && lastChange.text != null) {
            workspaceIndex.openOrUpdateBuffer(uri, lastChange.text);
            diagnosticCoordinator.publishDiagnostics(uri);
            return;
          }
        }
        workspaceIndex.notifyDiskEvent(uri, DiskEventKind.MODIFIED);
      }
      diagnosticCoordinator.publishDiagnostics(uri);
    }
  }

  @Override
  public void didSaveTextDocument(dev.secondsun.lsp.DidSaveTextDocumentParams params) {
    if (params != null && params.textDocument != null && params.textDocument.uri != null) {
      URI uri = params.textDocument.uri;
      if (workspaceIndex.isInsideWorkspace(uri)) {
        if (params.text != null) {
          workspaceIndex.openOrUpdateBuffer(uri, params.text);
        }
        workspaceIndex.notifyDiskEvent(uri, DiskEventKind.MODIFIED);
      }
      diagnosticCoordinator.publishDiagnostics(uri);
    }
  }

  @Override
  public void didCloseTextDocument(dev.secondsun.lsp.DidCloseTextDocumentParams params) {
    if (params != null && params.textDocument != null && params.textDocument.uri != null) {
      URI uri = params.textDocument.uri;
      if (workspaceIndex.isInsideWorkspace(uri)) {
        workspaceIndex.closeBuffer(uri);
      }
      diagnosticCoordinator.clearDiagnostics(uri);
    }
  }

  @Override
  public void didChangeWatchedFiles(dev.secondsun.lsp.DidChangeWatchedFilesParams params) {
    if (params == null || params.changes == null) {
      return;
    }
    for (var change : params.changes) {
      if (change == null || change.uri == null) {
        continue;
      }
      switch (change.type) {
        case FileChangeType.Created ->
            workspaceIndex.notifyDiskEvent(change.uri, DiskEventKind.CREATED);
        case FileChangeType.Changed ->
            workspaceIndex.notifyDiskEvent(change.uri, DiskEventKind.MODIFIED);
        case FileChangeType.Deleted ->
            workspaceIndex.notifyDiskEvent(change.uri, DiskEventKind.DELETED);
        default -> {}
      }
    }
  }

  /** Waits up to 10 seconds for initial workspace symbol indexing to complete. */
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
    if (params == null || params.textDocument == null || params.textDocument.uri == null) {
      return Optional.empty();
    }
    var file = readDocumentContents(params.textDocument.uri);
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
    if (params == null || params.textDocument == null || params.textDocument.uri == null) {
      return Optional.of(new Hover(new ArrayList<>()));
    }
    return hoverFeature.handle(params, readDocumentContents(params.textDocument.uri));
  }

  @Override
  public List<DocumentLink> documentLink(DocumentLinkParams params) {
    waitForIndexing();
    if (params == null || params.textDocument == null || params.textDocument.uri == null) {
      return List.of();
    }
    return documentLinkFeature
        .handle(params, readDocumentContents(params.textDocument.uri))
        .orElse(List.of());
  }

  @Override
  public Optional<List<Location>> gotoDefinition(TextDocumentPositionParams params) {
    waitForIndexing();
    if (params == null || params.textDocument == null || params.textDocument.uri == null) {
      return Optional.empty();
    }
    return gotoDefinitionLinkFeature.handle(params, readDocumentContents(params.textDocument.uri));
  }

  @Override
  public Optional<List<Location>> findReferences(ReferenceParams params) {
    waitForIndexing();
    if (params == null || params.textDocument == null || params.textDocument.uri == null) {
      return Optional.empty();
    }
    return findReferencesFeature.handle(params, readDocumentContents(params.textDocument.uri));
  }

  private TokenizedFile readDocumentContents(URI uri) {
    if (uri == null) {
      return TokenizedFile.EMPTY;
    }
    if (workspaceIndex.isInsideWorkspace(uri)) {
      return workspaceIndex.getFileContents(uri);
    }
    if ("file".equalsIgnoreCase(uri.getScheme())) {
      try {
        Path p = Path.of(uri).toAbsolutePath().normalize();
        if (Files.isRegularFile(p)) {
          return new CA65Scanner().tokenize(Files.readString(p), uri);
        }
      } catch (Exception e) {
        LOG.log(Level.FINE, "Could not read external document " + uri, e);
      }
    }
    return TokenizedFile.EMPTY;
  }

  /**
   * Thread-safe delegating {@link WorkspaceIndex} allowing {@link CA65LanguageServer#initialize} to
   * rebind the workspace root once the client sends {@link InitializeParams} while keeping a single
   * stable reference across all LSP features.
   */
  private static final class DelegatingWorkspaceIndex implements WorkspaceIndex {
    private final AtomicReference<WorkspaceIndex> delegate;
    private volatile Consumer<Path> directoryListener;

    DelegatingWorkspaceIndex(WorkspaceIndex initial) {
      this.delegate = new AtomicReference<>(Objects.requireNonNull(initial));
    }

    void setDelegate(WorkspaceIndex next) {
      Objects.requireNonNull(next);
      Consumer<Path> listener = this.directoryListener;
      if (listener != null) {
        next.setDirectoryIndexedListener(listener);
      }
      this.delegate.set(next);
    }

    @Override
    public WorkspaceSnapshot snapshot() {
      return delegate.get().snapshot();
    }

    @Override
    public WorkspaceSnapshot snapshotFor(URI nullableUri, String inlineCode) {
      return delegate.get().snapshotFor(nullableUri, inlineCode);
    }

    @Override
    public TokenizedFile resolveFile(URI uri, String optionalCode) {
      return delegate.get().resolveFile(uri, optionalCode);
    }

    @Override
    public TokenizedFile getFileContents(URI uri) {
      return delegate.get().getFileContents(uri);
    }

    @Override
    public void ensureIndexed(URI uri) {
      delegate.get().ensureIndexed(uri);
    }

    @Override
    public void openOrUpdateBuffer(URI uri, String text) {
      delegate.get().openOrUpdateBuffer(uri, text);
    }

    @Override
    public void closeBuffer(URI uri) {
      delegate.get().closeBuffer(uri);
    }

    @Override
    public void notifyDiskEvent(URI uri, DiskEventKind kind) {
      delegate.get().notifyDiskEvent(uri, kind);
    }

    @Override
    public void removeFile(URI uri) {
      delegate.get().removeFile(uri);
    }

    @Override
    public void includeDir(URI directory) {
      delegate.get().includeDir(directory);
    }

    @Override
    public void validateUriWithinWorkspace(URI uri) {
      delegate.get().validateUriWithinWorkspace(uri);
    }

    @Override
    public boolean isInsideWorkspace(URI uri) {
      return delegate.get().isInsideWorkspace(uri);
    }

    @Override
    public void setDirectoryIndexedListener(Consumer<Path> listener) {
      this.directoryListener = listener;
      delegate.get().setDirectoryIndexedListener(listener);
    }

    @Override
    public Path getWorkspaceRoot() {
      return delegate.get().getWorkspaceRoot();
    }
  }
}
