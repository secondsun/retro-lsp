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
import dev.secondsun.retro.util.WorkspaceIndex;
import dev.secondsun.retro.util.WorkspaceSnapshot;
import dev.secondsun.retro.util.vo.Scope;
import dev.secondsun.retro.util.vo.TokenizedFile;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Completion feature providing suggestions for pseudo-macro keywords and scoped symbols. */
public class SymbolCompletionFeature implements CompletionFeature {

  private static final List<String> PSEUDO_MACROS =
      List.of("function", "inline", "endfunction", "call", "return");

  private final WorkspaceIndex workspaceIndex;

  /** Constructs a standalone {@code SymbolCompletionFeature} with a default workspace index. */
  public SymbolCompletionFeature() {
    this(new DefaultWorkspaceIndex());
  }

  /**
   * Constructs a new {@code SymbolCompletionFeature} backed by the specified {@link
   * WorkspaceIndex}.
   *
   * @param workspaceIndex workspace index used to look up visible scopes and symbol definitions
   */
  public SymbolCompletionFeature(WorkspaceIndex workspaceIndex) {
    this.workspaceIndex = Objects.requireNonNull(workspaceIndex, "workspaceIndex must not be null");
  }

  private static boolean isIdentifierChar(char c) {
    return Character.isLetterOrDigit(c) || c == '_' || c == '@';
  }

  @Override
  public void initialize(JsonObject initializationData) {
    var completionRegistrationOptions = new JsonObject();
    completionRegistrationOptions.addProperty("resolveProvider", false);
    initializationData.add("completionProvider", completionRegistrationOptions);
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
    var leftOfCursor = line.substring(0, col);
    var trimmedLeft = leftOfCursor.stripLeading();

    if (trimmedLeft.startsWith(".")) {
      return false;
    }

    if (trimmedLeft.startsWith(";") || leftOfCursor.contains(";")) {
      return false;
    }

    long quoteCount = leftOfCursor.chars().filter(ch -> ch == '"').count();
    return quoteCount % 2 == 0;
  }

  @Override
  public Optional<CompletionList> handle(
      TextDocumentPositionParams params, TokenizedFile fileContent) {
    if (!canComplete(params, fileContent)) {
      return Optional.empty();
    }

    var line = fileContent.getLineText(params.position.line);
    int col = Math.max(0, Math.min(params.position.character, line.length()));

    int startCol = col;
    while (startCol > 0 && isIdentifierChar(line.charAt(startCol - 1))) {
      startCol--;
    }
    String prefix = line.substring(startCol, col);

    int endCol = col;
    while (endCol < line.length() && isIdentifierChar(line.charAt(endCol))) {
      endCol++;
    }

    Range replaceRange =
        new Range(
            new Position(params.position.line, startCol),
            new Position(params.position.line, endCol));

    String before = line.substring(0, startCol).stripTrailing();
    boolean isAtStatementPosition =
        before.isEmpty() || before.endsWith(":") || !before.contains(" ") && !before.contains("\t");

    List<CompletionItem> items = new ArrayList<>();
    Set<String> seen = new HashSet<>();

    // 1. Suggest pseudo-macro keywords at statement position
    if (isAtStatementPosition) {
      String lowerPrefix = prefix.toLowerCase(Locale.ROOT);
      for (String keyword : PSEUDO_MACROS) {
        if (prefix.isEmpty() || keyword.toLowerCase(Locale.ROOT).startsWith(lowerPrefix)) {
          seen.add(keyword);
          var item = new CompletionItem();
          item.label = keyword;
          item.kind = CompletionItemKind.Keyword;
          item.insertText = keyword;
          item.textEdit = new TextEdit(replaceRange, keyword);
          items.add(item);
        }
      }
    }

    // 2. Suggest symbols visible in lexical scope and across the immutable workspace snapshot
    URI uri = params.textDocument != null ? params.textDocument.uri : null;
    int lineNum = params.position.line;

    WorkspaceSnapshot snapshot = workspaceIndex.snapshot();
    if (uri != null && snapshot.file(uri).isEmpty() && !fileContent.isEmpty()) {
      snapshot = snapshot.overlay(uri, String.join("\n", fileContent.getRawLines()));
    }

    if (uri != null) {
      Optional<Scope> scopeOpt = snapshot.getScopeAt(uri, lineNum);
      if (scopeOpt.isPresent()) {
        Scope curr = scopeOpt.get();
        while (curr != null) {
          if (curr.definitions() != null) {
            for (String name : curr.definitions().keySet()) {
              addSymbolItem(name, prefix, replaceRange, items, seen);
            }
          }
          curr = curr.parent();
        }
      }
    }

    for (String name : snapshot.allSymbolNames()) {
      addSymbolItem(name, prefix, replaceRange, items, seen);
    }

    if (items.isEmpty()) {
      return Optional.empty();
    }

    var list = new CompletionList();
    list.items = items;
    return Optional.of(list);
  }

  private void addSymbolItem(
      String name,
      String prefix,
      Range replaceRange,
      List<CompletionItem> items,
      Set<String> seen) {
    if (name == null || name.isBlank()) {
      return;
    }
    if (!seen.add(name)) {
      return;
    }
    if (!prefix.isEmpty()
        && !name.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))) {
      return;
    }

    var item = new CompletionItem();
    item.label = name;
    item.kind = CompletionItemKind.Variable;
    item.insertText = name;
    item.textEdit = new TextEdit(replaceRange, name);
    items.add(item);
  }
}
