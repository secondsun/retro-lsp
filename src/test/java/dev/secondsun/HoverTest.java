package dev.secondsun;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import dev.secondsun.lsp.Hover;
import dev.secondsun.lsp.InitializeParams;
import dev.secondsun.lsp.Position;
import dev.secondsun.lsp.TextDocumentIdentifier;
import dev.secondsun.lsp.TextDocumentPositionParams;
import dev.secondsun.retrolsp.CC65LanguageServer;

import static org.junit.jupiter.api.Assertions.*;


public class HoverTest {

    @Test
    public void hoverGSUOpCodes() throws IOException {
        CC65LanguageServer server = new CC65LanguageServer();
        
        InitializeParams params = new InitializeParams();
        params.rootUri = getTestDirURI();

        server.initialize(params);
        TextDocumentIdentifier textDocument = new TextDocumentIdentifier(getTestFile("test.sgs"));
        Position position = new Position(11, 4);//A nop opcode
        Optional<Hover> hoverResult = server.hover(new TextDocumentPositionParams(textDocument, position));
        assertNotNull(hoverResult);
        assertTrue(hoverResult.isPresent());
        
        var hover = hoverResult.get();
        assertEquals("No operation", hover.contents.get(0).value);
    }

    private URI getTestFile(String string) {
        return new File(getClass().getClassLoader().getResource("includeTest/" + string).getFile()).toURI();
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
    public void  showRegisterUsageOfFunctionOnHover() throws IOException {
//line 74, token 2 should be functionName
        var xgsuDir = getXGSUDir();
        CC65LanguageServer server = new CC65LanguageServer();
        InitializeParams params = new InitializeParams();
        params.rootUri = xgsuDir.toURI();
        server.initialize(params);
        TextDocumentIdentifier textDocument = new TextDocumentIdentifier(getXGSUFile("SuperFX.sgs").toURI());

        Position position = new Position(74, 8);//function name
        Optional<Hover> hoverResult = server.hover(new TextDocumentPositionParams(textDocument, position));
        assertNotNull(hoverResult);
        Hover functionHover = (hoverResult.get());

        assertEquals(1, functionHover.contents.size());

    }

    @Test
    public void debugVectorHover() throws IOException {
        CC65LanguageServer server = new CC65LanguageServer();
        InitializeParams params = new InitializeParams();
        params.rootUri = getXGSUFile("tests/reciprocal").toURI();

        server.initialize(params);
        //TextDocumentIdentifier textDocument = new TextDocumentIdentifier(getXGSUFile("tests/reciprocal/Test.sgs").toURI());
        TextDocumentIdentifier textDocument2 = new TextDocumentIdentifier(getXGSUFile("gsu_maths/gsu_vector.i").toURI());
        Position position = new Position(127, 15);//Whitespace
        Optional<Hover> hoverResult = server.hover(new TextDocumentPositionParams(textDocument2, position));
        assertNotNull(hoverResult);
        assertFalse(hoverResult.get().contents.isEmpty());
    }

    @Test
    public void nohoverIfWhitespace() throws IOException {
        CC65LanguageServer server = new CC65LanguageServer();
        InitializeParams params = new InitializeParams();
        params.rootUri = getTestDirURI();

        server.initialize(params);
        TextDocumentIdentifier textDocument = new TextDocumentIdentifier(getTestFile("test.sgs"));
        Position position = new Position(11, 0);//Whitespace
        Optional<Hover> hoverResult = server.hover(new TextDocumentPositionParams(textDocument, position));
        assertNotNull(hoverResult);
        assertTrue(hoverResult.get().contents.isEmpty());
    }
}
