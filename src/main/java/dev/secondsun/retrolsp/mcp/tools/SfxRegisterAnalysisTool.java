package dev.secondsun.retrolsp.mcp.tools;

import dev.secondsun.retro.util.DefaultWorkspaceIndex;
import dev.secondsun.retro.util.WorkspaceIndex;
import dev.secondsun.retro.util.WorkspaceSnapshot;
import dev.secondsun.retro.util.vo.TokenizedFile;
import dev.secondsun.sfxoptimizer.Constants;
import dev.secondsun.sfxoptimizer.Interval;
import dev.secondsun.sfxoptimizer.IntervalKey;
import dev.secondsun.sfxoptimizer.analysis.CfgBlock;
import dev.secondsun.sfxoptimizer.analysis.FunctionAnalysis;
import dev.secondsun.sfxoptimizer.analysis.MainAnalysis;
import dev.secondsun.sfxoptimizer.analysis.ProgramAnalysisResult;
import dev.secondsun.sfxoptimizer.analysis.SfxProgramAnalyzer;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Static analysis tool that builds SuperFX Control Flow Graphs (CFG), live-range intervals, and
 * computes register pressure and allocations using {@link SfxProgramAnalyzer}.
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
   * @param pressureStatus pressure classification status (OPTIMAL, HIGH, SPILL_RISK)
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

  private final WorkspaceIndex workspaceIndex;

  /** Default constructor initializing with an empty in-memory {@link DefaultWorkspaceIndex}. */
  public SfxRegisterAnalysisTool() {
    this(new DefaultWorkspaceIndex());
  }

  /**
   * Constructs tool with the provided workspace index.
   *
   * @param workspaceIndex workspace index for resolving files and cross-file call signatures
   */
  public SfxRegisterAnalysisTool(WorkspaceIndex workspaceIndex) {
    this.workspaceIndex = Objects.requireNonNull(workspaceIndex, "workspaceIndex");
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
    URI uri = (uriStr != null && !uriStr.isBlank()) ? URI.create(uriStr) : null;
    boolean hasInlineCode = code != null && !code.isBlank();

    if (!hasInlineCode && uri == null) {
      return emptyResult();
    }

    WorkspaceSnapshot snapshot;
    TokenizedFile tf;
    if (hasInlineCode) {
      snapshot = workspaceIndex.snapshotFor(uri, code);
      URI targetUri = uri != null ? uri : WorkspaceSnapshot.TRANSIENT_SNIPPET_URI;
      tf = snapshot.file(targetUri);
    } else {
      tf = workspaceIndex.resolveFile(uri, null);
      snapshot = workspaceIndex.snapshot();
    }

    if (tf == null || tf.isEmpty()) {
      if (uri != null && !hasInlineCode) {
        throw new IllegalArgumentException("File not found in workspace: " + uri);
      }
      return emptyResult();
    }

    ProgramAnalysisResult analysis = SfxProgramAnalyzer.analyze(tf, snapshot);
    String filter =
        (functionFilter != null && !functionFilter.isBlank()) ? functionFilter.trim() : null;

    List<FunctionInfo> functions = new ArrayList<>();
    List<IntervalInfo> intervals = new ArrayList<>();
    Map<String, String> allocations = new LinkedHashMap<>();

    MainAnalysis main = analysis.getMain();
    if (main != null && (filter == null || "(main)".equalsIgnoreCase(filter))) {
      Map<String, String> mainAlloc = formatAllocatedMap(main.getAllocatedMap());
      functions.add(
          new FunctionInfo("(main)", main.getStartLine() + 1, main.getEndLine() + 1, mainAlloc));
      allocations.putAll(mainAlloc);
      intervals.addAll(
          buildScopeIntervals("(main)", main.getIntervals(), mainAlloc, main.getInterferences()));
    }

    List<FunctionAnalysis> sortedFns =
        analysis.getFunctions().stream()
            .sorted(Comparator.comparingInt(FunctionAnalysis::getStartLine))
            .toList();
    for (FunctionAnalysis fn : sortedFns) {
      if (filter != null && !filter.equalsIgnoreCase(fn.getName())) {
        continue;
      }
      Map<String, String> fnAlloc = formatAllocatedMap(fn.getAllocatedMap());
      functions.add(
          new FunctionInfo(fn.getName(), fn.getStartLine() + 1, fn.getEndLine() + 1, fnAlloc));
      allocations.putAll(fnAlloc);
      intervals.addAll(
          buildScopeIntervals(fn.getName(), fn.getIntervals(), fnAlloc, fn.getInterferences()));
    }

    List<BlockInfo> blocks = new ArrayList<>();
    for (CfgBlock block : analysis.getBlocks()) {
      if (filter != null && !filter.equalsIgnoreCase(block.getScopeName())) {
        continue;
      }
      blocks.add(
          new BlockInfo(
              block.getId(),
              block.getStartLine() + 1,
              block.getEndLine() + 1,
              block.getEntrances() != null ? List.copyOf(block.getEntrances()) : List.of(),
              block.getExits() != null ? List.copyOf(block.getExits()) : List.of()));
    }

    int maxPressure = computeMaxPressure(intervals, analysis.getTotalLines());
    String status = classifyPressure(maxPressure);

    return new RegisterAnalysisResult(
        maxPressure,
        TOTAL_SUPERFX_REGISTERS,
        status,
        List.copyOf(intervals),
        Map.copyOf(allocations),
        List.copyOf(blocks),
        List.copyOf(functions));
  }

  private static RegisterAnalysisResult emptyResult() {
    return new RegisterAnalysisResult(
        0, TOTAL_SUPERFX_REGISTERS, "OPTIMAL", List.of(), Map.of(), List.of(), List.of());
  }

  private static Map<String, String> formatAllocatedMap(Map<String, Constants.Register> raw) {
    if (raw == null || raw.isEmpty()) {
      return Map.of();
    }
    Map<String, String> result = new LinkedHashMap<>();
    for (Map.Entry<String, Constants.Register> entry : raw.entrySet()) {
      if (entry.getKey() != null && entry.getValue() != null) {
        result.put(entry.getKey(), entry.getValue().name());
      }
    }
    return Map.copyOf(result);
  }

  private static List<IntervalInfo> buildScopeIntervals(
      String scopeName,
      Map<IntervalKey, Interval> rawIntervals,
      Map<String, String> allocatedMap,
      Map<IntervalKey, Set<IntervalKey>> interferences) {
    if (rawIntervals == null || rawIntervals.isEmpty()) {
      return List.of();
    }
    List<IntervalInfo> list = new ArrayList<>();
    for (Interval interval : rawIntervals.values()) {
      if (interval == null || !interval.used() || interval.getEnd() < interval.getStart()) {
        continue;
      }
      IntervalKey key = interval.getKey();
      String keyName = keyToString(key);
      boolean isRegister = key instanceof IntervalKey.RegisterKey;
      String registerName =
          isRegister ? ((IntervalKey.RegisterKey) key).getRegister().name() : null;
      String allocatedRegister =
          isRegister
              ? registerName
              : allocatedMap.getOrDefault(
                  keyName.toLowerCase(Locale.ROOT), allocatedMap.get(keyName));
      boolean isSpill = "SPILL".equals(allocatedRegister);

      Set<IntervalKey> conflictKeys =
          interferences != null ? interferences.getOrDefault(key, Set.of()) : Set.of();
      List<String> conflictNames =
          conflictKeys.stream().map(SfxRegisterAnalysisTool::keyToString).sorted().toList();

      list.add(
          new IntervalInfo(
              keyName,
              scopeName,
              isRegister,
              registerName,
              interval.getStart() + 1,
              interval.getEnd() + 1,
              allocatedRegister,
              isSpill,
              conflictNames));
    }
    list.sort(
        Comparator.comparing((IntervalInfo i) -> !i.isRegister())
            .thenComparingInt(IntervalInfo::startLine)
            .thenComparing(IntervalInfo::key));
    return list;
  }

  private static String keyToString(IntervalKey key) {
    if (key instanceof IntervalKey.RegisterKey rk) {
      return rk.getRegister().name();
    }
    if (key instanceof IntervalKey.LabelKey lk) {
      return lk.getLabel();
    }
    return key.toString();
  }

  private static int computeMaxPressure(List<IntervalInfo> intervals, int totalLines) {
    if (intervals.isEmpty() || totalLines <= 0) {
      return 0;
    }
    int maxPressure = 0;
    for (int line = 1; line <= totalLines; line++) {
      int count = 0;
      for (IntervalInfo interval : intervals) {
        if (interval.startLine() <= line && interval.endLine() >= line) {
          count++;
        }
      }
      if (count > maxPressure) {
        maxPressure = count;
      }
    }
    return maxPressure;
  }

  private static String classifyPressure(int maxPressure) {
    if (maxPressure <= 8) {
      return "OPTIMAL";
    }
    if (maxPressure <= 14) {
      return "HIGH";
    }
    return "SPILL_RISK";
  }
}
