package dev.secondsun;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import dev.secondsun.lsp.DocumentLinkParams;
import dev.secondsun.lsp.TextDocumentIdentifier;
import dev.secondsun.tm4e.core.grammar.IGrammar;
import dev.secondsun.tm4e.core.registry.Registry;
import dev.secondsun.tm4e4lsp.CC65LanguageServer;
import dev.secondsun.tm4e4lsp.feature.DocumentLinkFeature;
import dev.secondsun.tm4e4lsp.util.DefaultFileService;

public class LinkToIncludedFileTest {
    
@Test
public void linkToLibSFXinMultipleWorkspaces() throws Exception {
    //Setup
    var registry = new Registry();
    IGrammar grammar = registry.loadGrammarFromPathSync("snes.json",
           CC65LanguageServer.class.getClassLoader().getResourceAsStream("snes.json"));
    
    var fileService = new DefaultFileService(){@Override
    public List<String> readLines(URI fileUri, URI workSpaceUri) throws IOException {
        return List.of(".include \"libSFX.i\"");
    }};
    ClassLoader classLoader = getClass().getClassLoader();
    
    // src/test/resources/workspace1
    File file = new File(classLoader.getResource("workspace1").getFile());
    fileService.addRepository(file.toURI());
    
    // src/test/resources/workspace2
    file = new File(classLoader.getResource("workspace2").getFile());
    fileService.addRepository(file.toURI());

    var results = fileService.find(new URI("libSFX.i"));
    assertEquals(2, results.size());
    assertTrue(results.get(0).toString().contains("workspace1"));
    assertTrue(results.get(1).toString().contains("workspace2"));
        
    var feature = new DocumentLinkFeature(grammar, fileService);
    var params = new DocumentLinkParams();
    params.textDocument = new TextDocumentIdentifier(URI.create("libSFX.i"));
    
    var result = feature.handle(params, fileService.readLines(null, null)).get();
    assertEquals(2, result.size());

}

@Test
public void canAddAndRemoveWorkspaces() {
    Assertions.fail("DocumentLink needs to be able to resolve to both the libSFX includes directory and relative to the current file. ");
}

}
