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

  @Test
  public void testHoverDisplaysDocComments() {
    var uri = URI.create("file:///test.s");
    var scanner = new dev.secondsun.retro.util.CA65Scanner();
    var src =
        """
        ; Documenting myCoolFunction
        ; Multi-line documentation
        function myCoolFunction param1 : result
            return result
        endfunction
        """;
    var fileContent = scanner.tokenize(src);
    fileContent.uri = uri;
    var fileService = new dev.secondsun.retro.util.FileService();
    var symbolService = new dev.secondsun.retro.util.SymbolService();
    symbolService.extractDefinitions(fileContent);

    var hoverFeature = new dev.secondsun.retrolsp.feature.HoverFeature(fileService, symbolService);

    // Hover on myCoolFunction at line 2, character 15
    var params =
        new TextDocumentPositionParams(new TextDocumentIdentifier(uri), new Position(2, 15));
    var result = hoverFeature.handle(params, fileContent);

    assertNotNull(result);
    assertTrue(result.isPresent());
    assertFalse(result.get().contents.isEmpty());

    var hoverText = result.get().contents.get(0).value;
    assertTrue(hoverText.contains("Documenting myCoolFunction"));
    assertTrue(hoverText.contains("Multi-line documentation"));
    assertTrue(hoverText.contains("function myCoolFunction param1 : result"));
  }

  @Test
  public void testHoverOnFunctionDeclarationDisplaysSignature() {
    var uri = URI.create("file:///test.s");
    var scanner = new dev.secondsun.retro.util.CA65Scanner();
    var src =
        """
        function calculateSum a, b : sum
            return sum
        endfunction
        call calculateSum
        """;
    var fileContent = scanner.tokenize(src);
    fileContent.uri = uri;
    var fileService = new dev.secondsun.retro.util.FileService();
    var symbolService = new dev.secondsun.retro.util.SymbolService();
    symbolService.extractDefinitions(fileContent);

    var hoverFeature = new dev.secondsun.retrolsp.feature.HoverFeature(fileService, symbolService);

    // Hover on function name at declaration (line 0, col 12)
    var paramsDecl =
        new TextDocumentPositionParams(new TextDocumentIdentifier(uri), new Position(0, 12));
    var resDecl = hoverFeature.handle(paramsDecl, fileContent);
    assertNotNull(resDecl);
    assertTrue(resDecl.isPresent());
    assertFalse(resDecl.get().contents.isEmpty());
    assertTrue(resDecl.get().contents.get(0).value.contains("function calculateSum a, b : sum"));

    // Hover on function name at call site (line 3, col 7)
    var paramsCall =
        new TextDocumentPositionParams(new TextDocumentIdentifier(uri), new Position(3, 7));
    var resCall = hoverFeature.handle(paramsCall, fileContent);
    assertNotNull(resCall);
    assertTrue(resCall.isPresent());
    assertFalse(resCall.get().contents.isEmpty());
    assertTrue(resCall.get().contents.get(0).value.contains("function calculateSum a, b : sum"));
  }

  @Test
  public void testHoverOnPseudoMacroKeywords() {
    var uri = URI.create("file:///test.s");
    var scanner = new dev.secondsun.retro.util.CA65Scanner();
    var src =
        """
        function myFunc
            call otherFunc
            return
        endfunction
        """;
    var fileContent = scanner.tokenize(src);
    fileContent.uri = uri;
    var fileService = new dev.secondsun.retro.util.FileService();
    var symbolService = new dev.secondsun.retro.util.SymbolService();

    var hoverFeature = new dev.secondsun.retrolsp.feature.HoverFeature(fileService, symbolService);

    // Hover on "function" keyword at line 0, col 2
    var pFunction =
        new TextDocumentPositionParams(new TextDocumentIdentifier(uri), new Position(0, 2));
    var rFunction = hoverFeature.handle(pFunction, fileContent);
    assertTrue(rFunction.isPresent());
    assertTrue(rFunction.get().contents.get(0).value.contains("function <name>"));

    // Hover on "call" keyword at line 1, col 5
    var pCall = new TextDocumentPositionParams(new TextDocumentIdentifier(uri), new Position(1, 5));
    var rCall = hoverFeature.handle(pCall, fileContent);
    assertTrue(rCall.isPresent());
    assertTrue(rCall.get().contents.get(0).value.contains("call <target>"));

    // Hover on "return" keyword at line 2, col 5
    var pReturn =
        new TextDocumentPositionParams(new TextDocumentIdentifier(uri), new Position(2, 5));
    var rReturn = hoverFeature.handle(pReturn, fileContent);
    assertTrue(rReturn.isPresent());
    assertTrue(rReturn.get().contents.get(0).value.contains("return"));

    // Hover on "endfunction" keyword at line 3, col 2
    var pEndFunction =
        new TextDocumentPositionParams(new TextDocumentIdentifier(uri), new Position(3, 2));
    var rEndFunction = hoverFeature.handle(pEndFunction, fileContent);
    assertTrue(rEndFunction.isPresent());
    assertTrue(rEndFunction.get().contents.get(0).value.contains("endfunction"));
  }
}
