package dev.secondsun;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.net.URI;
import java.util.Arrays;
import java.util.Optional;

import org.apache.commons.io.IOUtils;
import org.junit.Test;

import dev.secondsun.lsp.Hover;
import dev.secondsun.lsp.Position;
import dev.secondsun.lsp.TextDocumentIdentifier;
import dev.secondsun.lsp.TextDocumentPositionParams;

public class HoverTest {

    @Test
    public void hoverGSUOpCodes() {
        CC65LanguageServer server = new CC65LanguageServer((uri)->{
            var sgsProgram = IOUtils.toString(CC65LanguageServer.class.getClassLoader().getResourceAsStream(uri.toString()));
            return Arrays.asList(sgsProgram.split("\n"));
        });
        TextDocumentIdentifier textDocument = new TextDocumentIdentifier(URI.create("test.sgs"));
        Position position = new Position(11, 4);//A nop opcode
        Optional<Hover> hoverResult = server.hover(new TextDocumentPositionParams(textDocument, position));
        assertNotNull(hoverResult);
        assertTrue(hoverResult.isPresent());
        
        var hover = hoverResult.get();
        assertEquals("No operation", hover.contents.get(0).value);
    }

    @Test
    public void nohoverIfNotGSUOpCode() {
        CC65LanguageServer server = new CC65LanguageServer((uri)->{
            var sgsProgram = IOUtils.toString(CC65LanguageServer.class.getClassLoader().getResourceAsStream(uri.toString()));
            return Arrays.asList(sgsProgram.split("\n"));
        });
        TextDocumentIdentifier textDocument = new TextDocumentIdentifier(URI.create("test.sgs"));
        Position position = new Position(11, 1);//Whitespace
        Optional<Hover> hoverResult = server.hover(new TextDocumentPositionParams(textDocument, position));
        assertNotNull(hoverResult);
        assertTrue(hoverResult.get().contents == null);
        
    }
}
