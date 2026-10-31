package dev.secondsun.retrolsp.mcp;

import static org.junit.jupiter.api.Assertions.*;

import dev.secondsun.retrolsp.mcp.tools.SfxInvariantAuditor;
import dev.secondsun.retrolsp.mcp.tools.SfxInvariantAuditor.AuditResult;
import dev.secondsun.retrolsp.mcp.tools.SfxInvariantAuditor.Severity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class SfxInvariantAuditorTest {

  private SfxInvariantAuditor auditor;

  @BeforeEach
  public void setUp() {
    auditor = new SfxInvariantAuditor();
  }

  @Test
  public void testEightNopBeforeStopValid() {
    String code =
        """
        nop
        nop
        nop
        nop
        nop
        nop
        nop
        nop
        stop
        """;
    AuditResult result = auditor.audit(code);
    assertTrue(result.valid());
    assertTrue(result.violations().isEmpty());
  }

  @Test
  public void testFewerThanEightNopBeforeStopInvalid() {
    String code =
        """
        nop
        nop
        nop
        stop
        """;
    AuditResult result = auditor.audit(code);
    assertFalse(result.valid());
    assertEquals(1, result.violations().size());
    var v = result.violations().get(0);
    assertEquals("PIPELINE_SETTLING_8_NOP", v.rule());
    assertEquals(Severity.ERROR, v.severity());
    assertEquals(4, v.line()); // line 4 has 'stop'
    assertTrue(v.message().contains("3 NOPs"));
  }

  @Test
  public void testInterveningInstructionBreaksNopSettling() {
    String code =
        """
        nop
        nop
        nop
        nop
        nop
        nop
        nop
        add #1
        nop
        stop
        """;
    AuditResult result = auditor.audit(code);
    assertFalse(result.valid());
    var v =
        result.violations().stream()
            .filter(violation -> violation.rule().equals("PIPELINE_SETTLING_8_NOP"))
            .findFirst();
    assertTrue(v.isPresent());
    assertTrue(v.get().message().contains("1 NOPs"));
  }

  @Test
  public void testHardwareMergeInstructionInvalid() {
    String code =
        """
        from r1
        merge
        to r2
        """;
    AuditResult result = auditor.audit(code);
    assertFalse(result.valid());
    var v =
        result.violations().stream()
            .filter(violation -> violation.rule().equals("NO_HARDWARE_MERGE"))
            .findFirst();
    assertTrue(v.isPresent());
    assertEquals(2, v.get().line());
    assertEquals(Severity.ERROR, v.get().severity());
    assertTrue(v.get().message().contains("SuperFX 3 (FX3)"));
  }

  @Test
  public void testWordAlignmentLdwStwValid() {
    String code =
        """
        ldw $2000
        stw $1004
        ldw (r10)
        stw 0, r1
        """;
    AuditResult result = auditor.audit(code);
    var wordViolations =
        result.violations().stream()
            .filter(v -> v.rule().equals("WORD_ALIGNMENT_LDW_STW"))
            .toList();
    assertTrue(wordViolations.isEmpty());
  }

  @Test
  public void testWordAlignmentLdwStwInvalid() {
    String code =
        """
        ldw $2001
        stw $1003
        ldw 1235
        """;
    AuditResult result = auditor.audit(code);
    var wordViolations =
        result.violations().stream()
            .filter(v -> v.rule().equals("WORD_ALIGNMENT_LDW_STW"))
            .toList();
    assertEquals(3, wordViolations.size());
    for (var v : wordViolations) {
      assertEquals("WORD_ALIGNMENT_LDW_STW", v.rule());
      assertEquals(Severity.ERROR, v.severity());
    }
  }

  @Test
  public void testModalInstructionScopeOverwritten() {
    String code =
        """
        with r1
        with r2
        add #2
        """;
    AuditResult result = auditor.audit(code);
    var v =
        result.violations().stream()
            .filter(violation -> violation.rule().equals("MODAL_PREFIX_OVERWRITTEN"))
            .findFirst();
    assertTrue(v.isPresent());
    assertEquals(Severity.WARNING, v.get().severity());
    assertEquals(1, v.get().line());
  }

  @Test
  public void testDanglingModalPrefixAtEndOfFile() {
    String code =
        """
        nop
        to r1
        """;
    AuditResult result = auditor.audit(code);
    var v =
        result.violations().stream()
            .filter(violation -> violation.rule().equals("DANGLING_MODAL_PREFIX"))
            .findFirst();
    assertTrue(v.isPresent());
    assertEquals(Severity.ERROR, v.get().severity());
    assertEquals(2, v.get().line());
  }

  @Test
  public void testDanglingModalPrefixFollowedByStop() {
    String code =
        """
        from r1
        stop
        """;
    AuditResult result = auditor.audit(code);
    var v =
        result.violations().stream()
            .filter(violation -> violation.rule().equals("DANGLING_MODAL_PREFIX"))
            .findFirst();
    assertTrue(v.isPresent());
    assertEquals(Severity.ERROR, v.get().severity());
  }

  @Test
  public void testModalPrefixWithInterveningLabelValid() {
    String code =
        """
        from r1
        my_label:
        nop
        """;
    AuditResult result = auditor.audit(code);
    var v =
        result.violations().stream()
            .filter(violation -> violation.rule().equals("DANGLING_MODAL_PREFIX"))
            .findFirst();
    assertTrue(v.isEmpty(), "Modal prefix followed by label then instruction should be valid");
  }

  @Test
  public void testModalPrefixInLoopDelaySlotValid() {
    String code =
        """
        loop
        with r1
        return
        """;
    AuditResult result = auditor.audit(code);
    var v =
        result.violations().stream()
            .filter(violation -> violation.rule().equals("DANGLING_MODAL_PREFIX"))
            .findFirst();
    assertTrue(v.isEmpty(), "Modal prefix in loop delay slot should be valid");
  }

  @Test
  public void testEightNopBeforeTestStopLabelAndStopValid() {
    String code =
        """
        nop
        nop
        nop
        nop
        nop
        nop
        nop
        nop
        test_stop:
        stop
        """;
    AuditResult result = auditor.audit(code);
    assertTrue(result.valid());
    assertTrue(result.violations().isEmpty());
  }

  @Test
  public void testUnflushedPixelCacheWarningWhenPlotWithoutTwoRpix() {
    String code =
        """
        plot
        rpix
        nop
        nop
        nop
        nop
        nop
        nop
        nop
        nop
        stop
        """;
    AuditResult result = auditor.audit(code);
    assertTrue(result.valid(), "WARNING should not invalidate AuditResult");
    var v =
        result.violations().stream()
            .filter(violation -> violation.rule().equals("UNFLUSHED_PIXEL_CACHE"))
            .findFirst();
    assertTrue(v.isPresent());
    assertEquals(Severity.WARNING, v.get().severity());
  }

  @Test
  public void testPlotWithTwoRpixBeforeStopValid() {
    String code =
        """
        plot
        rpix
        rpix
        nop
        nop
        nop
        nop
        nop
        nop
        nop
        nop
        stop
        """;
    AuditResult result = auditor.audit(code);
    assertTrue(result.valid());
    assertTrue(result.violations().isEmpty());
  }

  @Test
  public void testGsuSqrtFileInvariants() throws Exception {
    var path = java.nio.file.Path.of("../snes-sfx-demo/X-GSU/gsu_maths/gsu_sqrt.i");
    if (java.nio.file.Files.exists(path)) {
      String code = java.nio.file.Files.readString(path);
      AuditResult result = auditor.audit(code);
      var dangling =
          result.violations().stream()
              .filter(violation -> violation.rule().equals("DANGLING_MODAL_PREFIX"))
              .toList();
      assertTrue(
          dangling.isEmpty(),
          "Expected no DANGLING_MODAL_PREFIX violations in gsu_sqrt.i, found: " + dangling);
    }
  }

  @Test
  public void testRomSymbolRamLoadFlaggedAndGsuramAllowed() {
    String code =
        """
        .segment "GSURAM"
        RAM_BUF:
            .res 4

        .segment "GSUCODE"
        TEXTURE_TABLE:
            .word $8000

        main:
            iwt r1, #RAM_BUF
            ldw (r1)
            iwt r2, #TEXTURE_TABLE
            ldw (r2)
            nop
            nop
            nop
            nop
            nop
            nop
            nop
            nop
            stop
        """;
    AuditResult result = auditor.audit(code);
    assertFalse(result.valid());
    var romLoadViolations =
        result.violations().stream().filter(v -> "ROM_SYMBOL_RAM_LOAD".equals(v.rule())).toList();
    assertEquals(1, romLoadViolations.size());
    assertEquals(13, romLoadViolations.get(0).line());
    assertTrue(romLoadViolations.get(0).message().contains("TEXTURE_TABLE"));
    assertTrue(romLoadViolations.get(0).message().contains("GSUCODE"));
  }

  @Test
  public void testIwtR15NonLabelFlagged() {
    String code =
        """
        target_label:
            iwt r15, #target_label
            nop
            iwt r15, #$1234
            nop
        """;
    AuditResult result = auditor.audit(code);
    assertFalse(result.valid());
    var iwtViolations =
        result.violations().stream().filter(v -> "IWT_R15_NON_LABEL".equals(v.rule())).toList();
    assertEquals(1, iwtViolations.size());
    assertEquals(4, iwtViolations.get(0).line());
  }
}
