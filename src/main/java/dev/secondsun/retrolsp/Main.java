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
    if (args != null && Arrays.asList(args).contains("--log")) {
      RetroLspMcpServer.configureLogging();
    }
    if (args != null && Arrays.asList(args).contains("--mcp")) {
      LOG.info("Starting Retro-LSP MCP Server over stdio");
      java.nio.file.Path workspaceRoot = null;
      for (int i = 0; i < args.length; i++) {
        if ("--workspace".equals(args[i]) && i + 1 < args.length) {
          workspaceRoot = java.nio.file.Path.of(args[i + 1]);
        }
      }
      if (workspaceRoot == null) {
        String errMsg = "Error: --workspace <path> is required when running in MCP mode (--mcp)";
        LOG.severe(errMsg);
        System.err.println(errMsg);
        return;
      }
      try (RetroLspMcpServer mcpServer = RetroLspMcpServer.createStdio(workspaceRoot)) {
        mcpServer.await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        LOG.info("Retro-LSP MCP Server interrupted");
      } catch (Exception e) {
        LOG.log(Level.SEVERE, "Retro-LSP MCP Server error: " + e.getMessage(), e);
      }
    } else {
      LOG.info("Starting");
      LSP.connect(CA65LanguageServer::new, System.in, System.out);
    }
  }
}
