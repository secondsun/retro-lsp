package dev.secondsun.retrolsp.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCRequest;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCResponse;
import io.modelcontextprotocol.spec.ServerMcpTransport;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import reactor.core.publisher.Mono;

/**
 * HTTP server transport for the Model Context Protocol (MCP) using {@link HttpServer}.
 *
 * <p>Supports both:
 *
 * <ul>
 *   <li>Standard MCP SSE stream transport on {@code /sse} with POST {@code /message?sessionId=...}
 *   <li>Direct JSON-RPC HTTP POST requests on {@code /}, {@code /mcp}, and {@code /message}
 * </ul>
 */
public class HttpServerMcpTransport implements ServerMcpTransport, AutoCloseable {

  private static final Logger LOG = Logger.getLogger(HttpServerMcpTransport.class.getName());

  private final String host;
  private final int actualPort;
  private final HttpServer server;
  private final ObjectMapper objectMapper;
  private final Map<String, SseClientSession> sseSessions = new ConcurrentHashMap<>();
  private final Map<Object, CompletableFuture<JSONRPCResponse>> pendingResponses =
      new ConcurrentHashMap<>();
  private final AtomicBoolean isClosing = new AtomicBoolean(false);

  private Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> handler;

  /**
   * Creates an HTTP MCP transport bound to the default host and specified port.
   *
   * @param port the port to bind to, or 0 for an ephemeral port
   * @throws IOException if the server fails to bind
   */
  public HttpServerMcpTransport(int port) throws IOException {
    this("127.0.0.1", port);
  }

  /**
   * Creates an HTTP MCP transport bound to the specified host and port.
   *
   * @param host the host IP or interface
   * @param port the port to bind to, or 0 for an ephemeral port
   * @throws IOException if the server fails to bind
   */
  public HttpServerMcpTransport(String host, int port) throws IOException {
    this(host, port, new ObjectMapper());
  }

  /**
   * Creates an HTTP MCP transport with a custom ObjectMapper.
   *
   * @param host the host IP or interface
   * @param port the port to bind to, or 0 for an ephemeral port
   * @param objectMapper the object mapper for serialization
   * @throws IOException if the server fails to bind
   */
  public HttpServerMcpTransport(String host, int port, ObjectMapper objectMapper)
      throws IOException {
    this.host = host != null ? host : "127.0.0.1";
    this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    this.server = createServerWithRetry(this.host, port);
    this.actualPort = this.server.getAddress().getPort();
    this.server.setExecutor(Executors.newCachedThreadPool());
    this.server.createContext("/", this::handleExchange);
  }

  private HttpServer createServerWithRetry(String host, int initialPort) throws IOException {
    if (initialPort <= 0) {
      return HttpServer.create(new InetSocketAddress(host, 0), 0);
    }

    int port = initialPort;
    int maxAttempts = 10;
    IOException lastException = null;

    for (int i = 0; i < maxAttempts; i++) {
      try {
        return HttpServer.create(new InetSocketAddress(host, port), 0);
      } catch (BindException e) {
        lastException = e;
        LOG.fine("Port " + port + " is unavailable, trying " + (port + 1));
        port++;
      } catch (IOException e) {
        throw e;
      }
    }

    throw new IOException(
        "Failed to bind HttpServer on port " + initialPort + ".." + (port - 1), lastException);
  }

  /** Returns the actual bound port number. */
  public int getPort() {
    return actualPort;
  }

  /** Returns the bound host address. */
  public String getHost() {
    return host;
  }

  /** Returns the underlying {@link HttpServer}. */
  public HttpServer getServer() {
    return server;
  }

  @Override
  public Mono<Void> connect(Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> handler) {
    this.handler = handler;
    this.server.start();
    LOG.info("MCP HTTP server started on http://" + host + ":" + actualPort);
    return Mono.empty();
  }

  @Override
  public Mono<Void> sendMessage(JSONRPCMessage message) {
    return Mono.fromRunnable(
        () -> {
          if (message instanceof JSONRPCResponse response) {
            Object id = response.id();
            if (id != null) {
              CompletableFuture<JSONRPCResponse> future = pendingResponses.remove(id);
              if (future != null) {
                future.complete(response);
              }
            }
          }

          if (!sseSessions.isEmpty()) {
            try {
              String json = objectMapper.writeValueAsString(message);
              List<String> failedSessions = new ArrayList<>();
              for (SseClientSession session : sseSessions.values()) {
                try {
                  session.sendEvent("message", json);
                } catch (IOException e) {
                  LOG.log(Level.FINE, "Failed to send message to SSE session " + session.id(), e);
                  failedSessions.add(session.id());
                }
              }
              for (String sessionId : failedSessions) {
                SseClientSession session = sseSessions.remove(sessionId);
                if (session != null) {
                  session.close();
                }
              }
            } catch (Exception e) {
              LOG.log(
                  Level.WARNING, "Error serializing MCP response message: " + e.getMessage(), e);
            }
          }
        });
  }

