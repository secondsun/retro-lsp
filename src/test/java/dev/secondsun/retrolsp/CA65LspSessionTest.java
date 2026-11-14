package dev.secondsun.retrolsp;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.secondsun.lsp.LspSession;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Transport-free, in-process JSON-RPC 2.0 integration tests driving {@link CA65LanguageServer}
 * through {@link LspSession}.
 */
public class CA65LspSessionTest {

  @Test
  public void testFullLifecycleOverJsonRpcInProcess(@TempDir Path tempDir) throws Exception {
    Path mathFile = tempDir.resolve("math.i");
    Files.writeString(
        mathFile,
        """
        ; Multiplies input by two
        inline function diskHelper x : res
            with x
            add x
            move res, r0
            call\s
            return res
        endfunction
        """);

    Path mainFile = tempDir.resolve("main.sgs");
    Files.writeString(
        mainFile,
        """
        function savedMainFn
            call diskHelper
            return
        endfunction
        """);

    // Warm up classloading once so timed protocol round-trip measures pure session latency
    LspSession warmup = new LspSession(CA65LanguageServer::new);
    warmup.handleJson(
        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"rootUri\":null}}");
    warmup.handleJson("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"shutdown\"}");
    warmup.handleJson("{\"jsonrpc\":\"2.0\",\"method\":\"exit\"}");

    long startNanos = System.nanoTime();

    LspSession session = new LspSession(CA65LanguageServer::new);
    String rootUri = tempDir.toUri().toString();
    String mainUri = mainFile.toUri().toString();
    String mathUri = mathFile.toUri().toString();

    // 1. initialize + initialized
    JsonObject initResp =
        sendRequest(
            session, 1, "initialize", "{\"rootUri\":\"" + rootUri + "\",\"capabilities\":{}}");
    JsonObject caps = initResp.getAsJsonObject("result").getAsJsonObject("capabilities");
    assertTrue(caps.has("hoverProvider"));
    assertTrue(caps.has("definitionProvider"));
    assertTrue(caps.has("referencesProvider"));
    assertTrue(caps.has("documentLinkProvider"));
    assertTrue(caps.has("completionProvider"));
    assertTrue(caps.has("textDocumentSync"));

    Optional<String> initializedResp =
        session.handleJson("{\"jsonrpc\":\"2.0\",\"method\":\"initialized\",\"params\":{}}");
    assertTrue(initializedResp.isEmpty());
    ((CA65LanguageServer) session.server()).waitForIndexing();
    assertTrue(session.drainOutbound().isEmpty());

    // 2. textDocument/didOpen with broken block -> publishes UNCLOSED_BLOCK diagnostic
    String brokenDirtyText = "function dirtyOnlyFn\\n    call diskHelper\\n";
    session.handleJson(
        "{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/didOpen\",\"params\":{\"textDocument\":{"
            + "\"uri\":\""
            + mainUri
            + "\",\"languageId\":\"retroca65\",\"version\":1,\"text\":\""
            + brokenDirtyText
            + "\"}}}");

    List<String> openOutbound = session.drainOutbound();
    assertEquals(1, openOutbound.size());
    JsonObject diagNotification = JsonParser.parseString(openOutbound.get(0)).getAsJsonObject();
    assertEquals("textDocument/publishDiagnostics", diagNotification.get("method").getAsString());
    JsonArray openDiags = diagNotification.getAsJsonObject("params").getAsJsonArray("diagnostics");
    assertFalse(openDiags.isEmpty());
    assertTrue(openOutbound.get(0).contains("UNCLOSED_BLOCK"));

    // 3. textDocument/didChange closing the function -> clears diagnostics & updates dirty buffer
    String validDirtyText =
        "function dirtyOnlyFn\\n    call diskHelper\\n    return\\nendfunction\\n";
    session.handleJson(
        "{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/didChange\",\"params\":{"
            + "\"textDocument\":{\"uri\":\""
            + mainUri
            + "\",\"version\":2},"
            + "\"contentChanges\":[{\"text\":\""
            + validDirtyText
            + "\"}]}}");

    List<String> changeOutbound = session.drainOutbound();
    assertEquals(1, changeOutbound.size());
    JsonArray changeDiags =
        JsonParser.parseString(changeOutbound.get(0))
            .getAsJsonObject()
            .getAsJsonObject("params")
            .getAsJsonArray("diagnostics");
    assertEquals(0, changeDiags.size(), "Expected clean diagnostics after fixing block");

    // 4. textDocument/hover on 'diskHelper' at line 1, col 11
    JsonObject hoverResp =
        sendRequest(
            session,
            2,
            "textDocument/hover",
            "{\"textDocument\":{\"uri\":\""
                + mainUri
                + "\"},\"position\":{\"line\":1,\"character\":11}}");
    JsonElement firstHoverItem =
        hoverResp.getAsJsonObject("result").getAsJsonArray("contents").get(0);
    String hoverMarkdown =
        firstHoverItem.isJsonObject()
            ? firstHoverItem.getAsJsonObject().get("value").getAsString()
            : firstHoverItem.getAsString();
    assertTrue(hoverMarkdown.contains("Multiplies input by two"));
    assertTrue(hoverMarkdown.contains("inline function diskHelper x : res"));

    // 5. textDocument/definition on 'diskHelper' at line 1, col 11 -> points to math.i line 1
    JsonObject defResp =
        sendRequest(
            session,
            3,
            "textDocument/definition",
            "{\"textDocument\":{\"uri\":\""
                + mainUri
                + "\"},\"position\":{\"line\":1,\"character\":11}}");
    JsonArray defLocations = defResp.getAsJsonArray("result");
    assertEquals(1, defLocations.size());
    assertEquals(
        mathFile.toAbsolutePath().normalize(),
        Path.of(java.net.URI.create(defLocations.get(0).getAsJsonObject().get("uri").getAsString()))
            .toAbsolutePath()
            .normalize());
    assertEquals(
        1,
        defLocations
            .get(0)
            .getAsJsonObject()
            .getAsJsonObject("range")
            .getAsJsonObject("start")
            .get("line")
            .getAsInt());

    // 6. textDocument/references on 'diskHelper' in math.i (line 1, col 18) -> finds dirty main.sgs
    JsonObject refResp =
        sendRequest(
            session,
            4,
            "textDocument/references",
            "{\"textDocument\":{\"uri\":\""
                + mathUri
                + "\"},\"position\":{\"line\":1,\"character\":18},"
                + "\"context\":{\"includeDeclaration\":false}}");
    JsonArray refLocations = refResp.getAsJsonArray("result");
    assertEquals(1, refLocations.size());
    assertEquals(
        mainFile.toAbsolutePath().normalize(),
        Path.of(java.net.URI.create(refLocations.get(0).getAsJsonObject().get("uri").getAsString()))
            .toAbsolutePath()
            .normalize());

    // 7. textDocument/completion in math.i -> offers dirtyOnlyFn while main.sgs buffer is open
    JsonObject compBeforeClose =
        sendRequest(
            session,
            5,
            "textDocument/completion",
            "{\"textDocument\":{\"uri\":\""
                + mathUri
                + "\"},\"position\":{\"line\":5,\"character\":9}}");
    List<String> labelsWhileOpen = extractCompletionLabels(compBeforeClose);
    assertTrue(
        labelsWhileOpen.contains("dirtyOnlyFn"),
        "Dirty unsaved symbol dirtyOnlyFn must be visible while buffer is open");
    assertFalse(
        labelsWhileOpen.contains("savedMainFn"),
        "Overwritten disk symbol savedMainFn must be shadowed by dirty open buffer");

    // 8. textDocument/didClose on main.sgs -> clears diagnostics AND reverts buffer to disk!
    session.handleJson(
        "{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/didClose\",\"params\":{\"textDocument\":{\"uri\":\""
            + mainUri
            + "\"}}}");
    List<String> closeOutbound = session.drainOutbound();
    assertEquals(1, closeOutbound.size());
    assertEquals(
        0,
        JsonParser.parseString(closeOutbound.get(0))
            .getAsJsonObject()
            .getAsJsonObject("params")
            .getAsJsonArray("diagnostics")
            .size());

    JsonObject compAfterClose =
        sendRequest(
            session,
            6,
            "textDocument/completion",
            "{\"textDocument\":{\"uri\":\""
                + mathUri
                + "\"},\"position\":{\"line\":5,\"character\":9}}");
    List<String> labelsAfterClose = extractCompletionLabels(compAfterClose);
    assertFalse(
        labelsAfterClose.contains("dirtyOnlyFn"),
        "Closing unsaved dirty buffer must evict dirtyOnlyFn");
    assertTrue(
        labelsAfterClose.contains("savedMainFn"),
        "Closing unsaved dirty buffer must restore savedMainFn from diskFiles");

    // 8b. workspace/didChangeWatchedFiles (Created -> Deleted) synchronizes diskFiles
    Path watchedNewFile = tempDir.resolve("watched_new.i");
    Files.writeString(watchedNewFile, "watchedDiskSym = $42\n");
    session.handleJson(
        "{\"jsonrpc\":\"2.0\",\"method\":\"workspace/didChangeWatchedFiles\",\"params\":{\"changes\":[{"
            + "\"uri\":\""
            + watchedNewFile.toUri()
            + "\",\"type\":1}]}}");
    List<String> labelsAfterCreate =
        extractCompletionLabels(
            sendRequest(
                session,
                61,
                "textDocument/completion",
                "{\"textDocument\":{\"uri\":\""
                    + mathUri
                    + "\"},\"position\":{\"line\":5,\"character\":9}}"));
    assertTrue(labelsAfterCreate.contains("watchedDiskSym"));

    Files.deleteIfExists(watchedNewFile);
    session.handleJson(
        "{\"jsonrpc\":\"2.0\",\"method\":\"workspace/didChangeWatchedFiles\",\"params\":{\"changes\":[{"
            + "\"uri\":\""
            + watchedNewFile.toUri()
            + "\",\"type\":3}]}}");
    List<String> labelsAfterDelete =
        extractCompletionLabels(
            sendRequest(
                session,
                62,
                "textDocument/completion",
                "{\"textDocument\":{\"uri\":\""
                    + mathUri
                    + "\"},\"position\":{\"line\":5,\"character\":9}}"));
    assertFalse(labelsAfterDelete.contains("watchedDiskSym"));

    // 9. shutdown -> exit
    JsonObject shutdownResp = sendRequest(session, 7, "shutdown", "null");
    assertTrue(shutdownResp.get("result").isJsonNull());
    assertFalse(session.isExited());

    session.handleJson("{\"jsonrpc\":\"2.0\",\"method\":\"exit\"}");
    assertTrue(session.isExited());
    assertEquals(0, session.exitCode());

    long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    assertTrue(
        elapsedMs < 500,
        "Expected in-process LSP session round-trip in well under 500ms (target <50ms), took "
            + elapsedMs
            + "ms");
  }

