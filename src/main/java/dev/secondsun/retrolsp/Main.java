package dev.secondsun.retrolsp;

import dev.secondsun.lsp.LSP;
import dev.secondsun.retrolsp.mcp.RetroLspMcpServer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Entry point for the retro-lsp Language Server and MCP Server process.
 *
 * <p>The Language Server Protocol (LSP) always communicates over standard input and output
 * (System.in, System.out). When the {@code --mcp} flag is supplied, an HTTP MCP server is also
 * started concurrently to expose SuperFX analysis tools over HTTP (SSE and direct POST).
 */
public class Main {

  private static final Logger LOG = Logger.getLogger(Main.class.getName());

  /** Default HTTP port for the MCP server if not specified. */
  public static final int DEFAULT_MCP_PORT = 8085;

  /** Private constructor to prevent instantiation of this entry point class. */
  private Main() {}

  /**
   * Main entry point to start the LSP server over stdio and optionally an MCP server over HTTP.
   *
   * @param args command line arguments
   */
  public static void main(String... args) {
    boolean enableMcp = false;
    int mcpPort = DEFAULT_MCP_PORT;

    if (args != null) {
      for (int i = 0; i < args.length; i++) {
        String arg = args[i];
        if ("--mcp".equals(arg)) {
          enableMcp = true;
        } else if (arg.startsWith("--mcp=")) {
          enableMcp = true;
          try {
            mcpPort = Integer.parseInt(arg.substring("--mcp=".length()));
          } catch (NumberFormatException e) {
            LOG.warning(
                "Invalid port in --mcp flag: " + arg + ", using default " + DEFAULT_MCP_PORT);
          }
        } else if (("--mcp-port".equals(arg) || "--port".equals(arg)) && i + 1 < args.length) {
          enableMcp = true;
          try {
            mcpPort = Integer.parseInt(args[++i]);
          } catch (NumberFormatException e) {
            LOG.warning(
                "Invalid port argument: " + args[i] + ", using default " + DEFAULT_MCP_PORT);
          }
        } else if (arg.startsWith("--mcp-port=")) {
          enableMcp = true;
          try {
            mcpPort = Integer.parseInt(arg.substring("--mcp-port=".length()));
          } catch (NumberFormatException e) {
            LOG.warning(
                "Invalid port in --mcp-port flag: " + arg + ", using default " + DEFAULT_MCP_PORT);
          }
        }
      }
    }

    String envPort = System.getenv("RETRO_LSP_MCP_PORT");
    if (envPort != null && !envPort.isBlank()) {
      try {
        mcpPort = Integer.parseInt(envPort.trim());
      } catch (NumberFormatException e) {
        LOG.warning("Invalid RETRO_LSP_MCP_PORT: " + envPort);
      }
    }

    RetroLspMcpServer mcpServer = null;
    if (enableMcp) {
      LOG.info("Starting Retro-LSP MCP Server over HTTP");
      try {
        mcpServer = RetroLspMcpServer.createHttp(mcpPort);
        LOG.info("Retro-LSP MCP Server listening on http://127.0.0.1:" + mcpServer.getPort());
      } catch (Exception e) {
        LOG.log(
            Level.SEVERE, "Failed to start Retro-LSP MCP Server over HTTP: " + e.getMessage(), e);
      }
    }

    try {
      LOG.info("Starting Retro-LSP Language Server over stdio");
      LSP.connect((langClient) -> new CA65LanguageServer(), System.in, System.out);
    } finally {
      if (mcpServer != null) {
        LOG.info("Closing Retro-LSP MCP Server");
        mcpServer.close();
      }
    }
  }
}
