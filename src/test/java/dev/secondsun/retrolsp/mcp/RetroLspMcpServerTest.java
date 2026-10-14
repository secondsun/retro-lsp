package dev.secondsun.retrolsp.mcp;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class RetroLspMcpServerTest {

  private RetroLspMcpServer server;
  private ObjectMapper objectMapper;

  @BeforeEach
  public void setUp() {
    server = new RetroLspMcpServer();
    objectMapper = new ObjectMapper();
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
  public void testUnknownToolReturnsError() {
    CallToolResult result = server.executeTool("non_existent_tool", Map.of());
    assertNotNull(result);
    assertTrue(result.isError());
    TextContent content = (TextContent) result.content().get(0);
    assertTrue(content.text().contains("Unknown tool"));
  }
}