  @Test
  public void testNullRootUriHandshakeAndExitWithoutShutdown() {
    LspSession session = new LspSession(CA65LanguageServer::new);
    JsonObject initResp =
        sendRequest(
            session, 1, "initialize", "{\"processId\":null,\"rootUri\":null,\"capabilities\":{}}");
    assertFalse(initResp.has("error"));
    assertNotNull(initResp.getAsJsonObject("result").getAsJsonObject("capabilities"));

    session.handleJson("{\"jsonrpc\":\"2.0\",\"method\":\"exit\"}");
    assertTrue(session.isExited());
    assertEquals(1, session.exitCode(), "Exit without prior shutdown must report exit code 1");
  }

  private static JsonObject sendRequest(
      LspSession session, int id, String method, String paramsJson) {
    String payload =
        "{\"jsonrpc\":\"2.0\",\"id\":"
            + id
            + ",\"method\":\""
            + method
            + "\",\"params\":"
            + paramsJson
            + "}";
    Optional<String> rawResp = session.handleJson(payload);
    assertTrue(rawResp.isPresent(), "Expected JSON-RPC response for " + method);
    JsonObject obj = JsonParser.parseString(rawResp.get()).getAsJsonObject();
    assertFalse(obj.has("error"), "Unexpected JSON-RPC error for " + method + ": " + rawResp.get());
    return obj;
  }

  private static List<String> extractCompletionLabels(JsonObject compResp) {
    JsonArray items = compResp.getAsJsonObject("result").getAsJsonArray("items");
    java.util.ArrayList<String> labels = new java.util.ArrayList<>(items.size());
    for (JsonElement el : items) {
      labels.add(el.getAsJsonObject().get("label").getAsString());
    }
    return labels;
  }
}
