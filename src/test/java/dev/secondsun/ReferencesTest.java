package dev.secondsun;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.secondsun.lsp.InitializeParams;
import dev.secondsun.lsp.Position;
import dev.secondsun.lsp.ReferenceContext;
import dev.secondsun.lsp.ReferenceParams;
import dev.secondsun.lsp.TextDocumentIdentifier;
import dev.secondsun.retro.util.CA65Scanner;
import dev.secondsun.retro.util.DefaultWorkspaceIndex;
import dev.secondsun.retrolsp.CA65LanguageServer;
import dev.secondsun.retrolsp.feature.FindReferencesFeature;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

public class ReferencesTest {

  private static ReferenceParams createParams(URI uri, int line, int col, boolean includeDecl) {
    var params = new ReferenceParams();
    params.textDocument = new TextDocumentIdentifier(uri);
    params.position = new Position(line, col);
    var ctx = new ReferenceContext();
    ctx.includeDeclaration = includeDecl;
    params.context = ctx;
    return params;
  }

  @Test
  public void testFindReferencesWithNullFile() {
    var projectService = new DefaultWorkspaceIndex();
    var feature = new FindReferencesFeature(projectService);
    var params = createParams(URI.create("file:///test.s"), 0, 0, false);

    var result = feature.handle(params, null);
    assertNotNull(result);
    assertTrue(result.isEmpty());
  }

  @Test
  public void testFindReferencesLineOutOfBounds() {
    var projectService = new DefaultWorkspaceIndex();
    var feature = new FindReferencesFeature(projectService);
    var fileContent = new CA65Scanner().tokenize("label:\n");
    var params = createParams(URI.create("file:///test.s"), 10, 0, false);

    var result = feature.handle(params, fileContent);
    assertNotNull(result);
    assertTrue(result.isEmpty());
  }

  @Test
  public void testServerAdvertisesReferencesProvider() {
    CA65LanguageServer server = new CA65LanguageServer();
    InitializeParams initParams = new InitializeParams();
    var initResult = server.initialize(initParams);
    assertNotNull(initResult);
    assertNotNull(initResult.capabilities);
    assertTrue(initResult.capabilities.has("referencesProvider"));
  }

  @Test
  public void testFindReferencesForFunctionAcrossFiles() {
    var projectService = new DefaultWorkspaceIndex();
    var sqrtUri = URI.create("file:///gsu_sqrt.i");
    var vectorUri = URI.create("file:///gsu_vector.i");

    projectService.openOrUpdateBuffer(
        sqrtUri,
        """
        function gsu_sqrt32 dividendhi, dividendlo : root
            with dividendhi
            rol
            return root
        endfunction
        """);
    projectService.openOrUpdateBuffer(
        vectorUri,
        """
        function vector3_length v1 : len
            register val, mult_lo
            call gsu_sqrt32 val, mult_lo : len
            return len
        endfunction
        """);

    var feature = new FindReferencesFeature(projectService);
    var sqrtFile = projectService.getFileContents(sqrtUri);

    // Exclude declaration: only the call in gsu_vector.i (line 2) is returned
    var withoutDecl = feature.handle(createParams(sqrtUri, 0, 12, false), sqrtFile);
    assertTrue(withoutDecl.isPresent());
    assertEquals(1, withoutDecl.get().size());
    assertEquals(vectorUri, withoutDecl.get().get(0).uri);
    assertEquals(2, withoutDecl.get().get(0).range.start.line);

    // Include declaration: both definition (line 0 of gsu_sqrt.i) and call (line 2 of gsu_vector.i)
    var withDecl = feature.handle(createParams(sqrtUri, 0, 12, true), sqrtFile);
    assertTrue(withDecl.isPresent());
    assertEquals(2, withDecl.get().size());
  }

