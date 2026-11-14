package dev.secondsun.retrolsp.feature;

import com.google.gson.JsonObject;
import dev.secondsun.lsp.CompletionItem;
import dev.secondsun.lsp.CompletionItemKind;
import dev.secondsun.lsp.CompletionList;
import dev.secondsun.lsp.Position;
import dev.secondsun.lsp.Range;
import dev.secondsun.lsp.TextDocumentPositionParams;
import dev.secondsun.lsp.TextEdit;
import dev.secondsun.retro.util.Util;
import dev.secondsun.retro.util.vo.DotKeywords;
import dev.secondsun.retro.util.vo.TokenizedFile;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Completion feature providing suggestions for CA65 assembler control directives and commands. */
public class DirectiveCompletionFeature implements CompletionFeature {

  /**
   * List of CA65 assembler control directives supported for autocompletion, validated against
   * {@link DotKeywords}.
   */
  public static final List<String> CONTROL_COMMANDS =
      Stream.of(
              ".A16",
              ".A8",
              ".ADDR",
              ".ALIGN",
              ".ASCIIZ",
              ".ASSERT",
              ".AUTOIMPORT",
              ".BANKBYTES",
              ".BSS",
              ".BYT",
              ".BYTE",
              ".CASE",
              ".CHARMAP",
              ".CODE",
              ".CONDES",
              ".CONSTRUCTOR",
              ".DATA",
              ".DBYT",
              ".DEBUGINFO",
              ".DEFINE",
              ".DEF",
              ".DEFINED",
              ".DESTRUCTOR",
              ".DWORD",
              ".ELSE",
              ".ELSEIF",
              ".END",
              ".ENDENUM",
              ".ENDIF",
              ".ENDMAC",
              ".ENDMACRO",
              ".ENDPROC",
              ".ENDREP",
              ".ENDREPEAT",
              ".ENDSCOPE",
              ".ENDSTRUCT",
              ".ENUM",
              ".ERROR",
              ".EXITMAC",
              ".EXITMACRO",
              ".EXPORT",
              ".EXPORTZP",
              ".FARADDR",
              ".FEATURE",
              ".FILEOPT",
              ".FOPT",
              ".FORCEIMPORT",
              ".GLOBAL",
              ".GLOBALZP",
              ".HIBYTES",
              ".I16",
              ".I8",
              ".IF",
              ".IFBLANK",
              ".IFCONST",
              ".IFDEF",
              ".IFNBLANK",
              ".IFNDEF",
              ".IFNREF",
              ".IFP02",
              ".IFP816",
              ".IFPC02",
              ".IFPSC02",
              ".IFREF",
              ".IMPORT",
              ".IMPORTZP",
              ".INCBIN",
              ".INCLUDE",
              ".INTERRUPTOR",
              ".LINECONT",
              ".LIST",
              ".LISTBYTES",
              ".LOBYTES",
              ".LOCAL",
              ".LOCALCHAR",
              ".MACPACK",
              ".MAC",
              ".MACRO",
              ".ORG",
              ".OUT",
              ".P02",
              ".P816",
              ".PAGELEN",
              ".PAGELENGTH",
              ".PC02",
              ".POPSEG",
              ".PROC",
              ".PSC02",
              ".PUSHSEG",
              ".RELOC",
              ".REPEAT",
              ".RES",
              ".RODATA",
              ".SCOPE",
              ".SEGMENT",
              ".SETCPU",
              ".SMART",
              ".STRUCT",
              ".TAG",
              ".WARNING",
              ".WORD",
              ".ZEROPAGE")
          .filter(cmd -> DotKeywords.fromText(cmd) != null)
          .toList();

  /** Constructs a new {@code DirectiveCompletionFeature} instance. */
  public DirectiveCompletionFeature() {}

  @Override
  public void initialize(JsonObject initializationData) {}

  @Override
  public Optional<CompletionList> handle(
      TextDocumentPositionParams params, TokenizedFile fileContent) {

    if (fileContent == null
        || params.position.line < 0
        || params.position.line >= fileContent.textLines()) {
      return Optional.empty();
    }

    var line = fileContent.getLineText(params.position.line);
    int col = Math.max(0, Math.min(params.position.character, line.length()));
    var leftOfCursor = line.substring(0, col).stripLeading();
    if (leftOfCursor.startsWith(".")) {
      String upperPrefix = leftOfCursor.toUpperCase(Locale.ROOT);
      var completionItems =
          CONTROL_COMMANDS.stream()
              .filter(cmd -> cmd.startsWith(upperPrefix))
              .map(
                  text -> {
                    var completionText = Util.trimCompletion(leftOfCursor, text);
                    var item = new CompletionItem();
                    item.label = text;
                    item.kind = CompletionItemKind.Struct;

                    int endCol = col;
                    while (endCol < line.length()
                        && Character.isLetterOrDigit(line.charAt(endCol))) {
                      endCol++;
                    }

                    item.textEdit =
                        new TextEdit(
                            new Range(
                                new Position(params.position.line, col),
                                new Position(params.position.line, endCol)),
                            completionText);
                    return item;
                  })
              .collect(Collectors.toList());

      var list = new CompletionList();
      list.items = completionItems;

      return Optional.of(list);
    }

    return Optional.empty();
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
    if (!leftOfCursor.startsWith(".")) {
      return false;
    }
    String upperPrefix = leftOfCursor.toUpperCase(Locale.ROOT);
    return CONTROL_COMMANDS.stream().anyMatch(string -> string.startsWith(upperPrefix));
  }
}
