package dev.secondsun.retrolsp.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.secondsun.retrolsp.mcp.tools.SfxInvariantAuditor;
import dev.secondsun.retrolsp.mcp.tools.SfxMacroExpanderTool;
import dev.secondsun.retrolsp.mcp.tools.SfxRegisterAnalysisTool;
import dev.secondsun.retrolsp.mcp.tools.SfxSymbolLookupTool;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.ServerMcpTransport;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Model Context Protocol (MCP) server for Retro-LSP, exposing SuperFX static analysis,
 * macro expansion, symbol lookup, and register analysis tools over stdio or custom transports.
 */
public class RetroLspMcpServer implements AutoCloseable {

  private static final Logger LOG = Logger.getLogger(RetroLspMcpServer.class.getName());

  private static final String AUDIT_INVARIANTS_SCHEMA =
      """
      {
        "type": "object",
        "properties": {
          "code": {
            "type": "string",
            "description": "SuperFX CA65 assembly code to audit"
          },
          "uri": {
            "type": "string",
            "description": "Optional file URI"
          }
        },
        "required": ["code"]
      }
      """;

  private static final String EXPAND_MACRO_SCHEMA =
      """
      {
        "type": "object",
        "properties": {
          "code": {
            "type": "string",
            "description": "CA65 assembly source code containing macros"
          }
        },
        "required": ["code"]
      }
      """;

  private static final String LOOKUP_SYMBOL_SCHEMA =
      """
      {
        "type": "object",
        "properties": {
          "symbol": {
            "type": "string",
            "description": "Symbol identifier to look up"
          },
          "code": {
            "type": "string",
            "description": "Optional CA65 assembly code context"
          },
          "uri": {
            "type": "string",
            "description": "Optional document URI"
          },
          "line": {
            "type": "integer",
            "description": "Optional cursor line number for scoped lookup"
          }
        },
        "required": ["symbol"]
      }
      """;

  private static final String ANALYZE_REGISTERS_SCHEMA =
      """
      {
        "type": "object",
        "properties": {
          "code": {
            "type": "string",
            "description": "SuperFX assembly code to analyze"
          },
          "function": {
            "type": "string",
            "description": "Optional function name to focus analysis on"
          }
        },
        "required": ["code"]
      }
      """;

  private final ObjectMapper objectMapper;
  private final SfxInvariantAuditor invariantAuditor;
  private final SfxMacroExpanderTool macroExpander;
  private final SfxSymbolLookupTool symbolLookup;
  private final SfxRegisterAnalysisTool registerAnalysis;
  private final McpSyncServer syncServer;
  private final CountDownLatch stopLatch;

  /** Constructs a standalone MCP server instance without transport (for tool testing). */
  public RetroLspMcpServer() {
    this(null);
  }

  /**
   * Constructs an MCP server attached to the given transport.
   *
   * @param transport the transport to listen and serve on, or null for standalone
   */
  public RetroLspMcpServer(ServerMcpTransport transport) {
    this.objectMapper = new ObjectMapper();
    this.invariantAuditor = new SfxInvariantAuditor();
    this.macroExpander = new SfxMacroExpanderTool();
    this.symbolLookup = new SfxSymbolLookupTool();
    this.registerAnalysis = new SfxRegisterAnalysisTool();
    this.stopLatch = new CountDownLatch(1);

    if (transport != null) {
      Tool auditTool =
          new Tool(
              "sfx_audit_invariants",
              "Audits SuperFX assembly for hardware invariants: 8-NOP pipeline settling before 'stop', 16-bit word aligned 'ldw'/'stw', absence of hardware 'MERGE' on FX3, and modal instruction lifetime ('with'/'to'/'from').",
              AUDIT_INVARIANTS_SCHEMA);

      Tool expandTool =
          new Tool(
              "sfx_expand_macro",
              "Expands CA65 and X-GSU macros (.macro, .define, function, for, stack operations, etc.) into a concrete instruction stream.",
              EXPAND_MACRO_SCHEMA);

      Tool lookupTool =
          new Tool(
              "sfx_lookup_symbol",
              "Resolves symbol definitions, scopes, and documentation in CA65 assembly using SymbolService.",
              LOOKUP_SYMBOL_SCHEMA);

      Tool analyzeTool =
          new Tool(
              "sfx_analyze_registers",
              "Analyzes SuperFX register allocation, live-range intervals, CFG basic blocks, and register pressure.",
              ANALYZE_REGISTERS_SCHEMA);

      ServerCapabilities capabilities =
          ServerCapabilities.builder().tools(true).build();

      this.syncServer =
          McpServer.sync(transport)
              .serverInfo("retro-lsp", "0.0.6-PROTOTYPE")
              .capabilities(capabilities)
              .tool(auditTool, args -> executeTool("sfx_audit_invariants", args))
              .tool(expandTool, args -> executeTool("sfx_expand_macro", args))
              .tool(lookupTool, args -> executeTool("sfx_lookup_symbol", args))
              .tool(analyzeTool, args -> executeTool("sfx_analyze_registers", args))
              .build();
    } else {
      this.syncServer = null;
    }
  }

  /**
   * Creates and starts a Retro-LSP MCP server communicating over standard input and output.
   *
   * @return a running RetroLspMcpServer instance
   */
  public static RetroLspMcpServer createStdio() {
    StdioServerTransport transport = new StdioServerTransport(new ObjectMapper());
    return new RetroLspMcpServer(transport);
  }

  /**
   * Executes a tool by name with arguments and formats the result as an MCP CallToolResult.
   *
   * @param name tool name
   * @param args tool arguments
   * @return CallToolResult containing serialized JSON or error
   */
  public CallToolResult executeTool(String name, Map<String, Object> args) {
    try {
      Object resultData =
          switch (name) {
            case "sfx_audit_invariants" -> {
              String code = (String) args.get("code");
              yield invariantAuditor.audit(code);
            }
            case "sfx_expand_macro" -> {
              String code = (String) args.get("code");
              yield macroExpander.expand(code);
            }
            case "sfx_lookup_symbol" -> {
              String symbol = (String) args.get("symbol");
              String code = (String) args.get("code");
              String uri = (String) args.get("uri");
              Number lineNum = (Number) args.get("line");
              Integer line = lineNum != null ? lineNum.intValue() : null;
              yield symbolLookup.lookup(symbol, code, uri, line);
            }
            case "sfx_analyze_registers" -> {
              String code = (String) args.get("code");
              String function = (String) args.get("function");
              yield registerAnalysis.analyze(code, function);
            }
            default -> throw new IllegalArgumentException("Unknown tool: " + name);
          };

      String jsonText = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(resultData);
      return new CallToolResult(List.of(new TextContent(jsonText)), false);
    } catch (Exception e) {
      LOG.log(Level.WARNING, "Error executing MCP tool '" + name + "': " + e.getMessage(), e);
      return new CallToolResult(
          List.of(new TextContent("Error executing tool '" + name + "': " + e.getMessage())),
          true);
    }
  }

  /** Waits until the server is closed. */
  public void await() throws InterruptedException {
    stopLatch.await();
  }

  @Override
  public void close() {
    if (syncServer != null) {
      try {
        syncServer.close();
      } catch (Exception e) {
        LOG.log(Level.FINE, "Error closing MCP sync server: " + e.getMessage(), e);
      }
    }
    stopLatch.countDown();
  }
}
