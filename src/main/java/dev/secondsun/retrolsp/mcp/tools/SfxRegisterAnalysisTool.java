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

  public static final int TOTAL_SUPERFX_REGISTERS = 16;

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

  public record BlockInfo(
      String id, int startLine, int endLine, List<String> entrances, List<String> exits) {}

  public record FunctionInfo(
      String name, int startLine, int endLine, Map<String, String> allocatedMap) {}

  public record RegisterAnalysisResult(
      int maxRegisterPressure,
      int availableRegisters,
      String pressureStatus,
      List<IntervalInfo> intervals,
      Map<String, String> allocations,
      List<BlockInfo> blocks,
      List<FunctionInfo> functions) {}

  public RegisterAnalysisResult analyze(String code, String functionFilter) {
    if (code == null || code.isBlank()) {
      return new RegisterAnalysisResult(
          0, TOTAL_SUPERFX_REGISTERS, "OPTIMAL", List.of(), Map.of(), List.of(), List.of());
    }

    CodeAnalyzer analyzer = new CodeAnalyzer();
    FileAnalysisResultDto dto = analyzer.analyze(code, "source.sgs");

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
