package dev.secondsun;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.net.URI;
import java.util.List;

import dev.secondsun.retro.util.CA65Scanner;
import org.junit.jupiter.api.Test;

import dev.secondsun.lsp.DocumentLinkParams;
import dev.secondsun.lsp.TextDocumentIdentifier;

import dev.secondsun.retrolsp.CC65LanguageServer;
import dev.secondsun.retrolsp.feature.DocumentLinkFeature;
import dev.secondsun.retro.util.FileService;

public class LinkToIncludedFileTest {
    
@Test
public void linkToLibSFXinMultipleWorkspaces() throws Exception {
    //Setup

    var fileService = new FileService();
    ClassLoader classLoader = getClass().getClassLoader();
    
    // src/test/resources/workspace1
    File file = new File(classLoader.getResource("workspace1").getFile());
    fileService.addSearchPath(file.toURI());
    
    // src/test/resources/workspace2
    file = new File(classLoader.getResource("workspace2").getFile());
    fileService.addSearchPath(file.toURI());

    var results = fileService.find(new URI("libSFX.i"));
    assertEquals(2, results.size());
    assertTrue(results.get(0).toString().contains("workspace1"));
    assertTrue(results.get(1).toString().contains("workspace2"));
        
    var feature = new DocumentLinkFeature( fileService);
    var params = new DocumentLinkParams();
    params.textDocument = new TextDocumentIdentifier(URI.create("file:./libSFX.i"));
    
    var result = feature.handle(params, new CA65Scanner().tokenize(".include \"libSFX.i\"")).get();
    assertEquals(2, result.size());

}

}
