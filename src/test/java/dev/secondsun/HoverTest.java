package dev.secondsun;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

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

    private URI getTestDirURI() throws IOException {
        File file = new File(getClass().getClassLoader().getResource("includeTest/test.sgs").getFile());
        return file.getParentFile().getCanonicalFile().toURI();
    }

    @Test
    public void nohoverIfNotGSUOpCode() throws IOException {
        CC65LanguageServer server = new CC65LanguageServer();
        InitializeParams params = new InitializeParams();
        params.rootUri = getTestDirURI();

        server.initialize(params);
        TextDocumentIdentifier textDocument = new TextDocumentIdentifier(getTestFile("test.sgs"));
        Position position = new Position(11, 0);//Whitespace
        Optional<Hover> hoverResult = server.hover(new TextDocumentPositionParams(textDocument, position));
        assertNotNull(hoverResult);
        assertTrue(hoverResult.get().contents == null);
    }
}