  @Override
  public <T> T unmarshalFrom(Object data, TypeReference<T> typeRef) {
    return objectMapper.convertValue(data, typeRef);
  }

  @Override
  public Mono<Void> closeGracefully() {
    return Mono.fromRunnable(this::close);
  }

  @Override
  public void close() {
    if (isClosing.compareAndSet(false, true)) {
      LOG.info("Stopping MCP HTTP server on http://" + host + ":" + actualPort);
      for (SseClientSession session : sseSessions.values()) {
        session.close();
      }
      sseSessions.clear();
      for (CompletableFuture<?> future : pendingResponses.values()) {
        future.cancel(true);
      }
      pendingResponses.clear();
      server.stop(0);
    }
  }

  private void handleExchange(HttpExchange exchange) throws IOException {
    String method = exchange.getRequestMethod().toUpperCase();
    String path = exchange.getRequestURI().getPath();

    // CORS preflight
    if ("OPTIONS".equals(method)) {
      exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
      exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
      exchange
          .getResponseHeaders()
          .set("Access-Control-Allow-Headers", "Content-Type, Authorization");
      exchange.sendResponseHeaders(204, -1);
      exchange.close();
      return;
    }

    if ("GET".equals(method)) {
      if ("/sse".equals(path) || path.startsWith("/sse/") || isSseRequest(exchange)) {
        handleSseConnect(exchange);
        return;
      }
      if ("/".equals(path) || "/ping".equals(path) || "/health".equals(path)) {
        byte[] responseBytes =
            "{\"status\":\"ok\",\"server\":\"retro-lsp\",\"mcp\":true}"
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(200, responseBytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
          os.write(responseBytes);
        }
        exchange.close();
        return;
      }
      sendError(exchange, 404, "Not Found");
      return;
    }

    if ("POST".equals(method)) {
      handlePostMessage(exchange);
      return;
    }

    sendError(exchange, 405, "Method Not Allowed");
  }

  private boolean isSseRequest(HttpExchange exchange) {
    String accept = exchange.getRequestHeaders().getFirst("Accept");
    return accept != null && accept.contains("text/event-stream");
  }

  private void handleSseConnect(HttpExchange exchange) throws IOException {
    if (isClosing.get()) {
      sendError(exchange, 503, "Server is shutting down");
      return;
    }

    exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=UTF-8");
    exchange.getResponseHeaders().set("Cache-Control", "no-cache");
    exchange.getResponseHeaders().set("Connection", "keep-alive");
    exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
    exchange.sendResponseHeaders(200, 0);

    String sessionId = UUID.randomUUID().toString();
    SseClientSession session = new SseClientSession(sessionId, exchange);
    sseSessions.put(sessionId, session);

    try {
      session.sendEvent("endpoint", "/message?sessionId=" + sessionId);
      LOG.info("MCP SSE client connected: session " + sessionId);
      session.awaitClose();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } finally {
      sseSessions.remove(sessionId);
      session.close();
      LOG.info("MCP SSE client disconnected: session " + sessionId);
    }
  }

