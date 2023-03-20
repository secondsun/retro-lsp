package dev.secondsun;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import com.google.gson.GsonBuilder;

import dev.secondsun.lsp.Position;
import dev.secondsun.lsp.TextDocumentIdentifier;
import dev.secondsun.lsp.TextDocumentPositionParams;
import dev.secondsun.tm4e.core.grammar.IGrammar;
import dev.secondsun.tm4e.core.registry.Registry;
import dev.secondsun.tm4e4lsp.CC65LanguageServer;
import dev.secondsun.tm4e4lsp.feature.GoToDefinitionLinkFeature;
import dev.secondsun.tm4e4lsp.util.DefaultFileService;
import dev.secondsun.tm4e4lsp.util.SymbolService;
import dev.secondsun.tm4e4lsp.util.Location;
/**
 * This is a suite of tests that test 
 *   1) symbol identification 
 *   2) symbol lookup/referencing   
 *   3) lexical scoping and 
 *   4) handling symbols becoming dirty
 */
public class SymbolsTest {
    
    private static final URI SYMBOLS_URI =URI.create("file:/C:/Users/secon/Projects/retro-lsp/target/test-classes/symbolTest/symbol.s");

    /**
     * A symbol is defined when it is the only element on a line and ends with a ":"
     * There are also anonymous symbols.
     * @throws Exception
     */
    @Test
    public void canIdentifySymbolDefinition() throws Exception {
        var symbolService = new SymbolService();
        var fileService = new DefaultFileService();

        ClassLoader classLoader = getClass().getClassLoader();
        // src/test/resources/workspace1
        File file = new File(classLoader.getResource("symbolTest").getFile());
        fileService.addSearchPath(file.toURI());


        var lines = fileService.readLines(file.listFiles((FilenameFilter) (dir, name) -> name.equalsIgnoreCase("symbol.s"))[0].toURI());
        
        symbolService.extractDefinitions(SYMBOLS_URI, lines);

        var location = symbolService.getLocation("labelDef");
        assertEquals(new Location(SYMBOLS_URI, 0,0,9), location);
        
    }

/**
     * A symbol is defined when it is the only element on a line and ends with a ":"
     * There are also anonymous symbols.
     * @throws Exception
     */
    @Test
    public void canIdentifyStructDefinition() throws Exception {
        var symbolService = new SymbolService();
        var fileService = new DefaultFileService();

        ClassLoader classLoader = getClass().getClassLoader();
        // src/test/resources/workspace1
        File file = new File(classLoader.getResource("symbolTest").getFile());
        fileService.addSearchPath(file.toURI());


        var lines = fileService.readLines(file.listFiles((FilenameFilter) (dir, name) -> name.equalsIgnoreCase("symbol.s"))[0].toURI());
        symbolService.extractDefinitions(SYMBOLS_URI, lines);

        var location = symbolService.getLocation("camera");
        assertEquals(new Location(SYMBOLS_URI, 5,0,14), location);
        
    }

@Test
/**
 * Keep disabled for debugging
 * @throws IOException
 */
public void printLines() throws Exception {
    var fileService = new DefaultFileService();
    var registry = new Registry();
    var grammar = registry.loadGrammarFromPathSync("snes.json",
    CC65LanguageServer.class.getClassLoader().getResourceAsStream("snes.json"));

        ClassLoader classLoader = getClass().getClassLoader();
        // src/test/resources/workspace1
        File file = new File(classLoader.getResource("symbolTest").getFile());
        fileService.addSearchPath(file.toURI());

     fileService.readLines(file.listFiles((FilenameFilter) (dir, name) -> name.equalsIgnoreCase("symbol.s"))[0].toURI()).forEach((line)->{
        System.out.println(line);
        Arrays.stream(grammar.tokenizeLine(line).getTokens()).map(Object::toString).forEach(System.out::println);
     });
}

/**
     * A symbol is defined when it is the only element on a line and ends with a ":"
     * There are also anonymous symbols.
     * @throws Exception
     */
    @Test
    public void canGotoSymbolDefinition() throws Exception {
        var symbolService = new SymbolService();
        var fileService = new DefaultFileService();

        ClassLoader classLoader = getClass().getClassLoader();
        // src/test/resources/workspace1
        File file = new File(classLoader.getResource("symbolTest").getFile());
        fileService.addSearchPath(file.toURI());
        
        var registry = new Registry();
        var grammar = registry.loadGrammarFromPathSync("snes.json",
        CC65LanguageServer.class.getClassLoader().getResourceAsStream("snes.json"));

        var lines = fileService.readLines(file.listFiles((FilenameFilter) (dir, name) -> name.equalsIgnoreCase("symbol.s"))[0].toURI());
        
        symbolService.extractDefinitions(SYMBOLS_URI, lines);

        var gotoDefinition = new GoToDefinitionLinkFeature(grammar, symbolService);


        var location = symbolService.getLocation("labelDef");
        assertEquals(new Location(SYMBOLS_URI, 0,0,9), location);

        var params = new TextDocumentPositionParams();
        params.position = new Position(2, 14);
        params.textDocument = new TextDocumentIdentifier(SYMBOLS_URI);
        var result = gotoDefinition.handle(params, lines);
        assertEquals(location.filename(), result.get().get(0).uri);
        assertEquals(location.line(), result.get().get(0).range.start.line);
        assertEquals(location.startIndex(), result.get().get(0).range.start.character);
        assertEquals(location.endIndex(), result.get().get(0).range.end.character);        
    }

}

