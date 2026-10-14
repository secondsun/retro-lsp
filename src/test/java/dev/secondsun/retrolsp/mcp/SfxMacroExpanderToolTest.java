package dev.secondsun.retrolsp.mcp;

import static org.junit.jupiter.api.Assertions.*;

import dev.secondsun.retrolsp.mcp.tools.SfxMacroExpanderTool;
import dev.secondsun.retrolsp.mcp.tools.SfxMacroExpanderTool.MacroExpandResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class SfxMacroExpanderToolTest {

  private SfxMacroExpanderTool tool;

  @BeforeEach
  public void setUp() {
    tool = new SfxMacroExpanderTool();
  }

  @Test
  public void testExpandBuiltinStackPushPop() {
    String code =
        """
        gsu_stack_push r1
        gsu_stack_pop r2
        """;
    MacroExpandResult result = tool.expand(code);
    assertNotNull(result);
    assertNotNull(result.expandedCode());
    // gsu_stack_push expands into stw (r10), inc r10, inc r10
    assertTrue(result.expandedCode().contains("stw (r10)"));
    assertTrue(result.expandedCode().contains("inc r10"));
    // gsu_stack_pop expands into dec r10, dec r10, ldw (r10)
    assertTrue(result.expandedCode().contains("dec r10"));
    assertTrue(result.expandedCode().contains("ldw (r10)"));
    assertTrue(result.expandedLineCount() > result.originalLineCount());
  }

  @Test
  public void testExpandForLoop() {
    String code =
        """
        for 4
            nop
        endfor
        """;
    MacroExpandResult result = tool.expand(code);
    assertNotNull(result);
    // for expands with loop register setup / backuploop
    assertTrue(
        result.expandedCode().contains("r12")
            || result.expandedCode().contains("r13")
            || result.expandedCode().contains("loop"));
  }

  @Test
  public void testExpandCustomMacro() {
    String code =
        """
        .macro doubleInc reg
            inc reg
            inc reg
        .endmacro

        doubleInc r1
        """;
    MacroExpandResult result = tool.expand(code);
    assertNotNull(result);
    assertTrue(result.expandedCode().contains("inc r1"));
  }

  @Test
  public void testExpandEmptyCode() {
    MacroExpandResult result = tool.expand("");
    assertNotNull(result);
    assertEquals("", result.expandedCode());
    assertEquals(0, result.expandedLineCount());
  }
}
