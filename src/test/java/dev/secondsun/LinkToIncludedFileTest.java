package dev.secondsun;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.secondsun.lsp.DocumentLinkParams;
import dev.secondsun.lsp.TextDocumentIdentifier;
import dev.secondsun.retro.util.CA65Scanner;
import dev.secondsun.retro.util.DefaultWorkspaceIndex;
import dev.secondsun.retrolsp.feature.DocumentLinkFeature;
import java.io.File;
import java.net.URI;
import org.junit.jupiter.api.Test;

public class LinkToIncludedFileTest {

  @Test
  public void linkToLibSFXinMultipleWorkspaces() throws Exception {
    var workspaceIndex = new DefaultWorkspaceIndex();
    ClassLoader classLoader = getClass().getClassLoader();

    // src/test/resources/workspace1
    File file = new File(classLoader.getResource("workspace1").getFile());
    workspaceIndex.includeDir(file.toURI());

    // src/test/resources/workspace2
    file = new File(classLoader.getResource("workspace2").getFile());
    workspaceIndex.includeDir(file.toURI());

    URI docUri = URI.create("file:./libSFX.i");
    var results = workspaceIndex.snapshot().findIncludeUris(docUri, "libSFX.i");
    assertEquals(2, results.size());
    assertTrue(results.stream().anyMatch(it -> it.toString().contains("workspace1")));
    assertTrue(results.stream().anyMatch(it -> it.toString().contains("workspace2")));

    var feature = new DocumentLinkFeature(workspaceIndex);
    var params = new DocumentLinkParams();
    params.textDocument = new TextDocumentIdentifier(docUri);

    var result = feature.handle(params, new CA65Scanner().tokenize(".include \"libSFX.i\"")).get();
    assertEquals(2, result.size());
  }

  @Test
  public void testIncompleteIncludeDirectiveDoesNotCrash() {
    var workspaceIndex = new DefaultWorkspaceIndex();
    var feature = new DocumentLinkFeature(workspaceIndex);
    var params = new DocumentLinkParams();
    params.textDocument = new TextDocumentIdentifier(URI.create("file:///test.s"));

    var result = feature.handle(params, new CA65Scanner().tokenize(".include \n"));
    assertTrue(result.isPresent());
    assertTrue(result.get().isEmpty());
  }

  @Test
  public void testIncludeWithSpacesDoesNotCrash() {
    var workspaceIndex = new DefaultWorkspaceIndex();
    var feature = new DocumentLinkFeature(workspaceIndex);
    var params = new DocumentLinkParams();
    params.textDocument = new TextDocumentIdentifier(URI.create("file:///test.s"));

    var result =
        feature.handle(params, new CA65Scanner().tokenize(".include \"my test file.s\"\n"));
    assertTrue(result.isPresent());
  }

  @Test
  public void testNullFileContentDoesNotCrash() {
    var workspaceIndex = new DefaultWorkspaceIndex();
    var feature = new DocumentLinkFeature(workspaceIndex);
    var params = new DocumentLinkParams();
    params.textDocument = new TextDocumentIdentifier(URI.create("file:///test.s"));

    var result = feature.handle(params, null);
    assertTrue(result.isPresent());
    assertTrue(result.get().isEmpty());
  }
}
