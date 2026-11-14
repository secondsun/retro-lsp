package dev.secondsun.retrolsp.mcp;

import static org.junit.jupiter.api.Assertions.*;

import dev.secondsun.retro.util.DefaultWorkspaceIndex;
import dev.secondsun.retro.util.WorkspaceIndex;
import dev.secondsun.retrolsp.mcp.tools.SfxFindReferencesTool;
import dev.secondsun.retrolsp.mcp.tools.SfxFindReferencesTool.ReferenceLocation;
import dev.secondsun.retrolsp.mcp.tools.SfxFindReferencesTool.ReferenceResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class SfxFindReferencesToolTest {

  private SfxFindReferencesTool tool;
  private WorkspaceIndex projectService;

  @BeforeEach
  public void setUp() {
    projectService = new DefaultWorkspaceIndex();
    tool = new SfxFindReferencesTool(projectService);
  }

  @Test
  public void testFindReferencesBasicAndKinds() {
    String code =
        """
        function doMath
            stw (storeTarget)
            ldw (readSource)
            call doMath
            return
        endfunction
        """;
    ReferenceResult res = tool.find("doMath", code, "file:///math.s", null, null, null, false, 1);
    assertNotNull(res);
    assertEquals("doMath", res.symbol());
    assertEquals(1, res.totalReferences());
    assertEquals(1, res.references().size());
    var ref = res.references().get(0);
    assertEquals("call", ref.kind());
    assertEquals(3, ref.line());
    assertEquals(4, ref.lineNumber());
    assertNotNull(ref.text());
    assertTrue(ref.text().contains("call doMath"));

    // With definitions
    ReferenceResult withDefs =
        tool.find("doMath", code, "file:///math.s", null, null, null, true, 0);
    assertEquals(2, withDefs.totalReferences());
    assertEquals("definition", withDefs.references().get(0).kind());
    assertNull(withDefs.references().get(0).text()); // contextLines == 0
  }

  @Test
  public void testSymbolOnlyInCommentOrStringIgnored() {
    String code =
        """
        ; Comment mentioning testSym
        .asciiz "testSym in literal"
        nop
        """;
    ReferenceResult res =
        tool.find("testSym", code, "file:///comments.s", null, null, null, false, 0);
    assertEquals(0, res.totalReferences());
    assertTrue(res.references().isEmpty());
  }

  @Test
  public void testFunctionAndFunctionLabelAlias() {
    String code =
        """
        function mySub
            bra mySub_function
            call mySub
            return
        endfunction
        """;
    ReferenceResult res = tool.find("mySub", code, "file:///sub.s", null, null, null, false, 0);
    assertEquals(2, res.totalReferences());
  }

  @Test
  public void testDisambiguationAndAmbiguityFlag(@TempDir Path root) throws Exception {
    Path file = root.resolve("structs.s");
    Files.writeString(
        file,
        """
        .struct structA
            drop_ns .byte
        .endstruct

        .struct structB
            drop_ns .word
        .endstruct

        main:
            iwt r1, #structA::drop_ns
            iwt r2, #structB::drop_ns
            nop
        """);

    var ps = new DefaultWorkspaceIndex(root);
    ps.includeDir(root.toUri());
    var t = new SfxFindReferencesTool(ps);

    // Unscoped search: multiple definitions -> ambiguous is true
    ReferenceResult unscoped = t.find("drop_ns", null, null, null, null, null, false, 0);
    assertEquals(2, unscoped.totalReferences());
    assertTrue(unscoped.ambiguous());
    assertEquals(2, unscoped.definitionCount());

    // Scoped search to line 5 (structB definition) -> ambiguous is false, only structB reference
    // returned
    ReferenceResult scoped =
        t.find("drop_ns", null, file.toUri().toString(), 5, null, null, false, 0);
    assertEquals(1, scoped.totalReferences());
    assertFalse(scoped.ambiguous());
    assertEquals(10, scoped.references().get(0).line());
  }

  @Test
  public void testRealSourceOutranksFixtureCopyAndPathFilterAndCap(@TempDir Path root)
      throws Exception {
    Path real = root.resolve("demo/common");
    Path fixture = root.resolve("lib/src/test/resources/common");
    Files.createDirectories(real);
    Files.createDirectories(fixture);

    // Both call targetFn
    Files.writeString(fixture.resolve("sub.s"), "call targetFn\n");
    Files.writeString(real.resolve("sub.s"), "call targetFn\n");

    var ps = new DefaultWorkspaceIndex(root);
    ps.includeDir(root.toUri());
    var t = new SfxFindReferencesTool(ps);

    ReferenceResult r = t.find("targetFn", null, null, null, null, null, false, 0);
    assertEquals(2, r.totalReferences());
    assertEquals("demo/common/sub.s", r.references().get(0).path());
    assertEquals("lib/src/test/resources/common/sub.s", r.references().get(1).path());

    // Path filter
    ReferenceResult scoped = t.find("targetFn", null, null, null, "lib/", null, false, 0);
    assertEquals(1, scoped.totalReferences());
    assertEquals("lib/src/test/resources/common/sub.s", scoped.references().get(0).path());

    // Max results cap
    ReferenceResult capped = t.find("targetFn", null, null, null, null, 1, false, 0);
    assertEquals(2, capped.totalReferences());
    assertEquals(1, capped.references().size());
  }

  @Test
  public void testIsoCameraSetYawAgainstRealDemo() {
    Path xgsuPath = Path.of("../snes-sfx-demo/X-GSU");
    if (!Files.exists(xgsuPath)) {
      return;
    }
    Path root = Path.of("..").toAbsolutePath().normalize();
    var ps = new DefaultWorkspaceIndex(root);
    ps.includeDir(xgsuPath.toUri());
    var t = new SfxFindReferencesTool(ps);

    ReferenceResult r =
        t.find("iso_camera_set_yaw", null, null, null, "snes-sfx-demo/X-GSU", null, false, 1);

    assertTrue(r.totalReferences() >= 3);
    assertEquals(r.totalReferences(), r.references().size());
    for (var ref : r.references()) {
      assertEquals("call", ref.kind());
      assertNotNull(ref.text());
      assertTrue(ref.text().contains("iso_camera_set_yaw"));
    }

    var lineNumbers = r.references().stream().map(ReferenceLocation::lineNumber).sorted().toList();
    assertTrue(
        lineNumbers.containsAll(List.of(36, 466)),
        "Expected references to include lines [36, 466], found: " + lineNumbers);
  }

  @Test
  public void testGsuSqrt32FromDefinitionInRealDemo() {
    Path xgsuPath = Path.of("../snes-sfx-demo/X-GSU").toAbsolutePath().normalize();
    Path sqrtPath = xgsuPath.resolve("gsu_maths/gsu_sqrt.i");
    if (!Files.exists(sqrtPath)) {
      return;
    }
    Path root = Path.of("..").toAbsolutePath().normalize();
    var ps = new DefaultWorkspaceIndex(root);
    ps.includeDir(xgsuPath.toUri());
    var t = new SfxFindReferencesTool(ps);

    // Scoped to gsu_sqrt.i line 17 (0-based for line 18: "function gsu_sqrt32 ...")
    ReferenceResult r =
        t.find(
            "gsu_sqrt32",
            null,
            sqrtPath.toUri().toString(),
            17,
            "snes-sfx-demo/X-GSU",
            null,
            false,
            1);

    assertTrue(r.totalReferences() >= 2);
    boolean foundVectorLine538 =
        r.references().stream()
            .anyMatch(
                ref ->
                    "snes-sfx-demo/X-GSU/gsu_maths/gsu_vector.i".equals(ref.path())
                        && ref.lineNumber() == 538
                        && "call".equals(ref.kind()));
    assertTrue(
        foundVectorLine538,
        "Expected call gsu_sqrt32 at line 538 in gsu_vector.i, found: " + r.references());
  }
}
