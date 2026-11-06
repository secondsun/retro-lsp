package dev.secondsun.retrolsp.mcp.tools;

import dev.secondsun.sfxoptimizer.viewer.CodeAnalyzer;
import dev.secondsun.sfxoptimizer.viewer.CodeBlockDto;
import dev.secondsun.sfxoptimizer.viewer.FileAnalysisResultDto;
import dev.secondsun.sfxoptimizer.viewer.FunctionScopeDto;
import dev.secondsun.sfxoptimizer.viewer.IntervalSpanDto;
import java.util.*;

/**
 * Static analysis tool that builds SuperFX Control Flow Graphs (CFG), live-range intervals, and
 * computes register pressure and allocations using sfx-optimizer.
 */
public class SfxRegisterAnalysisTool {

  /** Total physical registers available on the SuperFX (GSU / FX3) chip. */
  public static final int TOTAL_SUPERFX_REGISTERS = 16;

  /**
   * Live interval information for a variable or physical register.
   *
   * @param key identifier for the variable or interval
   * @param functionScope enclosing function scope name
   * @param isRegister whether this interval represents a fixed physical register
   * @param registerName physical register name if fixed
   * @param startLine 1-based start line of the live range
   * @param endLine 1-based end line of the live range
   * @param allocatedRegister physical register assigned by allocator
   * @param isSpill whether the variable was spilled to stack
   * @param interferences list of conflicting variable keys
   */
  public record IntervalInfo(
      String key,
      String functionScope,
      boolean isRegister,
      String registerName,
      int startLine,
      int endLine,
      String allocatedRegister,
      boolean isSpill,
      List<String> interferences) {}

  /**
   * Basic block information in the Control Flow Graph.
   *
   * @param id block identifier
   * @param startLine 1-based start line
   * @param endLine 1-based end line
   * @param entrances list of predecessor block IDs
   * @param exits list of successor block IDs
   */
  public record BlockInfo(
      String id, int startLine, int endLine, List<String> entrances, List<String> exits) {}

  /**
   * Function scope analysis information.
   *
   * @param name function scope name
   * @param startLine 1-based start line
   * @param endLine 1-based end line
   * @param allocatedMap register allocation mapping for variables in this function
   */
  public record FunctionInfo(
      String name, int startLine, int endLine, Map<String, String> allocatedMap) {}

  /**
   * Complete register analysis result containing pressure, live intervals, and CFG blocks.
   *
   * @param maxRegisterPressure peak register pressure across all program points
   * @param availableRegisters total physical registers available (16)
   * @param pressureStatus pressure classification status (OPTIMAL, HIGH, CRITICAL)
   * @param intervals list of live-range intervals
   * @param allocations map of variable names to assigned physical registers
   * @param blocks list of CFG basic blocks
   * @param functions list of analyzed function scopes
   */
  public record RegisterAnalysisResult(
      int maxRegisterPressure,
      int availableRegisters,
      String pressureStatus,
      List<IntervalInfo> intervals,
      Map<String, String> allocations,
      List<BlockInfo> blocks,
      List<FunctionInfo> functions) {}

  private final dev.secondsun.retro.util.ProjectService projectService;

  /** Default constructor initializing with an empty in-memory ProjectService. */
  public SfxRegisterAnalysisTool() {
    this(
        new dev.secondsun.retro.util.ProjectService(
            new dev.secondsun.retro.util.FileService(),
            new dev.secondsun.retro.util.SymbolService()));
  }

  /**
   * Constructs tool with the provided project service.
   *
   * @param projectService project service for resolving files and symbols
   */
  public SfxRegisterAnalysisTool(dev.secondsun.retro.util.ProjectService projectService) {
    this.projectService = Objects.requireNonNull(projectService);
  }

  /**
   * Analyzes register allocation and pressure in raw assembly code.
   *
   * @param code raw assembly code string
   * @param functionFilter optional function name filter
   * @return register analysis result
   */
  public RegisterAnalysisResult analyze(String code, String functionFilter) {
    return analyze(code, null, functionFilter);
  }