  @Test
  public void testFindReferencesLocalLabelsAndScopedParameters() {
    var projectService = new DefaultWorkspaceIndex();
    var uri = URI.create("file:///scoped.s");
    projectService.openOrUpdateBuffer(
        uri,
        """
        function func1 param1 : res
        @loop:
            with param1
            bra @loop
            return res
        endfunction
        function func2 param1 : res
        @loop:
            with param1
            bra @loop
            return res
        endfunction
        """);

    var feature = new FindReferencesFeature(projectService);
    var file = projectService.getFileContents(uri);

    // @loop in func1 (line 1, col 2) -> only line 3 in func1
    var loop1Refs = feature.handle(createParams(uri, 1, 2, false), file);
    assertTrue(loop1Refs.isPresent());
    assertEquals(1, loop1Refs.get().size());
    assertEquals(3, loop1Refs.get().get(0).range.start.line);

    // param1 in func2 (line 6, col 17) -> only line 8 in func2
    var param2Refs = feature.handle(createParams(uri, 6, 17, false), file);
    assertTrue(param2Refs.isPresent());
    assertEquals(1, param2Refs.get().size());
    assertEquals(8, param2Refs.get().get(0).range.start.line);
  }

  @Test
  public void testFindReferencesStructFields() {
    var projectService = new DefaultWorkspaceIndex();
    var uri = URI.create("file:///structs.s");
    projectService.openOrUpdateBuffer(
        uri,
        """
        .struct Point
            coordX .word
            coordY .word
        .endstruct
        .struct Other
            coordX .word
        .endstruct
            lda Point::coordX
            lda Other::coordX
        """);

    var feature = new FindReferencesFeature(projectService);
    var file = projectService.getFileContents(uri);

    // Right-click on coordX inside .struct Point (line 1, col 6) -> only line 7 (Point::coordX)
    var pointFieldRefs = feature.handle(createParams(uri, 1, 6, false), file);
    assertTrue(pointFieldRefs.isPresent());
    assertEquals(1, pointFieldRefs.get().size());
    assertEquals(7, pointFieldRefs.get().get(0).range.start.line);

    // Right-click on Point::coordX at usage site (line 7, col 16) with includeDeclaration = true
    var fromUsageWithDecl = feature.handle(createParams(uri, 7, 16, true), file);
    assertTrue(fromUsageWithDecl.isPresent());
    assertEquals(2, fromUsageWithDecl.get().size());
  }

  @Test
  public void testGsuSqrt32ReferencesViaLanguageServerAgainstRealDemo() {
    Path demoRoot = Path.of("../snes-sfx-demo/X-GSU").toAbsolutePath().normalize();
    Path sqrtFile = demoRoot.resolve("gsu_maths/gsu_sqrt.i");
    Path vectorFile = demoRoot.resolve("gsu_maths/gsu_vector.i");
    if (!Files.exists(sqrtFile) || !Files.exists(vectorFile)) {
      return;
    }

    CA65LanguageServer server = new CA65LanguageServer();
    InitializeParams initParams = new InitializeParams();
    initParams.rootUri = demoRoot.toUri();
    server.initialize(initParams);
    server.waitForIndexing();

    // Right-click on gsu_sqrt32 in gsu_sqrt.i line 18 (0-based line 17, col 12)
    ReferenceParams refParams = createParams(sqrtFile.toUri(), 17, 12, false);
    var refsOpt = server.findReferences(refParams);
    assertNotNull(refsOpt);
    assertTrue(refsOpt.isPresent());

    List<dev.secondsun.lsp.Location> refs = refsOpt.get();
    assertFalse(refs.isEmpty());

    // Must contain call gsu_sqrt32 at line 538 (0-based line 537) in gsu_vector.i
    boolean foundVectorCall =
        refs.stream()
            .anyMatch(
                loc ->
                    vectorFile.toUri().equals(loc.uri)
                        && loc.range.start.line == 537
                        && loc.range.start.character == 7
                        && loc.range.end.character == 17);
    assertTrue(
        foundVectorCall,
        "Expected reference to gsu_sqrt32 at line 538 (0-based 537) of gsu_vector.i, got: " + refs);
  }
}
