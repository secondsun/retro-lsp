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

  @Test
  public void testLocationHasOneBasedLineAndCanonicalUri() {
    String code = "nop\nmyLabel:\n  nop\n";
    SymbolLookupResult result = tool.lookup("myLabel", code, "file:///test.s", null);
    assertTrue(result.found());
    assertEquals(result.location().line() + 1, result.location().lineNumber());
    assertTrue(result.location().uri().startsWith("file:///"), result.location().uri());
    assertEquals(result.allLocations().size(), result.totalLocations());
  }

  @Test
  public void testRealSourceOutranksFixtureCopyAndPathFilterAndCap(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
    java.nio.file.Path real = root.resolve("demo/common");
    java.nio.file.Path fixture = root.resolve("lib/src/test/resources/common");
    java.nio.file.Files.createDirectories(real);
    java.nio.file.Files.createDirectories(fixture);
    // Fixture path sorts first alphabetically and must still rank last.
    java.nio.file.Files.writeString(fixture.resolve("var.i"), "dupSym = 1\n");
    java.nio.file.Files.writeString(real.resolve("var.i"), "\n\ndupSym = 2\n");

    var ps =
        new dev.secondsun.retro.util.ProjectService(
            new dev.secondsun.retro.util.FileService(),
            new dev.secondsun.retro.util.SymbolService(),
            root);
    ps.includeDir(root.toUri());
    var t = new SfxSymbolLookupTool(ps);

    SymbolLookupResult r = t.lookup("dupSym", null, null, null);
    assertEquals(2, r.totalLocations());
    assertEquals("demo/common/var.i", r.location().path());
    assertEquals("demo/common/var.i", r.allLocations().get(0).path());

    SymbolLookupResult scoped = t.lookup("dupSym", null, null, null, "lib/", null);
    assertEquals(1, scoped.totalLocations());

    SymbolLookupResult capped = t.lookup("dupSym", null, null, null, null, 1);
    assertEquals(1, capped.allLocations().size());
    assertEquals(2, capped.totalLocations());
  }
}
