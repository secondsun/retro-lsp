package dev.secondsun.retrolsp.diagnostic;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonElement;
import dev.secondsun.lsp.Diagnostic;
import dev.secondsun.lsp.DiagnosticSeverity;
import dev.secondsun.lsp.LanguageClient;
import dev.secondsun.lsp.PublishDiagnosticsParams;
import dev.secondsun.lsp.ShowMessageParams;
import dev.secondsun.lsp.ShowMessageRequestParams;
import dev.secondsun.retro.util.DefaultWorkspaceIndex;
import dev.secondsun.retro.util.WorkspaceIndex;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class DiagnosticCoordinatorTest {

  private WorkspaceIndex projectService;
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
    projectService = new DefaultWorkspaceIndex();
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
    projectService.openOrUpdateBuffer(uri, code);
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

  @Test
  public void testIsoEngineSuperFxSgsHasNoDiagnostics() throws Exception {
    var path = java.nio.file.Path.of("../snes-sfx-demo/X-GSU/IsoEngine/SuperFX.sgs");
    if (java.nio.file.Files.exists(path)) {
      String code = java.nio.file.Files.readString(path);
      DiagnosticResult result = coordinator.auditResult(code, path.toUri().toString());
      var errors =
          result.violations().stream()
              .filter(
                  v -> v.severity() == dev.secondsun.retrolsp.diagnostic.DiagnosticSeverity.ERROR)
              .toList();
      assertTrue(errors.isEmpty(), "Expected 0 errors in IsoEngine/SuperFX.sgs, found: " + errors);
    }
  }

  @Test
  public void testValidAnonymousBranchesHaveNoDiagnostics() {
    String code =
        """
        :
            nop
            beq :-
            bne :++
        :
            nop
        :
            nop
        """;
    DiagnosticResult result = coordinator.auditResult(code, "file:///valid_anon.s");
    var errors =
        result.violations().stream()
            .filter(v -> v.severity() == dev.secondsun.retrolsp.diagnostic.DiagnosticSeverity.ERROR)
            .toList();
    assertTrue(
        errors.isEmpty(), "Expected no errors for valid anonymous branches, found: " + errors);
  }

  @Test
  public void testMissingAnonymousBranchesReportDiagnostics() {
    String code =
        """
        bne :++
        beq :-
        """;
    DiagnosticResult result = coordinator.auditResult(code, "file:///missing_anon.s");
    assertFalse(result.valid());
    var errors =
        result.violations().stream()
            .filter(v -> "ANONYMOUS_LABEL_NOT_FOUND".equals(v.rule()))
            .toList();
    assertEquals(2, errors.size(), "Expected 2 ANONYMOUS_LABEL_NOT_FOUND errors, found: " + errors);
    assertEquals("Target anonymous label ':++' does not exist", errors.get(0).message());
    assertEquals("Target anonymous label ':-' does not exist", errors.get(1).message());
  }

  @Test
  public void testTestSMapGridHasNoDiagnostics() throws Exception {
    var path = java.nio.file.Path.of("../snes-sfx-demo/X-GSU/tests/test_map_grid/Test.s");
    if (java.nio.file.Files.exists(path)) {
      String code = java.nio.file.Files.readString(path);
      DiagnosticResult result = coordinator.auditResult(code, path.toUri().toString());
      var errors =
          result.violations().stream()
              .filter(
                  v -> v.severity() == dev.secondsun.retrolsp.diagnostic.DiagnosticSeverity.ERROR)
              .toList();
      assertTrue(errors.isEmpty(), "Expected 0 errors in Test.s, found: " + errors);
    }
  }

  @Test
  public void testTypedAuditFindingCategoriesForPreviouslyMisclassifiedRules() {
    String code =
        """
        function bad_return a
            for 4
                cache
            endfor
            ibt r0, #-1
            with r8
            sub #1
            beq @done
            nop
        @done:
            from r12
            sub #0
            return a, a
        endfunction
        """;
    DiagnosticResult result = coordinator.auditResult(code, "file:///typed_categories.sgs");

    var cacheInLoop =
        result.violations().stream()
            .filter(v -> "CACHE_IN_LOOP".equals(v.rule()))
            .findFirst()
            .orElseThrow();
    assertEquals(DiagnosticCategory.PERFORMANCE, cacheInLoop.category());

    var ibtNeg =
        result.violations().stream()
            .filter(v -> "IBT_NEGATIVE_IMMEDIATE".equals(v.rule()))
            .findFirst()
            .orElseThrow();
    assertEquals(DiagnosticCategory.HARDWARE_INVARIANT, ibtNeg.category());

    var destructiveWith =
        result.violations().stream()
            .filter(v -> "DESTRUCTIVE_WITH_COMPARISON".equals(v.rule()))
            .findFirst()
            .orElseThrow();
    assertEquals(DiagnosticCategory.HARDWARE_INVARIANT, destructiveWith.category());

    var destructiveFromR0 =
        result.violations().stream()
            .filter(v -> "DESTRUCTIVE_FROM_ALU_TO_R0".equals(v.rule()))
            .findFirst()
            .orElseThrow();
    assertEquals(DiagnosticCategory.HARDWARE_INVARIANT, destructiveFromR0.category());

    var returnErr =
        result.violations().stream()
            .filter(v -> "RETURN_STATEMENT_ERROR".equals(v.rule()))
            .findFirst()
            .orElseThrow();
    assertEquals(DiagnosticCategory.SYNTAX, returnErr.category());
  }

  @Test
  public void testStackLeakPreservesOneBasedOriginalLineAfterMacroExpansion() {
    String code =
        """
        function macro_line_test
            for 2
                nop
            endfor
            gsu_stack_push r4
            return
        endfunction
        """;
    DiagnosticResult result = coordinator.auditResult(code, "file:///macro_line_test.sgs");
    var leak =
        result.violations().stream()
            .filter(v -> "STACK_LEAK_ON_RETURN".equals(v.rule()))
            .findFirst()
            .orElseThrow();
    assertEquals(DiagnosticCategory.STACK_INVARIANT, leak.category());
    // 'return' is on 1-based line 6 of the unexpanded source (despite 'for'/'endfor' &
    // 'gsu_stack_push' expanding to many lines)
    assertEquals(6, leak.line());
  }

  @Test
  public void testTransientSnippetAuditDoesNotMutateWorkspaceIndex() {
    assertTrue(projectService.snapshot().effectiveFiles().isEmpty());
    DiagnosticResult res =
        coordinator.auditResult("function tmp\n return\nendfunction\n", "file:///ephemeral.sgs");
    assertTrue(res.valid());
    assertTrue(
        projectService.snapshot().effectiveFiles().isEmpty(),
        "Inline code audit must not mutate persistent WorkspaceIndex");
  }

  @Test
  public void testPure65816DotSDoesNotTriggerFalseSuperFxHardwareOrStackDiagnostics() {
    String cpu65816 =
        """
        .p816
        .a16
        .i16
        .segment "CODE"
        main_cpu:
            lda #$1234
            sbc #$0001
            sta $2100
            rts
        """;
    DiagnosticResult res = coordinator.auditResult(cpu65816, "file:///snes_main.s");
    assertTrue(res.valid(), "Pure 65816 .s file must pass cleanly, got: " + res.violations());
    assertTrue(
        res.violations().isEmpty(), "Expected 0 diagnostics on 65816 .s: " + res.violations());
  }
}
