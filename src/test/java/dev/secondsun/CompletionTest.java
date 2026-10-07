package dev.secondsun;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.secondsun.lsp.CompletionItem;
import dev.secondsun.lsp.Position;
import dev.secondsun.lsp.Range;
import dev.secondsun.lsp.TextDocumentIdentifier;
import dev.secondsun.lsp.TextDocumentPositionParams;
import dev.secondsun.lsp.TextEdit;
import dev.secondsun.retro.util.CA65Scanner;
import dev.secondsun.retrolsp.feature.DirectiveCompletionFeature;
import java.net.URI;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

public class CompletionTest {

  public static final String FILE_CONENTS =
      """
            .
            ;Comment
            """;

  @Test
  public void testDirectiveCompletion() {
    var completionFeature = new DirectiveCompletionFeature();

    var completionList =
        completionFeature.handle(
            documentPositionParams(0, 1), new CA65Scanner().tokenize(FILE_CONENTS));
    var completionItems = completionList.get().items;

    IntStream.range(0, completionItems.size())
        .forEach(
            idx -> {
              assertEquals(
                  DirectiveCompletionFeature.CONTROL_COMMANDS.get(idx).substring(1),
                  completionItems.get(idx).textEdit.newText);
            });
  }

  @Test
  public void testDirectiveCompletionWithIndentation() {
    var completionFeature = new DirectiveCompletionFeature();
    var fileContent = new CA65Scanner().tokenize("    .by\n");
    // Indented line: 4 spaces followed by .by. Cursor is at column 7.
    boolean canComplete = completionFeature.canComplete(documentPositionParams(0, 7), fileContent);
    org.junit.jupiter.api.Assertions.assertTrue(canComplete);

    var completionList = completionFeature.handle(documentPositionParams(0, 7), fileContent);
    org.junit.jupiter.api.Assertions.assertTrue(completionList.isPresent());
    org.junit.jupiter.api.Assertions.assertFalse(completionList.get().items.isEmpty());
  }

  @Test
  public void testDirectiveCompletionDoesNotCompleteOnWhitespace() {
    var completionFeature = new DirectiveCompletionFeature();
    var fileContent = new CA65Scanner().tokenize("    \n");
    // Only whitespace on the line. Cursor is at column 4.
    boolean canComplete = completionFeature.canComplete(documentPositionParams(0, 4), fileContent);
    org.junit.jupiter.api.Assertions.assertFalse(
        canComplete, "Directive completion should not match empty/whitespace lines");
  }

  @Test
  public void testDirectiveCompletionPreservesTrailingContent() {
    var completionFeature = new DirectiveCompletionFeature();
    var fileContent = new CA65Scanner().tokenize(".byt   $12, $34\n");
    // Cursor at col 4 (after .byt). Trailing content: "   $12, $34"
    var completionList = completionFeature.handle(documentPositionParams(0, 4), fileContent);
    org.junit.jupiter.api.Assertions.assertTrue(completionList.isPresent());
    var items = completionList.get().items;
    org.junit.jupiter.api.Assertions.assertFalse(items.isEmpty());
    for (var item : items) {
      org.junit.jupiter.api.Assertions.assertNotEquals(
          fileContent.getLineText(0).length(),
          item.textEdit.range.end.character,
          "TextEdit should not erase the rest of the line");
    }
  }

  @Test
  public void testIncludeCompletionWithIndentation() {
    var includeFeature = new dev.secondsun.retrolsp.feature.IncludeCompletionFeature();
    var fileContent = new CA65Scanner().tokenize("    .include \"lib\"\n");
    // Indented line: 4 spaces followed by .include "lib". Cursor at column 17.
    boolean canComplete = includeFeature.canComplete(documentPositionParams(0, 17), fileContent);
    org.junit.jupiter.api.Assertions.assertTrue(canComplete);
  }

  @Test
  public void testIncludeCompletionLineOutOfBounds() {
    var includeFeature = new dev.secondsun.retrolsp.feature.IncludeCompletionFeature();
    var fileContent = new CA65Scanner().tokenize(".include \"lib\"\n");
    // Request is for line index equal to textLines() (out of bounds).
    var result =
        includeFeature.handle(documentPositionParams(fileContent.textLines(), 0), fileContent);
    org.junit.jupiter.api.Assertions.assertTrue(result.isEmpty());
  }

  @Test
  public void testIncludeCompletionNonExistentDirectory() {
    var includeFeature = new dev.secondsun.retrolsp.feature.IncludeCompletionFeature();
    var fileContent = new CA65Scanner().tokenize(".include \"\n");
    var params =
        new TextDocumentPositionParams(
            new TextDocumentIdentifier(URI.create("file:///non/existent/path/test.s")),
            new Position(0, 10));
    var result = includeFeature.handle(params, fileContent);
    org.junit.jupiter.api.Assertions.assertTrue(result.isPresent());
  }

