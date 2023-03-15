package dev.secondsun;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.net.URI;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.gson.GsonBuilder;

import dev.secondsun.tm4e.core.grammar.IGrammar;
import dev.secondsun.tm4e.core.registry.Registry;
import dev.secondsun.tm4e4lsp.CC65LanguageServer;
import dev.secondsun.tm4e4lsp.util.DefaultFileService;

/**
 * This is a suite of tests that test 
 *   1) symbol identification 
 *   2) symbol lookup/referencing   
 *   3) lexical scoping and 
 *   4) handling symbols becoming dirty
 */
public class SymbolsTest {
    
    /**
     * A symbol is defined when it is the only element on a line and ends with a ":"
     * There are also anonymous symbols.
     * @throws Exception
     */
    @Test
    public void canIdentifySymbolDefinition() throws Exception {
        var registry = new Registry();
        IGrammar grammar = registry.loadGrammarFromPathSync("snes.json",
               CC65LanguageServer.class.getClassLoader().getResourceAsStream("snes.json"));
        
        var fileService = new DefaultFileService();

        ClassLoader classLoader = getClass().getClassLoader();
        // src/test/resources/workspace1
        File file = new File(classLoader.getResource("symbolTest").getFile());
        fileService.addSearchPath(file.toURI());

        System.out.println(file.listFiles((FilenameFilter) (dir, name) -> name.equalsIgnoreCase("symbol.s"))[0].toURI());

        var lines = fileService.readLines(file.listFiles((FilenameFilter) (dir, name) -> name.equalsIgnoreCase("symbol.s"))[0].toURI());
        
        lines.forEach((it)->{
            System.out.println(new GsonBuilder().create().toJson(grammar.tokenizeLine(it)));
        });

        assertTrue(lines.get(0).substring(0, 8).equals("labelDef"));
        
        

    }

}
