package dev.secondsun.retrolsp.mcp;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.secondsun.retro.util.FileService;
import dev.secondsun.retro.util.ProjectService;
import dev.secondsun.retro.util.SymbolService;
import dev.secondsun.retrolsp.mcp.tools.SfxInvariantAuditor;
import dev.secondsun.retrolsp.mcp.tools.SfxMacroExpanderTool;
import dev.secondsun.retrolsp.mcp.tools.SfxRegisterAnalysisTool;
import dev.secondsun.retrolsp.mcp.tools.SfxSymbolLookupTool;
import dev.secondsun.retrolsp.mcp.tools.SfxTexturePackerTool;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.ServerMcpTransport;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/**
 * Model Context Protocol (MCP) server for Retro-LSP, exposing SuperFX static analysis, macro
 * expansion, symbol lookup, and register analysis tools over stdio or custom transports backed by a
 * single shared persistent {@link ProjectService}.
 *
 * <p>Enforces a strict workspace sandbox boundary and performs lazy, on-demand directory indexing.
 */
public class RetroLspMcpServer implements AutoCloseable {

  private static final Logger LOG = Logger.getLogger(RetroLspMcpServer.class.getName());
  private static final AtomicBoolean loggingConfigured = new AtomicBoolean(false);
  private static Path currentLogFile;
  private static FileHandler currentFileHandler;

  private static final String AUDIT_DIAGNOSTICS_SCHEMA =
      """
      {
        "type": "object",
        "properties": {
          "code": {
            "type": "string",
            "description": "SuperFX CA65 assembly code to audit for syntax errors, hardware invariants, and stack displacement invariants (optional if uri or path is provided)"
          },
          "uri": {
            "type": "string",
            "description": "Optional file URI pointing to workspace source"
          },
          "path": {
            "type": "string",
            "description": "Optional workspace-relative file path or URI pointing to workspace source"
          }
        }
      }
      """;

  private static final String AUDIT_INVARIANTS_SCHEMA = AUDIT_DIAGNOSTICS_SCHEMA;

  private static final String EXPAND_MACRO_SCHEMA =
      """
      {
        "type": "object",
        "properties": {
          "code": {
            "type": "string",
            "description": "CA65 assembly source code containing macros (optional if uri or path is provided)"
          },
          "uri": {
            "type": "string",
            "description": "Optional document URI to expand"
          },
          "path": {
            "type": "string",
            "description": "Optional workspace-relative file path or URI to expand"
          }
        }
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
          "path": {
            "type": "string",
            "description": "Optional workspace-relative folder or file prefix (e.g. 'snes-sfx-demo/X-GSU/IsoEngine/'); only definitions under it are returned"
          },
          "maxResults": {
            "type": "integer",
            "description": "Optional cap on allLocations (default 20); totalLocations always reports the full count"
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
            "description": "SuperFX assembly code to analyze (optional if uri or path is provided)"
          },
          "uri": {
            "type": "string",
            "description": "Optional document URI to analyze"
          },
          "path": {
            "type": "string",
            "description": "Optional workspace-relative file path or URI to analyze"
          },
          "function": {
            "type": "string",
            "description": "Optional function name to focus analysis on"
          }
        }
      }
      """;

  private static final String FIND_REFERENCES_SCHEMA =
      """
      {
        "type": "object",
        "properties": {
          "symbol": {
            "type": "string",
            "description": "Symbol identifier to find references for"
          },
          "path": {
            "type": "string",
            "description": "Optional workspace-relative folder or file prefix (e.g. 'snes-sfx-demo/X-GSU/IsoEngine/'); only references under it are returned"
          },
          "maxResults": {
            "type": "integer",
            "description": "Optional cap on references returned (default 50); totalReferences always reports the full count"
          },
          "includeDefinitions": {
            "type": "boolean",
            "description": "Whether to include definitions in the results (default false)"
          },
          "contextLines": {
            "type": "integer",
            "description": "When > 0, includes the source line text for each reference (default 0)"
          },
          "code": {
            "type": "string",
            "description": "Optional CA65 assembly code context"
          },
          "uri": {
            "type": "string",
            "description": "Optional document URI for scoped lookup"
          },
          "line": {
            "type": "integer",
            "description": "Optional cursor line number for scoped lookup"
          }
        },
        "required": ["symbol"]
      }
      """;

