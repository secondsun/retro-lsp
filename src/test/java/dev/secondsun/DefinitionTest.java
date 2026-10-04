package dev.secondsun;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.secondsun.lsp.Position;
import dev.secondsun.lsp.TextDocumentIdentifier;
import dev.secondsun.lsp.TextDocumentPositionParams;
import dev.secondsun.retro.util.CA65Scanner;
import dev.secondsun.retro.util.SymbolService;
import dev.secondsun.retro.util.vo.Location;
import dev.secondsun.retrolsp.feature.GoToDefinitionLinkFeature;
import java.net.URI;
import org.junit.jupiter.api.Test;

public class DefinitionTest {

  @Test
  public void testGoToDefinitionWithNullFile() {
    var symbolService = new SymbolService();
    var feature = new GoToDefinitionLinkFeature(symbolService);
    var params =
        new TextDocumentPositionParams(
            new TextDocumentIdentifier(URI.create("file:///test.s")), new Position(0, 0));

    var result = feature.handle(params, null);
    assertNotNull(result);
    assertTrue(result.isEmpty());
  }

  @Test
  public void testGoToDefinitionLineOutOfBounds() {
    var symbolService = new SymbolService();
    var feature = new GoToDefinitionLinkFeature(symbolService);
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
    var symbolService = new SymbolService();
    var targetUri = URI.create("file:///def.s");
    symbolService.addDefinition("mySymbol", new Location(targetUri, 5, 0, 8));

    var feature = new GoToDefinitionLinkFeature(symbolService);
    var fileContent = new CA65Scanner().tokenize("    jsr mySymbol\n");
    var params =
        new TextDocumentPositionParams(
            new TextDocumentIdentifier(URI.create("file:///test.s")), new Position(0, 10));

    var result = feature.handle(params, fileContent);
    assertNotNull(result);
    assertTrue(result.isPresent());
    var locations = result.get();
    assertEquals(1, locations.size());
    assertEquals(targetUri, locations.get(0).uri);
    assertEquals(5, locations.get(0).range.start.line);
  }
}
