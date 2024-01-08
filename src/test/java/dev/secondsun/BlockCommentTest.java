package dev.secondsun;

import static dev.secondsun.TestUtils.getTestDirURI;
import static dev.secondsun.TestUtils.getTestFile;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import dev.secondsun.lsp.Hover;
import dev.secondsun.lsp.InitializeParams;
import dev.secondsun.lsp.Position;
import dev.secondsun.lsp.TextDocumentIdentifier;
import dev.secondsun.lsp.TextDocumentPositionParams;
import dev.secondsun.retrolsp.CC65LanguageServer;

public class BlockCommentTest {

    @Test
    public void testBlockCommentCodeActionRequest() throws IOException {
        CC65LanguageServer server = new CC65LanguageServer();

        InitializeParams params = new InitializeParams();
        params.rootUri = getTestDirURI();
        server.initialize(params);

        TextDocumentIdentifier textDocument = new TextDocumentIdentifier(getTestFile("test.sgs"));
        Position position = new Position(11, 4);// A nop opcode
        Optional<Hover> hoverResult = server.hover(new TextDocumentPositionParams(textDocument, position));
        assertNotNull(hoverResult);
        assertTrue(hoverResult.isPresent());

        var hover = hoverResult.get();

        

        assertEquals("No operation", hover.contents.get(0).value);
    }

}