  private static final String PACK_TEXTURES_SCHEMA =
      """
      {
        "type": "object",
        "properties": {
          "project_path": {
            "type": "string",
            "description": "Path to the .sfxproj project file (workspace-relative or absolute)"
          },
          "action": {
            "type": "string",
            "enum": ["pack", "import", "validate"],
            "description": "Action to perform: 'pack' (default), 'import', or 'validate'"
          },
          "auto_pack": {
            "type": "boolean",
            "description": "Whether to auto-pack textures using shelf packing during 'pack'"
          },
          "output_dir": {
            "type": "string",
            "description": "Optional output directory to save exported assets"
          },
          "image_paths": {
            "type": "array",
            "items": { "type": "string" },
            "description": "List of bitmap image paths to import when action is 'import'"
          },
          "auto_quantize": {
            "type": "boolean",
            "description": "Whether to auto-quantize palette when importing images"
          },
          "snap_palette": {
            "type": "boolean",
            "description": "Whether to snap unmapped colors to nearest existing palette slots when importing images"
          }
        },
        "required": ["project_path"]
      }
      """;

  private final ObjectMapper objectMapper;
  private final ProjectService projectService;
  private final Path workspaceRoot;
  private final dev.secondsun.retrolsp.diagnostic.DiagnosticCoordinator diagnosticCoordinator;
  private final SfxInvariantAuditor invariantAuditor;
  private final SfxMacroExpanderTool macroExpander;
  private final SfxSymbolLookupTool symbolLookup;
  private final dev.secondsun.retrolsp.mcp.tools.SfxFindReferencesTool findReferences;
  private final SfxRegisterAnalysisTool registerAnalysis;
  private final SfxTexturePackerTool texturePacker;
  private final McpSyncServer syncServer;
  private final CountDownLatch stopLatch;
  private final WorkspaceFileWatcher fileWatcher;

  private final AtomicBoolean workspaceRootIndexed = new AtomicBoolean(false);
  private final Set<Path> indexedDirectories = ConcurrentHashMap.newKeySet();

  /** Constructs a standalone in-memory MCP server instance without transport (for tool testing). */
  /** Default constructor initializing with an empty in-memory ProjectService. */
  public RetroLspMcpServer() {
    this(new ProjectService(new FileService(), new SymbolService()), null, null);
  }

  /**
   * Constructs an in-memory MCP server with a custom ProjectService.
   *
   * @param projectService the project service managing files and symbols
   */
  public RetroLspMcpServer(ProjectService projectService) {
    this(projectService, null, null);
  }

  /**
   * Constructs an MCP server attached to the given transport and required workspace root.
   *
   * @param transport transport to communicate over (e.g. StdioServerTransport)
   * @param workspaceRoot canonical root directory boundary for workspace access
   */
  public RetroLspMcpServer(ServerMcpTransport transport, Path workspaceRoot) {
    this(
        new ProjectService(new FileService(), new SymbolService(), workspaceRoot),
        transport,
        workspaceRoot);
  }

