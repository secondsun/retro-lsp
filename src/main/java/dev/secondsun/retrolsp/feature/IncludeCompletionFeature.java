package dev.secondsun.retrolsp.feature;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import dev.secondsun.lsp.CompletionItem;
import dev.secondsun.lsp.CompletionItemKind;
import dev.secondsun.lsp.CompletionList;
import dev.secondsun.lsp.Position;
import dev.secondsun.lsp.Range;
import dev.secondsun.lsp.TextDocumentPositionParams;
import dev.secondsun.lsp.TextEdit;
import dev.secondsun.retro.util.Util;
import dev.secondsun.retro.util.vo.TokenizedFile;
import java.io.File;
import java.util.ArrayList;
import java.util.Optional;
import java.util.logging.Logger;

/** Feature providing autocompletion for include directives and file paths. */
public class IncludeCompletionFeature implements CompletionFeature {

  private static final Logger LOG = Logger.getLogger(DocumentLinkFeature.class.getName());
  private static final Gson GSON = new GsonBuilder().create();

  /** Constructs a new {@code IncludeCompletionFeature} instance. */
  public IncludeCompletionFeature() {}

  @Override
  public void initialize(JsonObject initializationData) {
    var completionRegistrationOptions = new JsonObject();
    completionRegistrationOptions.addProperty("resolveProvider", false);
    initializationData.add("completionProvider", completionRegistrationOptions);
  }

  @Override
  public Optional<CompletionList> handle(
      TextDocumentPositionParams params, TokenizedFile fileContent) {

    if (fileContent == null
        || params.position.line < 0
        || params.position.line >= fileContent.textLines()) {
      return Optional.empty();
    }

    CompletionList list = new CompletionList();
    list.items = new ArrayList<>();
    var line = fileContent.getLineText(params.position.line);
    int col = Math.max(0, Math.min(params.position.character, line.length()));
    var stringLeftOfCursor = line.substring(0, col);

    var filePrefix = getPrefix(stringLeftOfCursor);

    File parentDir = null;
    try {
      if (params.textDocument != null && params.textDocument.uri != null) {
        var uri = params.textDocument.uri;
        if ("file".equalsIgnoreCase(uri.getScheme())) {
          parentDir = new File(uri.getPath()).getParentFile();
        } else {
          parentDir = new File(uri).getParentFile();
        }
      }
    } catch (Exception ignore) {
    }

    if (Util.isIncludeDirective(line)) {
      int endCol = col;
      while (endCol < line.length()
          && (Character.isLetterOrDigit(line.charAt(endCol))
              || line.charAt(endCol) == '.'
              || line.charAt(endCol) == '_')) {
        endCol++;
      }
      if (endCol < line.length() && line.charAt(endCol) == '"') {
        endCol++;
      }

      var dirFiles = parentDir != null ? parentDir.listFiles(File::isDirectory) : null;
      if (dirFiles != null) {
        for (File file : dirFiles) {
          if (!file.getName().startsWith(filePrefix)) {
            continue;
          }

          String replacement =
              Util.trimCompletion(stringLeftOfCursor, ".include \"" + file.getName() + "\"");

          var item = new CompletionItem();
          item.kind = CompletionItemKind.Folder;
          item.label = file.getName() + "/";
          item.textEdit =
              new TextEdit(
                  new Range(
                      new Position(params.position.line, col),
                      new Position(params.position.line, endCol)),
                  replacement);

          list.items.add(item);
        }
      }

      var regularFiles = parentDir != null ? parentDir.listFiles(File::isFile) : null;
      if (regularFiles != null) {
        for (File file : regularFiles) {
          if (!file.getName().startsWith(filePrefix)) {
            continue;
          }

          String replacement =
              Util.trimCompletion(stringLeftOfCursor, ".include \"" + file.getName() + "\"");

          var item = new CompletionItem();
          item.kind = CompletionItemKind.File;
          item.label = file.getName();
          item.textEdit =
              new TextEdit(
                  new Range(
                      new Position(params.position.line, col),
                      new Position(params.position.line, endCol)),
                  replacement);

          list.items.add(item);
        }
      }
    }
    return Optional.of(list);
  }

  private String getPrefix(String stringLeftOfCursor) {
    var testArray = stringLeftOfCursor.split("\"");
    if (testArray.length > 1) {
      return testArray[1];
    } else {
      return "";
    }
  }

  @Override
  public boolean canComplete(TextDocumentPositionParams params, TokenizedFile fileContent) {
    if (fileContent == null
        || params.position.line < 0
        || params.position.line >= fileContent.textLines()) {
      return false;
    }
    var line = fileContent.getLineText(params.position.line);
    int col = Math.max(0, Math.min(params.position.character, line.length()));
    var leftOfCursor = line.substring(0, col).stripLeading();
    return leftOfCursor.toUpperCase().startsWith(".INCLUDE");
  }
}