  /**
   * Analyzes register allocation and pressure in assembly code or workspace document URI.
   *
   * @param code raw assembly code string or null
   * @param uriStr workspace document URI string or null
   * @param functionFilter optional function name filter
   * @return register analysis result
   */
  public RegisterAnalysisResult analyze(String code, String uriStr, String functionFilter) {
    java.net.URI uri = uriStr != null ? java.net.URI.create(uriStr) : null;
    dev.secondsun.retro.util.vo.TokenizedFile tf = projectService.resolveFile(uri, code);

    if (tf == null || tf.isEmpty()) {
      if (uri != null && (code == null || code.isBlank())) {
        throw new IllegalArgumentException("File not found in workspace: " + uri);
      }
      return new RegisterAnalysisResult(
          0, TOTAL_SUPERFX_REGISTERS, "OPTIMAL", List.of(), Map.of(), List.of(), List.of());
    }

    List<String> rawLines = tf.getRawLines();
    String sourceCode =
        (rawLines != null && !rawLines.isEmpty()) ? String.join("\n", rawLines) : "";

    String fileName = uri != null ? (new java.io.File(uri.getPath()).getName()) : "source.sgs";
    CodeAnalyzer analyzer = new CodeAnalyzer();
    FileAnalysisResultDto dto = analyzer.analyze(sourceCode, fileName);

    List<IntervalSpanDto> sourceIntervals = dto.getIntervals();
    if (functionFilter != null && !functionFilter.isBlank()) {
      sourceIntervals =
          sourceIntervals.stream()
              .filter(i -> functionFilter.equalsIgnoreCase(i.getFunctionScope()))
              .toList();
    }

    List<IntervalInfo> intervals = new ArrayList<>();
    int maxPressure = 0;
    int totalLines = dto.getTotalLines();

    for (IntervalSpanDto span : sourceIntervals) {
      intervals.add(
          new IntervalInfo(
              span.getKeyName(),
              span.getFunctionScope(),
              span.isRegister(),
              span.getRegisterName(),
              span.getStartLine(),
              span.getEndLine(),
              span.getAllocatedRegister(),
              span.isSpill(),
              span.getInterferences() != null ? span.getInterferences() : List.of()));
    }

    // Calculate maximum register pressure across lines
    for (int line = 0; line <= totalLines; line++) {
      final int currentLine = line;
      long count =
          intervals.stream()
              .filter(i -> i.startLine() <= currentLine && i.endLine() >= currentLine)
              .count();
      if (count > maxPressure) {
        maxPressure = (int) count;
      }
    }

    String status;
    if (maxPressure <= 8) {
      status = "OPTIMAL";
    } else if (maxPressure <= 14) {
      status = "HIGH";
    } else {
      status = "SPILL_RISK";
    }

    List<BlockInfo> blocks = new ArrayList<>();
    for (CodeBlockDto b : dto.getBlocks()) {
      blocks.add(
          new BlockInfo(
              b.getId(),
              b.getStartLine(),
              b.getEndLine(),
              b.getEntrances() != null ? b.getEntrances() : List.of(),
              b.getExits() != null ? b.getExits() : List.of()));
    }

    List<FunctionInfo> functions = new ArrayList<>();
    for (FunctionScopeDto f : dto.getFunctions()) {
      if (functionFilter != null && !functionFilter.equalsIgnoreCase(f.getName())) {
        continue;
      }
      functions.add(
          new FunctionInfo(
              f.getName(),
              f.getStartLine(),
              f.getEndLine(),
              f.getAllocatedMap() != null ? f.getAllocatedMap() : Map.of()));
    }

    Map<String, String> allocations =
        dto.getAllocatedMap() != null ? dto.getAllocatedMap() : Map.of();

    return new RegisterAnalysisResult(
        maxPressure, TOTAL_SUPERFX_REGISTERS, status, intervals, allocations, blocks, functions);
  }
}