  /**
   * Primary constructor initializing persistent services, workspace root boundary, background
   * watcher, and MCP tool registrations.
   *
   * @param projectService shared project service
   * @param transport transport to listen and serve on, or null for standalone
   * @param workspaceRoot initial workspace directory boundary, or null for none
   */
  public RetroLspMcpServer(
      ProjectService projectService, ServerMcpTransport transport, Path workspaceRoot) {
    this.objectMapper =
        new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    this.projectService = Objects.requireNonNull(projectService);
    this.workspaceRoot = workspaceRoot != null ? workspaceRoot.toAbsolutePath().normalize() : null;
    this.diagnosticCoordinator =
        new dev.secondsun.retrolsp.diagnostic.DiagnosticCoordinator(this.projectService);
    this.invariantAuditor = new SfxInvariantAuditor(this.projectService);
    this.macroExpander = new SfxMacroExpanderTool(this.projectService);
    this.symbolLookup = new SfxSymbolLookupTool(this.projectService);
    this.findReferences =
        new dev.secondsun.retrolsp.mcp.tools.SfxFindReferencesTool(this.projectService);
    this.registerAnalysis = new SfxRegisterAnalysisTool(this.projectService);
    this.texturePacker =
        this.workspaceRoot != null ? new SfxTexturePackerTool(this.workspaceRoot) : null;
    this.stopLatch = new CountDownLatch(1);

    if (transport != null) {
      Tool diagnosticsTool =
          new Tool(
              "sfx_audit_diagnostics",
              "Audits SuperFX assembly for unified diagnostics: syntax errors (block balancing, instruction/macro arity, duplicate symbols), hardware invariants (8-NOP settling before 'stop', 16-bit word aligned 'ldw'/'stw', absence of hardware 'MERGE' on FX3, modal lifetimes), and stack displacement invariants (return leak, divergent branches).",
              AUDIT_DIAGNOSTICS_SCHEMA);

      Tool auditTool =
          new Tool(
              "sfx_audit_invariants",
              "Alias for sfx_audit_diagnostics. Audits SuperFX assembly for unified diagnostics including hardware invariants, syntax errors, and stack displacement.",
              AUDIT_INVARIANTS_SCHEMA);

      Tool expandTool =
          new Tool(
              "sfx_expand_macro",
              "Expands CA65 and X-GSU macros (.macro, .define, function, for, stack operations, etc.) into a concrete instruction stream using the shared workspace FileService.",
              EXPAND_MACRO_SCHEMA);

      Tool lookupTool =
          new Tool(
              "sfx_lookup_symbol",
              "Use INSTEAD OF grep to find where an assembly label, X-GSU function, macro, struct, struct field or constant is DEFINED (.s/.i/.inc/.sgs). Returns file path, 1-based lineNumber, and the doc comment (In/Out/Clobbers). Real sources rank above test-fixture copies; use 'path' to scope to a folder. Does not find usages/references (use sfx_find_references), linker addresses (.sym/.dbg), or plain text - use grep for those.",
              LOOKUP_SYMBOL_SCHEMA);

      Tool findReferencesTool =
          new Tool(
              "sfx_find_references",
              "Use INSTEAD OF grep to find references/usages of a CA65/X-GSU assembly symbol across the workspace (.s/.i/.inc/.sgs). Understands function/call pairs, macro invocations, and scoped symbols. Real sources rank above test fixtures; use 'path' to scope to a folder.",
              FIND_REFERENCES_SCHEMA);

      Tool analyzeTool =
          new Tool(
              "sfx_analyze_registers",
              "Analyzes SuperFX register allocation, live-range intervals, CFG basic blocks, and register pressure.",
              ANALYZE_REGISTERS_SCHEMA);

      Tool packTexturesTool =
          new Tool(
              "sfx_pack_textures",
              "Use INSTEAD OF './tools/texture-packer.sh' to pack, update, or validate a SuperFX Texture Atlas (.sfxproj), exporting companion binaries (.bin, .pal), ca65 assembly index (.i), and layout PNG, or importing bitmap graphics.",
              PACK_TEXTURES_SCHEMA);

      ServerCapabilities capabilities = ServerCapabilities.builder().tools(true).build();

      this.syncServer =
          McpServer.sync(transport)
              .serverInfo("retro-lsp", "0.1.0-PROTOTYPE")
              .capabilities(capabilities)
              .tool(diagnosticsTool, args -> executeTool("sfx_audit_diagnostics", args))
              .tool(auditTool, args -> executeTool("sfx_audit_invariants", args))
              .tool(expandTool, args -> executeTool("sfx_expand_macro", args))
              .tool(lookupTool, args -> executeTool("sfx_lookup_symbol", args))
              .tool(findReferencesTool, args -> executeTool("sfx_find_references", args))
              .tool(analyzeTool, args -> executeTool("sfx_analyze_registers", args))
              .tool(packTexturesTool, args -> executeTool("sfx_pack_textures", args))
              .build();
    } else {
      this.syncServer = null;
    }

    WorkspaceFileWatcher watcher = null;
    if (this.workspaceRoot != null) {
      try {
        watcher = new WorkspaceFileWatcher(this.projectService, this.workspaceRoot);
      } catch (Exception e) {
        LOG.log(
            Level.WARNING,
            "Failed to initialize workspace file watcher for: " + this.workspaceRoot,
            e);
      }
    }
    this.fileWatcher = watcher;
    if (this.fileWatcher != null) {
      this.projectService.setDirectoryIndexedListener(
          dir -> this.fileWatcher.registerDirectory(dir));
    }
  }

