package dev.secondsun.retrolsp.mcp;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class HttpServerMcpTransportTest {

  private RetroLspMcpServer server;
  private int port;
  private HttpClient client;
  private ObjectMapper objectMapper;

  @BeforeEach
  public void setUp() throws Exception {
    server = RetroLspMcpServer.createHttp(0);
    port = server.getPort();
    assertTrue(port > 0, "Server port should be bound to a positive port");
    client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    objectMapper = new ObjectMapper();
  }

  @AfterEach
  public void tearDown() {
    if (server != null) {
      server.close();
    }
  }

  @Test
  public void testPingEndpoint() throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + "/ping"))
            .GET()
            .build();

    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
    assertEquals(200, response.statusCode());

    JsonNode json = objectMapper.readTree(response.body());
    assertEquals("ok", json.get("status").asText());
    assertEquals("retro-lsp", json.get("server").asText());
    assertTrue(json.get("mcp").asBoolean());
  }

  @Test
  public void testDirectHttpPostToolsList() throws Exception {
    String rpcRequest =
        """
        {
          "jsonrpc": "2.0",
          "id": 1,
          "method": "tools/list",
          "params": {}
        }
        """;

    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + "/"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(rpcRequest))
            .build();

    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
    assertEquals(200, response.statusCode());

    JsonNode json = objectMapper.readTree(response.body());
    assertEquals("2.0", json.get("jsonrpc").asText());
    assertEquals(1, json.get("id").asInt());

    JsonNode tools = json.get("result").get("tools");
    assertNotNull(tools);
    assertTrue(tools.isArray());
    assertTrue(tools.size() >= 4);

    boolean hasAudit = false;
    boolean hasExpand = false;
    boolean hasLookup = false;
    boolean hasAnalyze = false;

    for (JsonNode tool : tools) {
      String name = tool.get("name").asText();
      if ("sfx_audit_invariants".equals(name)) hasAudit = true;
      if ("sfx_expand_macro".equals(name)) hasExpand = true;
      if ("sfx_lookup_symbol".equals(name)) hasLookup = true;
      if ("sfx_analyze_registers".equals(name)) hasAnalyze = true;
    }

    assertTrue(hasAudit, "Should contain sfx_audit_invariants");
    assertTrue(hasExpand, "Should contain sfx_expand_macro");
    assertTrue(hasLookup, "Should contain sfx_lookup_symbol");
    assertTrue(hasAnalyze, "Should contain sfx_analyze_registers");
  }

  @Test
  public void testDirectHttpPostToolCall() throws Exception {
    String rpcRequest =
        """
        {
          "jsonrpc": "2.0",
          "id": 2,
          "method": "tools/call",
          "params": {
            "name": "sfx_expand_macro",
            "arguments": {
              "code": "gsu_stack_push r1\\ngsu_stack_pop r2"
            }
          }
        }
        """;

    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + "/mcp"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(rpcRequest))
            .build();

    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
    assertEquals(200, response.statusCode());

    JsonNode json = objectMapper.readTree(response.body());
    assertEquals("2.0", json.get("jsonrpc").asText());
    assertEquals(2, json.get("id").asInt());

    JsonNode result = json.get("result");
    assertNotNull(result);
    assertFalse(result.get("isError").asBoolean());

    JsonNode content = result.get("content").get(0);
    assertEquals("text", content.get("type").asText());
    String text = content.get("text").asText();
    assertTrue(text.contains("expandedCode"));
    assertTrue(text.contains("stw (r10)"));
  }

  @Test
  public void testSseTransportFlow() throws Exception {
    HttpRequest sseRequest =
        HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + "/sse"))
            .header("Accept", "text/event-stream")
            .GET()
            .build();

    CompletableFuture<String> endpointUriFuture = new CompletableFuture<>();
    CompletableFuture<String> messageDataFuture = new CompletableFuture<>();

    Thread readerThread =
        new Thread(
            () -> {
              try {
                HttpResponse<java.io.InputStream> response =
                    client.send(sseRequest, HttpResponse.BodyHandlers.ofInputStream());
                try (BufferedReader reader =
                    new BufferedReader(
                        new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                  String currentEvent = null;
                  String line;
                  while ((line = reader.readLine()) != null) {
                    if (line.startsWith("event: ")) {
                      currentEvent = line.substring("event: ".length()).trim();
                    } else if (line.startsWith("data: ")) {
                      String data = line.substring("data: ".length()).trim();
                      if ("endpoint".equals(currentEvent)) {
                        endpointUriFuture.complete(data);
                      } else if ("message".equals(currentEvent)) {
                        messageDataFuture.complete(data);
                      }
                    }
                  }
                }
              } catch (Exception e) {
                if (!endpointUriFuture.isDone()) {
                  endpointUriFuture.completeExceptionally(e);
                }
                if (!messageDataFuture.isDone()) {
                  messageDataFuture.completeExceptionally(e);
                }
              }
            });
    readerThread.setDaemon(true);
    readerThread.start();

    // 1. Wait for initial endpoint event
    String endpointUri = endpointUriFuture.get(5, TimeUnit.SECONDS);
    assertNotNull(endpointUri);
    assertTrue(endpointUri.startsWith("/message?sessionId="));

    // 2. Post a JSON-RPC request to that message endpoint
    String rpcRequest =
        """
        {
          "jsonrpc": "2.0",
          "id": 99,
          "method": "tools/list",
          "params": {}
        }
        """;

    HttpRequest postRequest =
        HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + endpointUri))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(rpcRequest))
            .build();

    HttpResponse<String> postResponse =
        client.send(postRequest, HttpResponse.BodyHandlers.ofString());
    assertEquals(202, postResponse.statusCode());

    // 3. Wait for the response event to arrive over the SSE stream
    String eventData = messageDataFuture.get(5, TimeUnit.SECONDS);
    assertNotNull(eventData);

    JsonNode json = objectMapper.readTree(eventData);
    assertEquals("2.0", json.get("jsonrpc").asText());
    assertEquals(99, json.get("id").asInt());
    assertNotNull(json.get("result").get("tools"));
  }
}

