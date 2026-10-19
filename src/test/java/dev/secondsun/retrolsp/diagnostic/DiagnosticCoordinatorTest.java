package dev.secondsun.retrolsp.diagnostic;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonElement;
import dev.secondsun.lsp.Diagnostic;
import dev.secondsun.lsp.DiagnosticSeverity;
import dev.secondsun.lsp.LanguageClient;
import dev.secondsun.lsp.PublishDiagnosticsParams;
import dev.secondsun.lsp.ShowMessageParams;
import dev.secondsun.lsp.ShowMessageRequestParams;
import dev.secondsun.retro.util.FileService;
import dev.secondsun.retro.util.ProjectService;
import dev.secondsun.retro.util.SymbolService;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class DiagnosticCoordinatorTest {

  private ProjectService projectService;
  private TestLanguageClient client;
  private DiagnosticCoordinator coordinator;

  private static class TestLanguageClient implements LanguageClient {
    final List<PublishDiagnosticsParams> published = new ArrayList<>();

    @Override
    public void publishDiagnostics(PublishDiagnosticsParams params) {
      published.add(params);
    }

    @Override
    public void showMessage(ShowMessageParams params) {}

    @Override
    public void registerCapability(String method, JsonElement options) {}

    @Override
    public int showMessageRequest(ShowMessageRequestParams requestParams) {
      return 0;
    }

    @Override
    public void customNotification(String method, JsonElement params) {}
  }

  @BeforeEach
  public void setUp() {
    projectService = new ProjectService(new FileService(), new SymbolService());
    client = new TestLanguageClient();
    coordinator = new DiagnosticCoordinator(projectService, client);
  }

  @Test
  public void testUnbalancedFunctionBlockDiagnostic() {
    String code =
        """
        function foo
            nop
        """;
    DiagnosticResult result = coordinator.auditResult(code, null);
    assertFalse(result.valid());
    var v = result.violations().stream().filter(r -> "UNCLOSED_BLOCK".equals(r.rule())).findFirst();
    assertTrue(v.isPresent(), "Expected UNCLOSED_BLOCK violation");
    assertEquals(1, v.get().line());
    assertEquals(DiagnosticCategory.SYNTAX, v.get().category());
    assertEquals(dev.secondsun.retrolsp.diagnostic.DiagnosticSeverity.ERROR, v.get().severity());
    assertTrue(v.get().message().contains("endfunction"));
  }

  @Test
  public void testUnbalancedEndFunctionDiagnostic() {
    String code =
        """
        nop
        endfunction
        """;
    DiagnosticResult result = coordinator.auditResult(code, null);
    assertFalse(result.valid());
    var v =
        result.violations().stream()
            .filter(r -> "ORPHAN_BLOCK_CLOSER".equals(r.rule()))
            .findFirst();
    assertTrue(v.isPresent(), "Expected ORPHAN_BLOCK_CLOSER violation");
    assertEquals(2, v.get().line());
    assertTrue(v.get().message().contains("endfunction"));
  }

  @Test
  public void testInstructionArityDiagnostics() {
    String code =
        """
        nop r1
        dec
        with
        """;
    DiagnosticResult result = coordinator.auditResult(code, null);
    assertFalse(result.valid());
    var arityErrors =
        result.violations().stream()
            .filter(r -> "EXTRA_OPERAND".equals(r.rule()) || "MISSING_OPERAND".equals(r.rule()))
            .toList();
    assertEquals(3, arityErrors.size());
    assertEquals(1, arityErrors.get(0).line());
    assertEquals(2, arityErrors.get(1).line());
    assertEquals(3, arityErrors.get(2).line());
  }

  @Test
  public void testDuplicateSymbolDiagnostic() {
    String code =
        """
        loop_start:
            nop
        loop_start:
            nop
        """;
    DiagnosticResult result = coordinator.auditResult(code, null);
    assertFalse(result.valid());
    var v =
        result.violations().stream().filter(r -> "DUPLICATE_SYMBOL".equals(r.rule())).findFirst();
    assertTrue(v.isPresent());
    assertEquals(3, v.get().line());
    assertEquals(DiagnosticCategory.SYNTAX, v.get().category());
    assertTrue(v.get().message().contains("loop_start"));
  }

  @Test
  public void testHardwareInvariantsDiagnostics() {
    String code =
        """
        from r1
        merge
        to r2
        ldw $2001
        stop
        """;
    DiagnosticResult result = coordinator.auditResult(code, null);
    assertFalse(result.valid());

    assertTrue(result.violations().stream().anyMatch(r -> "NO_HARDWARE_MERGE".equals(r.rule())));
    assertTrue(
        result.violations().stream().anyMatch(r -> "WORD_ALIGNMENT_LDW_STW".equals(r.rule())));
    assertTrue(
        result.violations().stream().anyMatch(r -> "PIPELINE_SETTLING_8_NOP".equals(r.rule())));
  }

  @Test
  public void testStackLeakOnReturnDiagnostic() {
    String code =
        """
        function leak_test
            gsu_stack_push r4
            return
        endfunction
        """;
    DiagnosticResult result = coordinator.auditResult(code, "file:///leak_test.s");
    assertFalse(result.valid());
    var v =
        result.violations().stream()
            .filter(r -> "STACK_LEAK_ON_RETURN".equals(r.rule()))
            .findFirst();
    assertTrue(v.isPresent(), "Expected STACK_LEAK_ON_RETURN violation");
    assertEquals(DiagnosticCategory.STACK_INVARIANT, v.get().category());
    assertTrue(v.get().message().contains("Δ_SP = 2 bytes"));
  }

  @Test
  public void testPublishDiagnosticsToLanguageClient() {
    URI uri = URI.create("file:///test_diagnostics.s");
    String code =
        """
        function unclosed
            nop r1
        """;
    projectService.updateFileContents(uri, code);
    coordinator.publishDiagnostics(uri);

    assertEquals(1, client.published.size());
    PublishDiagnosticsParams params = client.published.get(0);
    assertEquals(uri, params.uri);
    assertFalse(params.diagnostics.isEmpty());

    // Check LSP range mapping: line 1 (function) -> 0-based line 0
    Diagnostic unclosedDiag =
        params.diagnostics.stream()
            .filter(d -> "UNCLOSED_BLOCK".equals(d.code))
            .findFirst()
            .orElseThrow();
    assertEquals(0, unclosedDiag.range.start.line);
    assertEquals(DiagnosticSeverity.Error, unclosedDiag.severity);
    assertEquals("retroca65", unclosedDiag.source);

    // Check LSP range mapping: line 2 (nop r1) -> 0-based line 1
    Diagnostic arityDiag =
        params.diagnostics.stream()
            .filter(d -> "EXTRA_OPERAND".equals(d.code))
            .findFirst()
            .orElseThrow();
    assertEquals(1, arityDiag.range.start.line);
    assertEquals(DiagnosticSeverity.Error, arityDiag.severity);
    assertEquals("retroca65", arityDiag.source);
  }

  @Test
  public void testClearDiagnostics() {
    URI uri = URI.create("file:///clean.s");
    coordinator.clearDiagnostics(uri);

    assertEquals(1, client.published.size());
    PublishDiagnosticsParams params = client.published.get(0);
    assertEquals(uri, params.uri);
    assertTrue(params.diagnostics.isEmpty());
  }

  @Test
  public void testServerEndToEndTextDocumentSyncAndDiagnostics() {
    TestLanguageClient serverClient = new TestLanguageClient();
    dev.secondsun.retrolsp.CA65LanguageServer server =
        new dev.secondsun.retrolsp.CA65LanguageServer(serverClient);

    // 1. Verify textDocumentSync capability advertised in InitializeResult
    dev.secondsun.lsp.InitializeParams initParams = new dev.secondsun.lsp.InitializeParams();
    dev.secondsun.lsp.InitializeResult initResult = server.initialize(initParams);
    assertNotNull(initResult.capabilities);
    assertTrue(initResult.capabilities.has("textDocumentSync"));
    var syncObj = initResult.capabilities.getAsJsonObject("textDocumentSync");
    assertTrue(syncObj.get("openClose").getAsBoolean());
    assertEquals(1, syncObj.get("change").getAsInt());

    // 2. Open file missing endfunction -> should publish UNCLOSED_BLOCK error
    URI docUri = URI.create("file:///test_unclosed.s");
    dev.secondsun.lsp.DidOpenTextDocumentParams openParams =
        new dev.secondsun.lsp.DidOpenTextDocumentParams();
    openParams.textDocument = new dev.secondsun.lsp.TextDocumentItem();
    openParams.textDocument.uri = docUri;
    openParams.textDocument.languageId = "retroca65";
    openParams.textDocument.text =
        """
        function my_func
            nop
        """;
    server.didOpenTextDocument(openParams);

    assertEquals(1, serverClient.published.size());
    PublishDiagnosticsParams openDiag = serverClient.published.get(0);
    assertEquals(docUri, openDiag.uri);
    assertTrue(
        openDiag.diagnostics.stream().anyMatch(d -> "UNCLOSED_BLOCK".equals(d.code)),
        "Expected UNCLOSED_BLOCK diagnostic on open");

    // 3. Edit file to add endfunction -> should clear errors live
    dev.secondsun.lsp.DidChangeTextDocumentParams changeParams =
        new dev.secondsun.lsp.DidChangeTextDocumentParams();
    changeParams.textDocument = new dev.secondsun.lsp.VersionedTextDocumentIdentifier();
    changeParams.textDocument.uri = docUri;
    dev.secondsun.lsp.TextDocumentContentChangeEvent changeEvent =
        new dev.secondsun.lsp.TextDocumentContentChangeEvent();
    changeEvent.text =
        """
        function my_func
            nop
        endfunction
        """;
    changeParams.contentChanges = List.of(changeEvent);
    server.didChangeTextDocument(changeParams);

    assertEquals(2, serverClient.published.size());
    PublishDiagnosticsParams changeDiag = serverClient.published.get(1);
    assertEquals(docUri, changeDiag.uri);
    assertTrue(
        changeDiag.diagnostics.isEmpty(), "Expected all diagnostics cleared after closing block");
  }

  @Test
  public void testGsuSqrtFileHasNoDiagnostics() throws Exception {
    var path = java.nio.file.Path.of("../snes-sfx-demo/X-GSU/gsu_maths/gsu_sqrt.i");
    if (java.nio.file.Files.exists(path)) {
      String code = java.nio.file.Files.readString(path);
      DiagnosticResult result = coordinator.auditResult(code, path.toUri().toString());
      var errors =
          result.violations().stream()
              .filter(
                  v -> v.severity() == dev.secondsun.retrolsp.diagnostic.DiagnosticSeverity.ERROR)
              .toList();
      assertTrue(errors.isEmpty(), "Expected 0 errors in gsu_sqrt.i, found: " + errors);
    }
  }
}
