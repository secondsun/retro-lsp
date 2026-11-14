package dev.secondsun.retrolsp.mcp;

import static org.junit.jupiter.api.Assertions.*;

import dev.secondsun.retrolsp.mcp.tools.SfxRegisterAnalysisTool;
import dev.secondsun.retrolsp.mcp.tools.SfxRegisterAnalysisTool.RegisterAnalysisResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class SfxRegisterAnalysisToolTest {

  private SfxRegisterAnalysisTool tool;

  @BeforeEach
  public void setUp() {
    tool = new SfxRegisterAnalysisTool();
  }

  @Test
  public void testAnalyzeRegistersInFunction() {
    String code =
        """
        function calculate a, b : res
            register temp
            with a
            add b
            to temp
            move r0
            with temp
            add #1
            to res
            move r0
            return
        endfunction
        """;
    RegisterAnalysisResult result = tool.analyze(code, null);
    assertNotNull(result);
    assertTrue(result.maxRegisterPressure() > 0);
    assertEquals(16, result.availableRegisters());
    assertNotNull(result.intervals());
    assertFalse(result.intervals().isEmpty());
    assertNotNull(result.allocations());
    assertNotNull(result.blocks());
    assertNotNull(result.functions());
    assertEquals(1, result.functions().size());
    assertEquals("calculate", result.functions().get(0).name());
    assertEquals(1, result.functions().get(0).startLine());
    assertEquals(12, result.functions().get(0).endLine());
    for (var interval : result.intervals()) {
      assertTrue(interval.startLine() >= 1, "Interval startLine must be 1-based");
      assertTrue(interval.endLine() >= interval.startLine());
    }
    assertFalse(result.blocks().isEmpty());
    var blockIds =
        result.blocks().stream().map(b -> b.id()).collect(java.util.stream.Collectors.toSet());
    for (var block : result.blocks()) {
      assertTrue(block.id().startsWith("block-"), "Expected block-N ID, got: " + block.id());
      assertTrue(block.startLine() >= 1, "Block startLine must be 1-based");
      assertTrue(block.endLine() >= block.startLine());
      assertTrue(blockIds.containsAll(block.entrances()));
      assertTrue(blockIds.containsAll(block.exits()));
    }
  }

  @Test
  public void testFunctionFilterScopesFunctionsIntervalsBlocksAndAllocations() {
    String code =
        """
        function first_fn a : out1
            register tmp1
            with a
            add #1
            to tmp1
            move r0
            with tmp1
            bne @skip
            nop
            inc tmp1
        @skip:
            move out1, tmp1
            return out1
        endfunction

        function second_fn b : out2
            register tmp2
            with b
            add #2
            to tmp2
            move r0
            move out2, tmp2
            return out2
        endfunction
        """;
    RegisterAnalysisResult all = tool.analyze(code, null);
    assertEquals(2, all.functions().size());
    int totalBlocks = all.blocks().size();
    assertTrue(totalBlocks >= 3);

    RegisterAnalysisResult filtered = tool.analyze(code, "second_fn");
    assertEquals(1, filtered.functions().size());
    assertEquals("second_fn", filtered.functions().get(0).name());
    assertEquals(16, filtered.functions().get(0).startLine());
    assertEquals(24, filtered.functions().get(0).endLine());
    assertTrue(
        filtered.intervals().stream().allMatch(i -> "second_fn".equals(i.functionScope())),
        "All intervals must belong to second_fn");
    assertFalse(filtered.blocks().isEmpty());
    assertTrue(
        filtered.blocks().size() < totalBlocks,
        "Blocks must be filtered by functionFilter ('second_fn')");
    for (var b : filtered.blocks()) {
      assertTrue(b.startLine() >= 16 && b.endLine() <= 24);
    }
    assertFalse(filtered.allocations().containsKey("tmp1"));
    assertTrue(filtered.allocations().containsKey("tmp2"));
  }

  @Test
  public void testAnalyzeEmptyCode() {
    RegisterAnalysisResult result = tool.analyze("", null);
    assertNotNull(result);
    assertEquals(0, result.maxRegisterPressure());
    assertEquals("OPTIMAL", result.pressureStatus());
    assertTrue(result.intervals().isEmpty());
  }
}
