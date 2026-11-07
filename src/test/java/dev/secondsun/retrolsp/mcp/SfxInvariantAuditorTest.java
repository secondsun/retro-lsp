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
    assertEquals(Severity.ERROR, v.get().severity());
    assertEquals(1, v.get().line());
  }

  @Test
  public void testConsecutiveConflictingModalInstructionsFlagged() {
    String codeWithTo =
        """
        with r1
        to r2
        xor r3
        """;
    AuditResult resultWithTo = auditor.audit(codeWithTo);
    var vWithTo =
        resultWithTo.violations().stream()
            .filter(violation -> violation.rule().equals("MODAL_PREFIX_OVERWRITTEN"))
            .findFirst();
    assertTrue(vWithTo.isPresent());
    assertEquals(Severity.ERROR, vWithTo.get().severity());
    assertEquals(1, vWithTo.get().line());

    String codeFromFrom =
        """
        from r1
        from r2
        add r3
        """;
    AuditResult resultFromFrom = auditor.audit(codeFromFrom);
    var vFromFrom =
        resultFromFrom.violations().stream()
            .filter(violation -> violation.rule().equals("MODAL_PREFIX_OVERWRITTEN"))
            .findFirst();
    assertTrue(vFromFrom.isPresent());
    assertEquals(Severity.ERROR, vFromFrom.get().severity());
    assertEquals(1, vFromFrom.get().line());
    assertTrue(vFromFrom.get().message().contains("immediately overwritten by 'from'"));

    String codeToWith =
        """
        to r1
        with r2
        sub r3
        """;
    AuditResult resultToWith = auditor.audit(codeToWith);
    var vToWith =
        resultToWith.violations().stream()
            .filter(violation -> violation.rule().equals("MODAL_PREFIX_OVERWRITTEN"))
            .findFirst();
    assertTrue(vToWith.isPresent());
    assertEquals(Severity.ERROR, vToWith.get().severity());
    assertEquals(1, vToWith.get().line());
    assertTrue(vToWith.get().message().contains("completely overwriting 'to'"));
  }

  @Test
  public void testFromAndToCanBeCombinedToSetSregAndDreg() {
    String codeFromTo =
        """
        from r1
        to r2
        add r3
        """;
    AuditResult resultFromTo = auditor.audit(codeFromTo);
    assertTrue(
        resultFromTo.violations().stream()
            .noneMatch(v -> v.rule().equals("MODAL_PREFIX_OVERWRITTEN")));

    String codeToFrom =
        """
        to r8
        from anchor_y
        add r0
        """;
    AuditResult resultToFrom = auditor.audit(codeToFrom);
    assertTrue(
        resultToFrom.violations().stream()
            .noneMatch(v -> v.rule().equals("MODAL_PREFIX_OVERWRITTEN")));
  }

  @Test
  public void testModalPrefixColumnRangeForUnderline() {
    String code =
        """
            to r11
            move r11, trap_ptr
        """;
    AuditResult result = auditor.audit(code);
    var v =
        result.violations().stream()
            .filter(violation -> violation.rule().equals("MODAL_PREFIX_OVERWRITTEN"))
            .findFirst();
    assertTrue(v.isPresent());
    assertEquals(4, v.get().columnStart());
    assertEquals(10, v.get().columnEnd());
    var sd = v.get().toSyntaxDiagnostic(java.net.URI.create("file:///test.s"));
    assertEquals(4, sd.columnStart());
    assertEquals(10, sd.columnEnd());
    assertEquals(dev.secondsun.retro.util.diagnostic.DiagnosticSeverity.ERROR, sd.severity());
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

  private static java.util.List<SfxInvariantAuditor.InvariantViolation> perf(
      java.util.List<SfxInvariantAuditor.InvariantViolation> vs, String rule) {
    return vs.stream().filter(v -> v.rule().equals(rule)).toList();
  }

  @Test
  public void testCallInForLoopWarns() {
    String code =
        """
        function outer
            for 4
                call helper
            endfor
            call helper
        endfunction
        """;
    var hits = perf(auditor.audit(code).violations(), "CALL_IN_HOT_LOOP");
    assertEquals(1, hits.size());
    assertEquals(3, hits.get(0).line());
    assertEquals(Severity.WARNING, hits.get(0).severity());
  }

  @Test
  public void testCallAfterCacheWarns() {
    String code =
        """
        cache
        call helper
        """;
    var hits = perf(auditor.audit(code).violations(), "CALL_IN_HOT_LOOP");
    assertEquals(1, hits.size());
    assertEquals(2, hits.get(0).line());
  }

  @Test
  public void testCallInLabelLoopWarns() {
    String code =
        """
        iwt r12, #4
        iwt r13, #top
        top:
        call helper
        loop
        nop
        call helper
        """;
    var hits = perf(auditor.audit(code).violations(), "CALL_IN_HOT_LOOP");
    assertEquals(1, hits.size());
    assertEquals(4, hits.get(0).line());
  }

  @Test
  public void testCacheInLoopWarns() {
    String code =
        """
        for 10
            cache
        endfor
        """;
    var hits = perf(auditor.audit(code).violations(), "CACHE_IN_LOOP");
    assertEquals(1, hits.size());
    assertEquals(2, hits.get(0).line());
  }

  @Test
  public void testCacheBlockOverflowWarns() {
    StringBuilder sb = new StringBuilder("cache\ntop:\n");
    for (int i = 0; i < 520; i++) {
      sb.append("add r1\n");
    }
    sb.append("loop\nnop\n");
    var hits = perf(auditor.audit(sb.toString()).violations(), "CACHE_BLOCK_OVERFLOW");
    assertEquals(1, hits.size());
    assertEquals(1, hits.get(0).line());
    assertEquals(Severity.WARNING, hits.get(0).severity());
  }

  @Test
  public void testSmallCacheBlockDoesNotWarn() {
    String code =
        """
        cache
        top:
        add r1
        loop
        nop
        """;
    assertTrue(perf(auditor.audit(code).violations(), "CACHE_BLOCK_OVERFLOW").isEmpty());
  }

  @Test
  public void testRpixInFunctionWarnsButNotBeforeStop() {
    String code =
        """
        function draw
            plot
            rpix
            rpix
        endfunction
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
    var hits = perf(auditor.audit(code).violations(), "REDUNDANT_RPIX_IN_SUBROUTINE");
    assertEquals(java.util.List.of(3, 4), hits.stream().map(v -> v.line()).toList());
  }

  @Test
  public void testRombInLoopWarns() {
    String code =
        """
        romb
        for 3
            romb
        endfor
        """;
    var hits = perf(auditor.audit(code).violations(), "ROMB_IN_HOT_LOOP");
    assertEquals(1, hits.size());
    assertEquals(3, hits.get(0).line());
    assertEquals(Severity.WARNING, hits.get(0).severity());
  }

  @Test
  public void testDestructiveWithFollowedBySubAndBranchWarns() {
    String code =
        """
        with r8
        sub #1
        beq target
        nop
        target:
        """;
    AuditResult res = auditor.audit(code);
    var hits =
        res.violations().stream()
            .filter(v -> "DESTRUCTIVE_WITH_COMPARISON".equals(v.rule()))
            .toList();
    assertEquals(1, hits.size());
    assertEquals(1, hits.get(0).line());
    assertEquals(Severity.WARNING, hits.get(0).severity());
    assertTrue(hits.get(0).message().contains("modifies R8 in-place"));
    assertTrue(hits.get(0).message().contains("use 'from R8' instead"));
  }

  @Test
  public void testFromRnFollowedBySubAndBranchIsValid() {
    String code =
        """
        from r8
        sub #1
        beq target
        nop
        target:
        """;
    AuditResult res = auditor.audit(code);
    var hits =
        res.violations().stream()
            .filter(v -> "DESTRUCTIVE_WITH_COMPARISON".equals(v.rule()))
            .toList();
    assertTrue(hits.isEmpty());
  }

  @Test
  public void testFromFollowedBySubZeroWithoutToTriggersDestructiveFromAluToR0() {
    String code =
        """
        from r12
        sub #0
        """;
    AuditResult result = auditor.audit(code);
    assertTrue(result.valid(), "WARNING should not invalidate AuditResult");
    var v =
        result.violations().stream()
            .filter(violation -> "DESTRUCTIVE_FROM_ALU_TO_R0".equals(violation.rule()))
            .findFirst();
    assertTrue(v.isPresent());
    assertEquals(Severity.WARNING, v.get().severity());
    assertEquals(1, v.get().line());
    assertTrue(v.get().message().contains("Instruction 'from' on line 1"));
    assertTrue(v.get().message().contains("followed by 'sub #0' on line 2 without 'to'"));
    assertTrue(v.get().message().contains("clobbering R0"));
  }

  @Test
  public void testFromToSubOrAddZeroDoesNotTriggerDestructiveFromAluToR0() {
    String code =
        """
        from r12
        to r12
        add #0
        """;
    AuditResult result = auditor.audit(code);
    assertTrue(
        result.violations().stream().noneMatch(v -> "DESTRUCTIVE_FROM_ALU_TO_R0".equals(v.rule())));
  }

  @Test
  public void testFromFollowedByAddZeroWithoutToTriggersDestructiveFromAluToR0() {
    String code =
        """
        from r12
        add #$00
        """;
    AuditResult result = auditor.audit(code);
    assertTrue(result.valid());
    var v =
        result.violations().stream()
            .filter(violation -> "DESTRUCTIVE_FROM_ALU_TO_R0".equals(violation.rule()))
            .findFirst();
    assertTrue(v.isPresent());
    assertEquals(Severity.WARNING, v.get().severity());
    assertEquals(1, v.get().line());
    assertTrue(v.get().message().contains("followed by 'add #0' on line 2 without 'to'"));
  }

  @Test
  public void testFromFollowedBySubNonZeroDoesNotTriggerDestructiveFromAluToR0() {
    String code =
        """
        from r12
        sub #1
        """;
    AuditResult result = auditor.audit(code);
    assertTrue(
        result.violations().stream().noneMatch(v -> "DESTRUCTIVE_FROM_ALU_TO_R0".equals(v.rule())));
  }

  @Test
  public void testIbtUnmaskedNegativeImmediateFlaggedAndMaskedOrHexAllowed() {
    String code =
        """
        ibt r0, #-128
        ibt r1, #<(-128)
        ibt r2, #$80
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
    var hits =
        result.violations().stream()
            .filter(v -> "IBT_NEGATIVE_IMMEDIATE".equals(v.rule()))
            .toList();
    assertEquals(1, hits.size());
    assertEquals(1, hits.get(0).line());
    assertEquals(Severity.ERROR, hits.get(0).severity());
    assertTrue(hits.get(0).message().contains("#-128"));
  }
}
