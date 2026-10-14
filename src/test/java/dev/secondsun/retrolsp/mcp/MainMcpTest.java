package dev.secondsun.retrolsp.mcp;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.secondsun.retrolsp.Main;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;

public class MainMcpTest {

  @Test
  public void testMainConcurrentLspStdioAndMcpHttp() throws Exception {
    InputStream originalIn = System.in;
    PrintStream originalOut = System.out;

    PipedOutputStream clientInPipe = new PipedOutputStream();
    PipedInputStream serverIn = new PipedInputStream(clientInPipe);
    ByteArrayOutputStream serverOut = new ByteArrayOutputStream();
    PrintStream customOut = new PrintStream(serverOut, true, StandardCharsets.UTF_8);

    int testPort = 8098;

    try {
      System.setIn(serverIn);
      System.setOut(customOut);

      Thread serverThread =
          new Thread(
              () -> {
                Main.main("--mcp-port=" + testPort, "--mcp");
              });
      serverThread.start();

      HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

      // 1. Verify MCP is available via HTTP
      HttpResponse<String> httpResponse = null;
      for (int i = 0; i < 20; i++) {
        try {
          HttpRequest httpRequest =
              HttpRequest.newBuilder()
                  .uri(URI.create("http://127.0.0.1:" + testPort + "/ping"))
                  .GET()
                  .build();
          httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
          if (httpResponse.statusCode() == 200) {
            break;
          }
        } catch (Exception e) {
          Thread.sleep(100);
        }
      }

      assertNotNull(httpResponse, "MCP HTTP server should respond within timeout");
      assertEquals(200, httpResponse.statusCode());
      ObjectMapper mapper = new ObjectMapper();
      JsonNode json = mapper.readTree(httpResponse.body());
      assertEquals("ok", json.get("status").asText());
      assertTrue(json.get("mcp").asBoolean());

      // 2. Verify LSP continues to work over stdio concurrently
      String lspRequest =
          "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"rootUri\":\"file:///tmp\",\"capabilities\":{}}}";
      byte[] payloadBytes = lspRequest.getBytes(StandardCharsets.UTF_8);
      String header = String.format("Content-Length: %d\r\n\r\n", payloadBytes.length);
      clientInPipe.write(header.getBytes(StandardCharsets.UTF_8));
      clientInPipe.write(payloadBytes);
      clientInPipe.flush();

      // Wait for LSP response on stdio
      String stdoutContent = "";
      for (int i = 0; i < 20; i++) {
        stdoutContent = serverOut.toString(StandardCharsets.UTF_8);
        if (stdoutContent.contains("\"capabilities\"")) {
          break;
        }
        Thread.sleep(100);
      }

      assertTrue(
          stdoutContent.contains("\"capabilities\""),
          "LSP should respond over stdio with server capabilities");

      // 3. Close client stdin pipe to signal EOF and trigger clean shutdown
      clientInPipe.close();
      serverThread.join(3000);
      assertFalse(serverThread.isAlive(), "Server thread should terminate cleanly on EOF");
    } finally {
      System.setIn(originalIn);
      System.setOut(originalOut);
      try {
        clientInPipe.close();
      } catch (Exception ignored) {
      }
    }
  }
}
