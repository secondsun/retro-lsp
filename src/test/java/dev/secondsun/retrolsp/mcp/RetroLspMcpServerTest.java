package dev.secondsun.retrolsp.mcp;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class RetroLspMcpServerTest {

  private RetroLspMcpServer server;
  private ObjectMapper objectMapper;

  @BeforeEach
  public void setUp() {
    server = new RetroLspMcpServer();
    objectMapper = new ObjectMapper();
  }

  @AfterEach
  public void tearDown() {
    if (server != null) {
      server.close();
    }
  }

  @Test
  public void testAuditInvariantsToolViaServer() throws Exception {
    String code =
        """
        nop
        nop
        nop
        nop
        nop
        nop
        nop
        nop
        stop
        """;
    CallToolResult result = server.executeTool("sfx_audit_invariants", Map.of("code", code));
    assertNotNull(result);
    assertFalse(result.isError());
    assertFalse(result.content().isEmpty());

    TextContent content = (TextContent) result.content().get(0);
    JsonNode json = objectMapper.readTree(content.text());
    assertTrue(json.get("valid").asBoolean());
    assertEquals(0, json.get("violations").size());
  }

  @Test
  public void testAuditDiagnosticsToolViaServer() throws Exception {
    String code =
        """
        function unclosed
            nop
        """;
    CallToolResult result = server.executeTool("sfx_audit_diagnostics", Map.of("code", code));
    assertNotNull(result);
    assertFalse(result.isError());

    TextContent content = (TextContent) result.content().get(0);
    JsonNode json = objectMapper.readTree(content.text());
    assertFalse(json.get("valid").asBoolean());
    assertTrue(json.get("violations").size() > 0);
    JsonNode firstViolation = json.get("violations").get(0);
    assertEquals("UNCLOSED_BLOCK", firstViolation.get("rule").asText());
    assertEquals("SYNTAX", firstViolation.get("category").asText());
    assertEquals("ERROR", firstViolation.get("severity").asText());
  }

  @Test
  public void testExpandMacroToolViaServer() throws Exception {
    String code =
        """
        gsu_stack_push r1
        gsu_stack_pop r2
        """;
    CallToolResult result = server.executeTool("sfx_expand_macro", Map.of("code", code));
    assertNotNull(result);
    assertFalse(result.isError());

    TextContent content = (TextContent) result.content().get(0);
    JsonNode json = objectMapper.readTree(content.text());
    String expanded = json.get("expandedCode").asText();
    assertTrue(expanded.contains("stw (r10)"));
    assertTrue(expanded.contains("ldw (r10)"));
  }

  @Test
  public void testLookupSymbolToolViaServer() throws Exception {
    String code =
        """
        ; Computes vector length
        function vectorLength
            return
        endfunction
        """;
    CallToolResult result =
        server.executeTool(
            "sfx_lookup_symbol",
            Map.of("symbol", "vectorLength", "code", code, "uri", "file:///math.s"));
    assertNotNull(result);
    assertFalse(result.isError());

    TextContent content = (TextContent) result.content().get(0);
    JsonNode json = objectMapper.readTree(content.text());
    assertTrue(json.get("found").asBoolean());
    assertEquals("vectorLength", json.get("symbol").asText());
    assertTrue(json.get("documentation").asText().contains("Computes vector length"));
  }

  @Test
  public void testAnalyzeRegistersToolViaServer() throws Exception {
    String code =
        """
        function transform vec
            register x
            with vec
            ldw (r10)
            to x
            move r0
            return
        endfunction
        """;
    CallToolResult result = server.executeTool("sfx_analyze_registers", Map.of("code", code));
    assertNotNull(result);
    assertFalse(result.isError());

    TextContent content = (TextContent) result.content().get(0);
    JsonNode json = objectMapper.readTree(content.text());
    assertTrue(json.get("maxRegisterPressure").asInt() > 0);
    assertEquals(16, json.get("availableRegisters").asInt());
  }

  @Test
  public void testSharedPersistenceAcrossToolCalls() throws Exception {
    String initialCode =
        """
        ; Shared vector normalizer
        function normalizeVector
            return
        endfunction
        """;

    // 1. First tool call: updates workspace index for file:///math.s
    CallToolResult firstResult =
        server.executeTool(
            "sfx_lookup_symbol",
            Map.of("symbol", "normalizeVector", "code", initialCode, "uri", "file:///math.s"));
    assertFalse(firstResult.isError());

    // 2. Second tool call: lookup normalizeVector without providing 'code'
    CallToolResult secondResult =
        server.executeTool("sfx_lookup_symbol", Map.of("symbol", "normalizeVector"));
    assertFalse(secondResult.isError());

    TextContent content = (TextContent) secondResult.content().get(0);
    JsonNode json = objectMapper.readTree(content.text());
    assertTrue(json.get("found").asBoolean(), "Symbol should persist in shared workspace index");
    assertEquals("normalizeVector", json.get("symbol").asText());
    assertTrue(json.get("documentation").asText().contains("Shared vector normalizer"));
  }

  @Test
  public void testFileUpdatesPropagateToSubsequentToolCalls() throws Exception {
    String originalCode =
        """
        my_label:
            nop
        """;

    server.executeTool(
        "sfx_lookup_symbol",
        Map.of("symbol", "my_label", "code", originalCode, "uri", "file:///labels.s"));

    // Update the file contents with a new docstring and moved line
    String updatedCode =
        """
        nop
        nop
        ; Updated label documentation
        my_label:
            nop
        """;

    server.executeTool(
        "sfx_lookup_symbol",
        Map.of("symbol", "my_label", "code", updatedCode, "uri", "file:///labels.s"));

    // Query without code
    CallToolResult result =
        server.executeTool(
            "sfx_lookup_symbol", Map.of("symbol", "my_label", "uri", "file:///labels.s"));
    assertFalse(result.isError());

    JsonNode json = objectMapper.readTree(((TextContent) result.content().get(0)).text());
    assertTrue(json.get("found").asBoolean());
    assertTrue(json.get("documentation").asText().contains("Updated label documentation"));
    assertEquals(3, json.get("location").get("line").asInt());
  }

  @Test
  public void testToolsUsingOnlyUriWithoutCode() throws Exception {
    String code =
        """
        nop
        nop
        nop
        nop
        nop
        nop
        nop
        nop
        stop
        """;
    server.getProjectService().updateFileContents(java.net.URI.create("file:///test.s"), code);

    // Audit invariants using only uri
    CallToolResult auditResult =
        server.executeTool("sfx_audit_invariants", Map.of("uri", "file:///test.s"));
    assertFalse(auditResult.isError());
    JsonNode auditJson = objectMapper.readTree(((TextContent) auditResult.content().get(0)).text());
    assertTrue(auditJson.get("valid").asBoolean());

    // Macro expand using only uri
    String macroCode = "gsu_stack_push r1";
    server
        .getProjectService()
        .updateFileContents(java.net.URI.create("file:///macro.s"), macroCode);
    CallToolResult expandResult =
        server.executeTool("sfx_expand_macro", Map.of("uri", "file:///macro.s"));
    assertFalse(expandResult.isError());
    JsonNode expandJson =
        objectMapper.readTree(((TextContent) expandResult.content().get(0)).text());
    assertTrue(expandJson.get("expandedCode").asText().contains("stw (r10)"));
  }

  @Test
  public void testFileWatcherIntegration(@TempDir Path tempDir) throws Exception {
    Path sourceFile = tempDir.resolve("test.s");
    Files.writeString(
        sourceFile,
        """
        ; Watcher documentation
        watcherSymbol:
            nop
        """);

    try (RetroLspMcpServer liveServer = new RetroLspMcpServer(null, tempDir)) {
      // Allow initial indexing
      CallToolResult result =
          liveServer.executeTool("sfx_lookup_symbol", Map.of("symbol", "watcherSymbol"));
      assertFalse(result.isError());
      JsonNode json = objectMapper.readTree(((TextContent) result.content().get(0)).text());
      assertTrue(json.get("found").asBoolean());
      assertTrue(json.get("documentation").asText().contains("Watcher documentation"));
    }
  }

  @Test
  public void testWorkspaceBoundaryValidation(@TempDir Path tempDir) {
    try (RetroLspMcpServer boundedServer = new RetroLspMcpServer(null, tempDir)) {
      // 1. Synthetic snippet URI inside or outside is permitted
      CallToolResult snippetResult =
          boundedServer.executeTool(
              "sfx_lookup_symbol",
              Map.of("symbol", "temp", "code", "temp:\n nop", "uri", "file:///snippet.s"));
      assertFalse(snippetResult.isError());

      // 2. URI inside workspace is permitted
      Path inside = tempDir.resolve("inside.s");
      CallToolResult insideResult =
          boundedServer.executeTool(
              "sfx_lookup_symbol",
              Map.of("symbol", "temp", "code", "temp:\n nop", "uri", inside.toUri().toString()));
      assertFalse(insideResult.isError());

      // 3. URI outside workspace is rejected with Security error
      Path outside = Path.of("/etc/shadow.s");
      CallToolResult outsideResult =
          boundedServer.executeTool(
              "sfx_lookup_symbol", Map.of("symbol", "temp", "uri", outside.toUri().toString()));
      assertTrue(outsideResult.isError());
      TextContent textContent = (TextContent) outsideResult.content().get(0);
      assertTrue(textContent.text().contains("Access denied"));
    }
  }

  @Test
  public void testLazyIndexingOnDemand(@TempDir Path tempDir) throws Exception {
    Path subDir = tempDir.resolve("subdir");
    Files.createDirectories(subDir);
    Path fileInSubdir = subDir.resolve("subfile.s");
    Files.writeString(
        fileInSubdir,
        """
        ; In subfile
        subfile_symbol:
            nop
        """);

    try (RetroLspMcpServer liveServer = new RetroLspMcpServer(null, tempDir)) {
      // Before indexing, query with specific URI triggers on-demand indexing of subdir
      CallToolResult result =
          liveServer.executeTool(
              "sfx_lookup_symbol",
              Map.of("symbol", "subfile_symbol", "uri", fileInSubdir.toUri().toString()));
      assertFalse(result.isError());
      JsonNode json = objectMapper.readTree(((TextContent) result.content().get(0)).text());
      assertTrue(json.get("found").asBoolean());
      assertTrue(json.get("documentation").asText().contains("In subfile"));
    }
  }

  @Test
  public void testUnknownToolReturnsError() {
    CallToolResult result = server.executeTool("non_existent_tool", Map.of());
    assertNotNull(result);
    assertTrue(result.isError());
    TextContent content = (TextContent) result.content().get(0);
    assertTrue(content.text().contains("Unknown tool"));
  }
}
