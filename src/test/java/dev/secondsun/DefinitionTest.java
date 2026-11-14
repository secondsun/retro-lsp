package dev.secondsun;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.secondsun.lsp.Position;
import dev.secondsun.lsp.TextDocumentIdentifier;
import dev.secondsun.lsp.TextDocumentPositionParams;
import dev.secondsun.retro.util.CA65Scanner;
import dev.secondsun.retro.util.DefaultWorkspaceIndex;
import dev.secondsun.retrolsp.feature.GoToDefinitionLinkFeature;
import java.net.URI;
import org.junit.jupiter.api.Test;

public class DefinitionTest {

  @Test
  public void testGoToDefinitionWithNullFile() {
    var workspaceIndex = new DefaultWorkspaceIndex();
    var feature = new GoToDefinitionLinkFeature(workspaceIndex);
    var params =
        new TextDocumentPositionParams(
            new TextDocumentIdentifier(URI.create("file:///test.s")), new Position(0, 0));

    var result = feature.handle(params, null);
    assertNotNull(result);
    assertTrue(result.isEmpty());
  }

  @Test
  public void testGoToDefinitionLineOutOfBounds() {
    var workspaceIndex = new DefaultWorkspaceIndex();
    var feature = new GoToDefinitionLinkFeature(workspaceIndex);
    var fileContent = new CA65Scanner().tokenize("label:\n");
    var params =
        new TextDocumentPositionParams(
            new TextDocumentIdentifier(URI.create("file:///test.s")), new Position(10, 0));

    var result = feature.handle(params, fileContent);
    assertNotNull(result);
    assertTrue(result.isEmpty());
  }

  @Test
  public void testGoToDefinitionResolvesSymbol() {
    var workspaceIndex = new DefaultWorkspaceIndex();
    var targetUri = URI.create("file:///def.s");
    workspaceIndex.openOrUpdateBuffer(targetUri, "\n\n\n\n\nmySymbol:\n");

    var feature = new GoToDefinitionLinkFeature(workspaceIndex);
    var testUri = URI.create("file:///test.s");
    workspaceIndex.openOrUpdateBuffer(testUri, "    jsr mySymbol\n");
    var fileContent = workspaceIndex.getFileContents(testUri);
    var params =
        new TextDocumentPositionParams(new TextDocumentIdentifier(testUri), new Position(0, 10));

    var result = feature.handle(params, fileContent);
    assertNotNull(result);
    assertTrue(result.isPresent());
    var locations = result.get();
    assertEquals(1, locations.size());
    assertEquals(targetUri, locations.get(0).uri);
    assertEquals(5, locations.get(0).range.start.line);
  }

  @Test
  public void testGoToDefinitionLocalLabelsInScopedFunctions() {
    var uri = URI.create("file:///test.s");
    var src =
        """
        function func1
        @loop:
            bra @loop
        endfunction
        function func2
        @loop:
            bra @loop
        endfunction
        """;
    var workspaceIndex = new DefaultWorkspaceIndex();
    workspaceIndex.openOrUpdateBuffer(uri, src);
    var fileContent = workspaceIndex.getFileContents(uri);

    var feature = new GoToDefinitionLinkFeature(workspaceIndex);

    // func1's @loop at line 2, character 9
    var params1 =
        new TextDocumentPositionParams(new TextDocumentIdentifier(uri), new Position(2, 9));
    var res1 = feature.handle(params1, fileContent);
    assertNotNull(res1);
    assertTrue(res1.isPresent());
    assertEquals(1, res1.get().get(0).range.start.line);

    // func2's @loop at line 6, character 9
    var params2 =
        new TextDocumentPositionParams(new TextDocumentIdentifier(uri), new Position(6, 9));
    var res2 = feature.handle(params2, fileContent);
    assertNotNull(res2);
    assertTrue(res2.isPresent());
    assertEquals(5, res2.get().get(0).range.start.line);
  }

  @Test
  public void testGoToDefinitionFunctionParametersAndReturnVariables() {
    var uri = URI.create("file:///test.s");
    var src =
        """
        function calculate param1, param2 : result
            lda param1
            sta result
            return result
        endfunction
        """;
    var workspaceIndex = new DefaultWorkspaceIndex();
    workspaceIndex.openOrUpdateBuffer(uri, src);
    var fileContent = workspaceIndex.getFileContents(uri);

    var feature = new GoToDefinitionLinkFeature(workspaceIndex);

    // param1 at line 1, col 9
    var params1 =
        new TextDocumentPositionParams(new TextDocumentIdentifier(uri), new Position(1, 9));
    var res1 = feature.handle(params1, fileContent);
    assertNotNull(res1);
    assertTrue(res1.isPresent());
    assertEquals(0, res1.get().get(0).range.start.line);

    // result at line 2, col 9
    var params2 =
        new TextDocumentPositionParams(new TextDocumentIdentifier(uri), new Position(2, 9));
    var res2 = feature.handle(params2, fileContent);
    assertNotNull(res2);
    assertTrue(res2.isPresent());
    assertEquals(0, res2.get().get(0).range.start.line);
  }

  @Test
  public void testGoToDefinitionQualifiedSymbolAndStructFields() {
    var uri = URI.create("file:///test.s");
    var src =
        """
        .struct Point
            coordX .word
            coordY .word
        .endstruct
            lda Point::coordX
            jsr ::globalFunc




        globalFunc:
        """;
    var workspaceIndex = new DefaultWorkspaceIndex();
    workspaceIndex.openOrUpdateBuffer(uri, src);
    var fileContent = workspaceIndex.getFileContents(uri);

    var feature = new GoToDefinitionLinkFeature(workspaceIndex);

    // Point::coordX on "::" at line 4, col 13
    var params1 =
        new TextDocumentPositionParams(new TextDocumentIdentifier(uri), new Position(4, 13));
    var res1 = feature.handle(params1, fileContent);
    assertNotNull(res1);
    assertTrue(res1.isPresent());
    assertEquals(1, res1.get().get(0).range.start.line);

    // Point::coordX on member at line 4, col 16
    var params2 =
        new TextDocumentPositionParams(new TextDocumentIdentifier(uri), new Position(4, 16));
    var res2 = feature.handle(params2, fileContent);
    assertNotNull(res2);
    assertTrue(res2.isPresent());
    assertEquals(1, res2.get().get(0).range.start.line);

    // ::globalFunc on "::" at line 5, col 9
    var params3 =
        new TextDocumentPositionParams(new TextDocumentIdentifier(uri), new Position(5, 9));
    var res3 = feature.handle(params3, fileContent);
    assertNotNull(res3);
    assertTrue(res3.isPresent());
    assertEquals(10, res3.get().get(0).range.start.line);
  }
}