  /**
   * Creates and starts a Retro-LSP MCP server communicating over standard input and output for the
   * given required workspace root.
   *
   * @param workspaceRoot required root directory of the workspace
   * @return a running RetroLspMcpServer instance
   */
  public static RetroLspMcpServer createStdio(Path workspaceRoot) {
    Objects.requireNonNull(workspaceRoot, "workspaceRoot is required when starting MCP server");
    ObjectMapper mapper =
        new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    StdioServerTransport transport = new StdioServerTransport(mapper);
    return new RetroLspMcpServer(transport, workspaceRoot);
  }

  /**
   * Configures standard Java logging (JUL) for the MCP server to output plain text logs (INFO level
   * and higher) to a temporary file in java.io.tmpdir with the naming format
   * retro-lsp-msp.date.time.log.
   *
   * @return the path to the configured log file, or null if configuration failed
   */
  public static synchronized Path configureLogging() {
    if (loggingConfigured.get()) {
      return currentLogFile;
    }
    return configureLogging(
        Path.of(System.getProperty("java.io.tmpdir", "/tmp")), LocalDateTime.now());
  }

  /**
   * Configures standard Java logging for the MCP server using a specified directory and timestamp.
   *
   * @param dir the directory to create log files in
   * @param timestamp the timestamp for filename generation
   * @return the path to the primary log file
   */
  public static synchronized Path configureLogging(Path dir, LocalDateTime timestamp) {
    try {
      Files.createDirectories(dir);

      if (currentFileHandler != null) {
        Logger.getLogger("").removeHandler(currentFileHandler);
        currentFileHandler.close();
        currentFileHandler = null;
      }

      String date = DateTimeFormatter.ofPattern("yyyy-MM-dd").format(timestamp);
      String time = DateTimeFormatter.ofPattern("HHmmss").format(timestamp);

      String fileName = "retro-lsp-msp." + date + "." + time + ".log";
      Path logPath = dir.resolve(fileName);

      FileHandler handler = new FileHandler(logPath.toAbsolutePath().toString(), true);
      handler.setLevel(Level.INFO);
      handler.setFormatter(new SimpleFormatter());

      Logger rootLogger = Logger.getLogger("");
      rootLogger.setLevel(Level.INFO);
      rootLogger.addHandler(handler);

      currentLogFile = logPath;
      currentFileHandler = handler;
      loggingConfigured.set(true);

      return logPath;
    } catch (Exception e) {
      System.err.println("Failed to configure MCP server file logging: " + e.getMessage());
      return null;
    }
  }

  /**
   * Returns the path to the currently active log file, if logging was configured.
   *
   * @return log file path or null
   */
  public static synchronized Path getLogFilePath() {
    return currentLogFile;
  }

  /** Resets logging configuration and closes active file handlers (primarily for testing). */
  public static synchronized void resetLoggingForTesting() {
    if (currentFileHandler != null) {
      Logger.getLogger("").removeHandler(currentFileHandler);
      currentFileHandler.close();
      currentFileHandler = null;
    }
    currentLogFile = null;
    loggingConfigured.set(false);
  }

  /**
   * Returns the underlying project service.
   *
   * @return the project service
   */
  public ProjectService getProjectService() {
    return projectService;
  }

  /**
   * Returns the configured workspace root boundary.
   *
   * @return the workspace root path
   */
  public Path getWorkspaceRoot() {
    return workspaceRoot;
  }

