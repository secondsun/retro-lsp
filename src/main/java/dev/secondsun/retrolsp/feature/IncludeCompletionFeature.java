package dev.secondsun.retrolsp.feature;

import com.google.gson.JsonObject;
import dev.secondsun.lsp.CompletionItem;
import dev.secondsun.lsp.CompletionItemKind;
import dev.secondsun.lsp.CompletionList;
import dev.secondsun.lsp.Position;
import dev.secondsun.lsp.Range;
import dev.secondsun.lsp.TextDocumentPositionParams;
import dev.secondsun.lsp.TextEdit;
import dev.secondsun.retro.util.DefaultWorkspaceIndex;
import dev.secondsun.retro.util.Util;
import dev.secondsun.retro.util.WorkspaceIndex;
import dev.secondsun.retro.util.WorkspaceSnapshot;
import dev.secondsun.retro.util.vo.TokenizedFile;
import java.net.URI;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** Feature providing autocompletion for include directives and file paths. */
public class IncludeCompletionFeature implements CompletionFeature {

  private final WorkspaceIndex workspaceIndex;

  /** Constructs a standalone {@code IncludeCompletionFeature} with a default workspace index. */
  public IncludeCompletionFeature() {
    this(new DefaultWorkspaceIndex());
  }

  /**
   * Constructs a new {@code IncludeCompletionFeature} backed by the specified {@link
   * WorkspaceIndex}.
   *
   * @param workspaceIndex workspace index providing include candidate resolution
   */
  public IncludeCompletionFeature(WorkspaceIndex workspaceIndex) {
    this.workspaceIndex = Objects.requireNonNull(workspaceIndex, "workspaceIndex must not be null");
  }

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
        || params == null
        || params.position == null
        || params.position.line < 0
        || params.position.line >= fileContent.textLines()) {
      return Optional.empty();
    }

    CompletionList list = new CompletionList();
    list.items = new ArrayList<>();
    var line = fileContent.getLineText(params.position.line);
    int col = Math.max(0, Math.min(params.position.character, line.length()));
    var stringLeftOfCursor = line.substring(0, col);

    var filePrefix = getPrefix(stringLeftOfCursor).replace('\\', '/');
    int lastSlash = filePrefix.lastIndexOf('/');
    String dirPrefix = lastSlash >= 0 ? filePrefix.substring(0, lastSlash + 1) : "";
    String namePrefix = lastSlash >= 0 ? filePrefix.substring(lastSlash + 1) : filePrefix;

    URI docUri = params.textDocument != null ? params.textDocument.uri : null;

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

      WorkspaceSnapshot snapshot = workspaceIndex.snapshot();
      for (WorkspaceSnapshot.IncludeCandidate candidate :
          snapshot.listIncludeCandidates(docUri, dirPrefix)) {
        if (!candidate.name().startsWith(namePrefix)) {
          continue;
        }

        String fullTarget = dirPrefix + candidate.name();
        String replacement =
            Util.trimCompletion(stringLeftOfCursor, ".include \"" + fullTarget + "\"");

        var item = new CompletionItem();
        if (candidate.isDirectory()) {
          item.kind = CompletionItemKind.Folder;
          item.label = candidate.name() + "/";
        } else {
          item.kind = CompletionItemKind.File;
          item.label = candidate.name();
        }
        item.textEdit =
            new TextEdit(
                new Range(
                    new Position(params.position.line, col),
                    new Position(params.position.line, endCol)),
                replacement);

        list.items.add(item);
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
        || params == null
        || params.position == null
        || params.position.line < 0
        || params.position.line >= fileContent.textLines()) {
      return false;
    }
    var line = fileContent.getLineText(params.position.line);
    int col = Math.max(0, Math.min(params.position.character, line.length()));
    var leftOfCursor = line.substring(0, col).stripLeading();
    return leftOfCursor.toUpperCase(Locale.ROOT).startsWith(".INCLUDE");
  }
}
