package dev.secondsun.retrolsp;

import dev.secondsun.lsp.LSP;
import dev.secondsun.retrolsp.mcp.RetroLspMcpServer;
import java.util.Arrays;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Entry point for the retro-lsp Language Server and MCP Server process. */
public class Main {

  private static final Logger LOG = Logger.getLogger(Main.class.getName());

  /** Private constructor to prevent instantiation of this entry point class. */
  private Main() {}

  /**
   * Main entry point to start the LSP or MCP server communicating over standard input and output.
   *
   * @param args command line arguments
   */
  public static void main(String... args) {
    if (args != null && Arrays.asList(args).contains("--mcp")) {
      LOG.info("Starting Retro-LSP MCP Server over stdio");
      try (RetroLspMcpServer mcpServer = RetroLspMcpServer.createStdio()) {
        mcpServer.await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        LOG.info("Retro-LSP MCP Server interrupted");
      } catch (Exception e) {
        LOG.log(Level.SEVERE, "Retro-LSP MCP Server error: " + e.getMessage(), e);
      }
    } else {
      LOG.info("Starting");
      LSP.connect((langClient) -> new CA65LanguageServer(), System.in, System.out);
    }
  }
}
