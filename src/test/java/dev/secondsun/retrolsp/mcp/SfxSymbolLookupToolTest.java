package dev.secondsun.retrolsp.mcp;

import static org.junit.jupiter.api.Assertions.*;

import dev.secondsun.retrolsp.mcp.tools.SfxSymbolLookupTool;
import dev.secondsun.retrolsp.mcp.tools.SfxSymbolLookupTool.SymbolLookupResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class SfxSymbolLookupToolTest {

  private SfxSymbolLookupTool tool;

  @BeforeEach
  public void setUp() {
    tool = new SfxSymbolLookupTool();
  }

  @Test
  public void testLookupFunctionDefinitionAndDoc() {
    String code =
        """
        ; Documenting myCoolFunction
        ; Multi-line documentation
        function myCoolFunction param1 : result
            return result
        endfunction
        """;
    SymbolLookupResult result = tool.lookup("myCoolFunction", code, "file:///test.s", null);
    assertNotNull(result);
    assertTrue(result.found());
    assertEquals("myCoolFunction", result.symbol());
    assertNotNull(result.location());
    assertEquals(2, result.location().line());
    assertNotNull(result.documentation());
    assertTrue(result.documentation().contains("Documenting myCoolFunction"));
  }

  @Test
  public void testLookupVariableInScope() {
    String code =
        """
        function compute
            register tempVar
            with tempVar
            inc r0
            return
        endfunction
        """;
    SymbolLookupResult result = tool.lookup("tempVar", code, "file:///test.s", 2);
    assertNotNull(result);
    assertTrue(result.found());
    assertEquals("tempVar", result.symbol());
    assertEquals("compute", result.scope());
  }

  @Test
  public void testLookupUnknownSymbol() {
    String code = "nop";
    SymbolLookupResult result = tool.lookup("nonExistent", code, "file:///test.s", null);
    assertNotNull(result);
    assertFalse(result.found());
    assertNull(result.location());
  }
}