  private void handlePostMessage(HttpExchange exchange) throws IOException {
    if (isClosing.get()) {
      sendError(exchange, 503, "Server is shutting down");
      return;
    }

    byte[] bodyBytes;
    try (InputStream is = exchange.getRequestBody()) {
      bodyBytes = is.readAllBytes();
    }

    if (bodyBytes.length == 0) {
      sendError(exchange, 400, "Empty request body");
      return;
    }

    String body = new String(bodyBytes, StandardCharsets.UTF_8);
    JSONRPCMessage message;
    try {
      message = McpSchema.deserializeJsonRpcMessage(objectMapper, body);
    } catch (Exception e) {
      LOG.log(Level.WARNING, "Failed to parse JSON-RPC message: " + body, e);
      sendError(exchange, 400, "Invalid JSON-RPC message: " + e.getMessage());
      return;
    }

    if (this.handler == null) {
      sendError(exchange, 503, "No MCP handler connected");
      return;
    }

    String sessionId = getQueryParam(exchange.getRequestURI().getQuery(), "sessionId");
    SseClientSession sseSession = sessionId != null ? sseSessions.get(sessionId) : null;

    if (sseSession != null) {
      // SSE client session active: respond 202 Accepted, response will be dispatched via SSE
      exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
      exchange.sendResponseHeaders(202, -1);
      exchange.close();

      this.handler
          .apply(Mono.just(message))
          .subscribe(
              m -> {},
              err ->
                  LOG.log(
                      Level.WARNING, "Error processing SSE MCP message: " + err.getMessage(), err));
    } else {
      // Direct HTTP POST: execute and return JSON-RPC response directly
      if (message instanceof JSONRPCRequest request) {
        Object id = request.id();
        CompletableFuture<JSONRPCResponse> future = new CompletableFuture<>();
        if (id != null) {
          pendingResponses.put(id, future);
        }
        try {
          this.handler
              .apply(Mono.just(message))
              .subscribe(
                  m -> {},
                  err -> {
                    if (id != null) {
                      pendingResponses.remove(id);
                    }
                    future.completeExceptionally(err);
                  });

          JSONRPCResponse response = future.get(15, TimeUnit.SECONDS);
          byte[] responseBytes = objectMapper.writeValueAsBytes(response);
          exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
          exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
          exchange.sendResponseHeaders(200, responseBytes.length);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(responseBytes);
          }
        } catch (TimeoutException te) {
          if (id != null) {
            pendingResponses.remove(id);
          }
          sendError(exchange, 504, "MCP request timed out");
        } catch (Exception ex) {
          if (id != null) {
            pendingResponses.remove(id);
          }
          sendError(exchange, 500, "Internal MCP error: " + ex.getMessage());
        } finally {
          exchange.close();
        }
      } else {
        // Notification
        this.handler
            .apply(Mono.just(message))
            .subscribe(
                m -> {},
                err ->
                    LOG.log(
                        Level.WARNING,
                        "Error processing MCP notification: " + err.getMessage(),
                        err));
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(204, -1);
        exchange.close();
      }
    }
  }

  private void sendError(HttpExchange exchange, int statusCode, String message) {
    try {
      byte[] bytes = objectMapper.writeValueAsBytes(Map.of("error", message));
      exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
      exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
      exchange.sendResponseHeaders(statusCode, bytes.length);
      try (OutputStream os = exchange.getResponseBody()) {
        os.write(bytes);
      }
    } catch (IOException ignored) {
    } finally {
      exchange.close();
    }
  }

  private String getQueryParam(String query, String paramName) {
    if (query == null || query.isEmpty()) {
      return null;
    }
    String[] pairs = query.split("&");
    for (String pair : pairs) {
      int idx = pair.indexOf("=");
      if (idx > 0) {
        String key = URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8);
        if (paramName.equals(key)) {
          return URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8);
        }
      }
    }
    return null;
  }

  /** Represents a connected Server-Sent Events (SSE) client session. */
  public static class SseClientSession implements AutoCloseable {

    private final String id;
    private final HttpExchange exchange;
    private final OutputStream outputStream;
    private final PrintWriter writer;
    private final CountDownLatch closeLatch = new CountDownLatch(1);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public SseClientSession(String id, HttpExchange exchange) {
      this.id = id;
      this.exchange = exchange;
      this.outputStream = exchange.getResponseBody();
      this.writer = new PrintWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8));
    }

    public String id() {
      return id;
    }

    public synchronized void sendEvent(String eventType, String data) throws IOException {
      if (closed.get()) {
        throw new IOException("SSE session closed");
      }
      if (eventType != null) {
        writer.write("event: " + eventType + "\n");
      }
      for (String line : data.split("\n")) {
        writer.write("data: " + line + "\n");
      }
      writer.write("\n");
      writer.flush();
      if (writer.checkError()) {
        close();
        throw new IOException("Connection broken writing SSE event");
      }
    }

    public void awaitClose() throws InterruptedException {
      closeLatch.await();
    }

    @Override
    public void close() {
      if (closed.compareAndSet(false, true)) {
        closeLatch.countDown();
        try {
          writer.close();
        } catch (Exception ignored) {
        }
        try {
          outputStream.close();
        } catch (Exception ignored) {
        }
        exchange.close();
      }
    }
  }
}
