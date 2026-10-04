package dev.secondsun;

import static org.junit.jupiter.api.Assertions.*;

import dev.secondsun.lsp.Hover;
import dev.secondsun.lsp.InitializeParams;
import dev.secondsun.lsp.Position;
import dev.secondsun.lsp.TextDocumentIdentifier;
import dev.secondsun.lsp.TextDocumentPositionParams;
import dev.secondsun.retrolsp.CA65LanguageServer;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.Optional;
import org.junit.jupiter.api.Test;

public class HoverTest {

  @Test
  public void hoverGSUOpCodes() throws IOException {
    CA65LanguageServer server = new CA65LanguageServer();

    InitializeParams params = new InitializeParams();
    params.rootUri = getTestDirURI();

    server.initialize(params);
    TextDocumentIdentifier textDocument = new TextDocumentIdentifier(getTestFile("test.sgs"));
    Position position = new Position(11, 4); // A nop opcode
    Optional<Hover> hoverResult =
        server.hover(new TextDocumentPositionParams(textDocument, position));
    assertNotNull(hoverResult);
    assertTrue(hoverResult.isPresent());

    var hover = hoverResult.get();
    assertEquals("No operation", hover.contents.get(0).value);
  }

  private URI getTestFile(String string) {
    return new File(getClass().getClassLoader().getResource("includeTest/" + string).getFile())
        .toURI();
  }

  private File getXGSUDir() {
    return new File(getClass().getClassLoader().getResource("X-GSU/").getFile());
  }

  private File getXGSUFile(String string) {
    return new File(getClass().getClassLoader().getResource("X-GSU/" + string).getFile());
  }

  private URI getTestDirURI() throws IOException {
    File file = new File(getClass().getClassLoader().getResource("includeTest/test.sgs").getFile());
    return file.getParentFile().getCanonicalFile().toURI();
  }

  @Test
  public void showRegisterUsageOfFunctionOnHover() throws IOException {
    // line 74, token 2 should be functionName
    var xgsuDir = getXGSUDir();
    CA65LanguageServer server = new CA65LanguageServer();
    InitializeParams params = new InitializeParams();
    params.rootUri = xgsuDir.toURI();
    server.initialize(params);
    TextDocumentIdentifier textDocument =
        new TextDocumentIdentifier(getXGSUFile("SuperFX.sgs").toURI());

    Position position = new Position(74, 8); // function name
    Optional<Hover> hoverResult =
        server.hover(new TextDocumentPositionParams(textDocument, position));
    assertNotNull(hoverResult);
    Hover functionHover = (hoverResult.get());

    assertEquals(1, functionHover.contents.size());
  }

  @Test
  public void debugVectorHover() throws IOException {
    CA65LanguageServer server = new CA65LanguageServer();
    InitializeParams params = new InitializeParams();
    params.rootUri = getXGSUFile("tests/reciprocal").toURI();

    server.initialize(params);
    // TextDocumentIdentifier textDocument = new
    // TextDocumentIdentifier(getXGSUFile("tests/reciprocal/Test.sgs").toURI());
    TextDocumentIdentifier textDocument2 =
        new TextDocumentIdentifier(getXGSUFile("gsu_maths/gsu_vector.i").toURI());
    Position position = new Position(418, 2); // nop opcode
    Optional<Hover> hoverResult =
        server.hover(new TextDocumentPositionParams(textDocument2, position));
    assertNotNull(hoverResult);
    assertFalse(hoverResult.get().contents.isEmpty());
  }

  @Test
  public void nohoverIfWhitespace() throws IOException {
    CA65LanguageServer server = new CA65LanguageServer();
    InitializeParams params = new InitializeParams();
    params.rootUri = getTestDirURI();

    server.initialize(params);
    TextDocumentIdentifier textDocument = new TextDocumentIdentifier(getTestFile("test.sgs"));
    Position position = new Position(11, 0); // Whitespace
    Optional<Hover> hoverResult =
        server.hover(new TextDocumentPositionParams(textDocument, position));
    assertNotNull(hoverResult);
    assertTrue(hoverResult.get().contents.isEmpty());
  }

  @Test
  public void testHoverWithNullFileContent() {
    var fileService = new dev.secondsun.retro.util.FileService();
    var symbolService = new dev.secondsun.retro.util.SymbolService();
    var hoverFeature = new dev.secondsun.retrolsp.feature.HoverFeature(fileService, symbolService);
    var params =
        new TextDocumentPositionParams(
            new TextDocumentIdentifier(URI.create("file:///test.s")), new Position(0, 0));

    var result = hoverFeature.handle(params, null);
    assertNotNull(result);
    assertTrue(result.isPresent());
    assertTrue(result.get().contents.isEmpty());
  }

  @Test
  public void testHoverLineOutOfBounds() {
    var fileService = new dev.secondsun.retro.util.FileService();
    var symbolService = new dev.secondsun.retro.util.SymbolService();
    var hoverFeature = new dev.secondsun.retrolsp.feature.HoverFeature(fileService, symbolService);
    var fileContent = new dev.secondsun.retro.util.CA65Scanner().tokenize("nop\n");
    var params =
        new TextDocumentPositionParams(
            new TextDocumentIdentifier(URI.create("file:///test.s")), new Position(10, 0));

    var result = hoverFeature.handle(params, fileContent);
    assertNotNull(result);
    assertTrue(result.isPresent());
    assertTrue(result.get().contents.isEmpty());
  }

  @Test
  public void testHoverOnSymbolHandlesRuntimeFailureGracefully() {
    var fileService = new dev.secondsun.retro.util.FileService();
    var symbolService = new dev.secondsun.retro.util.SymbolService();
    var targetUri = URI.create("file:///non_gsu_file.s");
    symbolService.addDefinition(
        "myData", new dev.secondsun.retro.util.vo.Location(targetUri, 0, 0, 6));

    var hoverFeature = new dev.secondsun.retrolsp.feature.HoverFeature(fileService, symbolService);
    var fileContent = new dev.secondsun.retro.util.CA65Scanner().tokenize("lda myData\n");
    var params =
        new TextDocumentPositionParams(
            new TextDocumentIdentifier(URI.create("file:///test.s")), new Position(0, 5));

    var result = hoverFeature.handle(params, fileContent);
    assertNotNull(result);
    assertTrue(result.isPresent());
  }
}
