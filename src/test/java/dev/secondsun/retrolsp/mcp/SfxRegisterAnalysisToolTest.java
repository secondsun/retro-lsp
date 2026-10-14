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