  @Test
  public void testIncludeCompletionPreservesTrailingComment() throws java.io.IOException {
    var includeFeature = new dev.secondsun.retrolsp.feature.IncludeCompletionFeature();
    var fileContent = new CA65Scanner().tokenize(".include \"test\" ; keep this comment\n");
    var testFile = TestUtils.getTestFile("test.sgs");
    var params =
        new TextDocumentPositionParams(new TextDocumentIdentifier(testFile), new Position(0, 14));
    var result = includeFeature.handle(params, fileContent);
    org.junit.jupiter.api.Assertions.assertTrue(result.isPresent());
    var items = result.get().items;
    org.junit.jupiter.api.Assertions.assertFalse(items.isEmpty());
    for (var item : items) {
      org.junit.jupiter.api.Assertions.assertNotEquals(
          fileContent.getLineText(0).length(),
          item.textEdit.range.end.character,
          "TextEdit should not erase the rest of the line");
    }
  }

  @Test
  public void testPseudoMacroCompletion() {
    var symbolService = new dev.secondsun.retro.util.SymbolService();
    var feature = new dev.secondsun.retrolsp.feature.SymbolCompletionFeature(symbolService);

    var fileContent = new CA65Scanner().tokenize("    func\n");
    var params = documentPositionParams(0, 8);
    var result = feature.handle(params, fileContent);

    org.junit.jupiter.api.Assertions.assertTrue(result.isPresent());
    var labels = result.get().items.stream().map(it -> it.label).toList();
    org.junit.jupiter.api.Assertions.assertTrue(labels.contains("function"));

    // Complete "ca" -> "call"
    var fileCall = new CA65Scanner().tokenize("    ca\n");
    var resCall = feature.handle(documentPositionParams(0, 6), fileCall);
    org.junit.jupiter.api.Assertions.assertTrue(resCall.isPresent());
    var callLabels = resCall.get().items.stream().map(it -> it.label).toList();
    org.junit.jupiter.api.Assertions.assertTrue(callLabels.contains("call"));

    // Complete "ret" -> "return"
    var fileRet = new CA65Scanner().tokenize("    ret\n");
    var resRet = feature.handle(documentPositionParams(0, 7), fileRet);
    org.junit.jupiter.api.Assertions.assertTrue(resRet.isPresent());
    var retLabels = resRet.get().items.stream().map(it -> it.label).toList();
    org.junit.jupiter.api.Assertions.assertTrue(retLabels.contains("return"));

    // Complete "endf" -> "endfunction"
    var fileEndf = new CA65Scanner().tokenize("    endf\n");
    var resEndf = feature.handle(documentPositionParams(0, 8), fileEndf);
    org.junit.jupiter.api.Assertions.assertTrue(resEndf.isPresent());
    var endfLabels = resEndf.get().items.stream().map(it -> it.label).toList();
    org.junit.jupiter.api.Assertions.assertTrue(endfLabels.contains("endfunction"));
  }

  @Test
  public void testScopeAwareSymbolCompletion() {
    var uri = URI.create("file:///test.s");
    var scanner = new CA65Scanner();
    var src =
        """
        globalVar = $1234
        function myFunc param1 : retVal
        localVar = $5678
            lda\s
        endfunction
        """;
    var fileContent = scanner.tokenize(src);
    fileContent.uri = uri;
    var symbolService = new dev.secondsun.retro.util.SymbolService();
    symbolService.extractDefinitions(fileContent);

    var feature = new dev.secondsun.retrolsp.feature.SymbolCompletionFeature(symbolService);

    // Inside myFunc (line 3, col 8)
    var params =
        new TextDocumentPositionParams(new TextDocumentIdentifier(uri), new Position(3, 8));
    var result = feature.handle(params, fileContent);

    org.junit.jupiter.api.Assertions.assertTrue(result.isPresent());
    var labels = result.get().items.stream().map(it -> it.label).toList();
    org.junit.jupiter.api.Assertions.assertTrue(labels.contains("param1"));
    org.junit.jupiter.api.Assertions.assertTrue(labels.contains("retVal"));
    org.junit.jupiter.api.Assertions.assertTrue(labels.contains("localVar"));
    org.junit.jupiter.api.Assertions.assertTrue(labels.contains("globalVar"));
  }

  private static TextDocumentPositionParams documentPositionParams(int line, int column) {
    final URI filepath = URI.create("file://bar/foo/baz.s");
    return new TextDocumentPositionParams(
        new TextDocumentIdentifier(filepath), new Position(line, column));
  }

  public static List<CompletionItem> buildItems(int line, int column) {

    return DirectiveCompletionFeature.CONTROL_COMMANDS.stream()
        .map(
            command -> {
              var item = new CompletionItem();
              item.label = command;
              item.textEdit =
                  new TextEdit(
                      new Range(new Position(line, column), new Position(line, command.length())),
                      command);

              return item;
            })
        .collect(Collectors.toList());
  }
}