  /**
   * Returns the diagnostic coordinator.
   *
   * @return the diagnostic coordinator
   */
  public dev.secondsun.retrolsp.diagnostic.DiagnosticCoordinator getDiagnosticCoordinator() {
    return diagnosticCoordinator;
  }

  /**
   * Validates that the provided URI resides within the configured workspace root boundary. Throws
   * {@link SecurityException} if access is outside boundary.
   *
   * @param uri URI to validate
   */
  public void validateUriWithinWorkspace(URI uri) {
    projectService.validateUriWithinWorkspace(uri);
  }

  /**
   * Ensures that the target file or directory is indexed in the ProjectService on-demand.
   *
   * @param uri file URI to ensure indexed, or null to trigger lazy root indexing
   */
  public void ensureIndexed(URI uri) {
    if (workspaceRoot == null) {
      return;
    }
    if (uri != null && "file".equalsIgnoreCase(uri.getScheme())) {
      validateUriWithinWorkspace(uri);
      if (!uri.toString().startsWith("file:///snippet")) {
        try {
          Path path = Path.of(uri).toAbsolutePath().normalize();
          Path dir = Files.isDirectory(path) ? path : path.getParent();
          if (dir != null
              && dir.startsWith(workspaceRoot)
              && Files.isDirectory(dir)
              && indexedDirectories.add(dir)) {
            projectService.includeDir(dir.toUri());
            if (fileWatcher != null) {
              fileWatcher.registerDirectory(dir);
            }
          }
        } catch (Exception e) {
          LOG.log(Level.WARNING, "Failed to on-demand index directory for URI: " + uri, e);
        }
      }
    } else if (uri == null) {
      if (workspaceRootIndexed.compareAndSet(false, true)) {
        try {
          projectService.includeDir(workspaceRoot.toUri());
          indexedDirectories.add(workspaceRoot);
          if (fileWatcher != null) {
            fileWatcher.registerDirectory(workspaceRoot);
          }
        } catch (Exception e) {
          LOG.log(Level.WARNING, "Failed lazy indexing for workspace root: " + workspaceRoot, e);
        }
      }
    }
  }

  private URI resolveTargetUri(String uriStr, String pathStr, boolean usePathAsFileTarget) {
    String candidate = uriStr;
    if ((candidate == null || candidate.isBlank()) && usePathAsFileTarget) {
      candidate = pathStr;
    }
    if (candidate == null || candidate.isBlank()) {
      return null;
    }
    try {
      if (candidate.startsWith("file:")) {
        return URI.create(candidate);
      }
      Path path = Path.of(candidate);
      if (!path.isAbsolute()) {
        if (workspaceRoot != null) {
          path = workspaceRoot.resolve(path);
        } else {
          path = path.toAbsolutePath();
        }
      }
      return path.normalize().toUri();
    } catch (Exception e) {
      LOG.log(Level.FINE, "Failed to resolve URI candidate: " + candidate, e);
      return null;
    }
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
      boolean isFileTarget =
          switch (name) {
            case "sfx_audit_diagnostics",
                "sfx_audit_invariants",
                "sfx_expand_macro",
                "sfx_analyze_registers" ->
                true;
            default -> false;
          };

      String rawUriStr = (String) args.get("uri");
      String rawPathStr = (String) args.get("path");
      URI uri = resolveTargetUri(rawUriStr, rawPathStr, isFileTarget);
      String uriStr = uri != null ? uri.toString() : rawUriStr;

      if (uri != null) {
        validateUriWithinWorkspace(uri);
        ensureIndexed(uri);
      } else {
        ensureIndexed(null);
      }

      if (!isFileTarget && rawPathStr != null && !rawPathStr.isBlank() && workspaceRoot != null) {
        try {
          Path filterPath = workspaceRoot.resolve(rawPathStr);
          Path dir = Files.isDirectory(filterPath) ? filterPath : filterPath.getParent();
          if (dir != null && Files.isDirectory(dir) && indexedDirectories.add(dir)) {
            projectService.includeDir(dir.toUri());
            if (fileWatcher != null) {
              fileWatcher.registerDirectory(dir);
            }
          }
        } catch (Exception e) {
          LOG.log(Level.FINE, "Failed lazy indexing for path filter: " + rawPathStr, e);
        }
      }

      Object resultData =
          switch (name) {
            case "sfx_audit_diagnostics", "sfx_audit_invariants" -> {
              String code = (String) args.get("code");
              yield diagnosticCoordinator.auditResult(code, uriStr);
            }
            case "sfx_expand_macro" -> {
              String code = (String) args.get("code");
              yield macroExpander.expand(code, uriStr);
            }
            case "sfx_lookup_symbol" -> {
              String symbol = (String) args.get("symbol");
              String code = (String) args.get("code");
              Number lineNum = (Number) args.get("line");
              Integer line = lineNum != null ? lineNum.intValue() : null;
              String pathFilter = rawPathStr;
              Number maxNum = (Number) args.get("maxResults");
              Integer maxResults = maxNum != null ? maxNum.intValue() : null;
              yield symbolLookup.lookup(symbol, code, uriStr, line, pathFilter, maxResults);
            }
            case "sfx_find_references" -> {
              String symbol = (String) args.get("symbol");
              String code = (String) args.get("code");
              Number lineNum = (Number) args.get("line");
              Integer line = lineNum != null ? lineNum.intValue() : null;
              String pathFilter = rawPathStr;
              Number maxNum = (Number) args.get("maxResults");
              Integer maxResults = maxNum != null ? maxNum.intValue() : null;
              Boolean includeDefs = (Boolean) args.get("includeDefinitions");
              boolean includeDefinitions = includeDefs != null && includeDefs;
              Number ctxNum = (Number) args.get("contextLines");
              Integer contextLines = ctxNum != null ? ctxNum.intValue() : 0;
              yield findReferences.find(
                  symbol,
                  code,
                  uriStr,
                  line,
                  pathFilter,
                  maxResults,
                  includeDefinitions,
                  contextLines);
            }
            case "sfx_analyze_registers" -> {
              String code = (String) args.get("code");
              String function = (String) args.get("function");
              yield registerAnalysis.analyze(code, uriStr, function);
            }
            case "sfx_pack_textures" -> {
              if (texturePacker == null) {
                throw new IllegalStateException(
                    "Workspace root is required to execute texture packer");
              }
              String projectPath = (String) args.get("project_path");
              String action = (String) args.get("action");
              Boolean autoPack = (Boolean) args.get("auto_pack");
              String outputDir = (String) args.get("output_dir");
              @SuppressWarnings("unchecked")
              List<String> imagePaths = (List<String>) args.get("image_paths");
              Boolean autoQuantize = (Boolean) args.get("auto_quantize");
              Boolean snapPalette = (Boolean) args.get("snap_palette");
              yield texturePacker.run(
                  projectPath, action, autoPack, outputDir, imagePaths, autoQuantize, snapPalette);
            }
            default -> throw new IllegalArgumentException("Unknown tool: " + name);
          };

      String jsonText =
          objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(resultData);
      return new CallToolResult(List.of(new TextContent(jsonText)), false);
    } catch (SecurityException se) {
      LOG.log(Level.WARNING, "Security boundary violation: " + se.getMessage());
      return new CallToolResult(List.of(new TextContent(se.getMessage())), true);
    } catch (Exception e) {
      LOG.log(Level.WARNING, "Error executing MCP tool '" + name + "': " + e.getMessage(), e);
      return new CallToolResult(
          List.of(new TextContent("Error executing tool '" + name + "': " + e.getMessage())), true);
    }
  }

  /**
   * Waits until the server is closed.
   *
   * @throws InterruptedException if interrupted while awaiting server shutdown
   */
  public void await() throws InterruptedException {
    stopLatch.await();
  }

  @Override
  public void close() {
    if (fileWatcher != null) {
      try {
        fileWatcher.close();
      } catch (Exception e) {
        LOG.log(Level.FINE, "Error closing file watcher: " + e.getMessage(), e);
      }
    }
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
