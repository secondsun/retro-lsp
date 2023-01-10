package dev.secondsun;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import dev.secondsun.lsp.CompletionItem;
import dev.secondsun.lsp.Position;
import dev.secondsun.lsp.Range;
import dev.secondsun.lsp.TextDocumentIdentifier;
import dev.secondsun.lsp.TextDocumentPositionParams;
import dev.secondsun.lsp.TextEdit;
import dev.secondsun.tm4e4lsp.feature.DirectiveCompletionFeature;

public class CompletionTest {
    
    public static final List<String> FILE_CONENTS = List.of("""
            .
            ;Comment
            """);

    private static final List<String> CONTROL_COMMANDS = List.of(
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
".SUNPLUS",
".TAG",
".WARNING",
".WORD",
".ZEROPAGE"
    );



    @Test
    public void testDirectiveCompletion() {
        var completionFeature = new DirectiveCompletionFeature();
        var completionList = completionFeature.handle(documentPositionParams(0, 1), FILE_CONENTS);
        var completionItems = completionList.get().items;
        
        IntStream.range(0, completionItems.size()).forEach(null);
        
    }


    private static TextDocumentPositionParams documentPositionParams(int line, int column) {
        final URI filepath = URI.create("file://bar/foo/baz.s");
        return new TextDocumentPositionParams(
            new TextDocumentIdentifier(filepath),
            new Position(line, column)
        );
    }

    public static List<CompletionItem> buildItems(int line, int column) {

        

        return CONTROL_COMMANDS.stream().map(command -> {
            var item = new CompletionItem();
                item.label = command;
                item.textEdit = new TextEdit(new Range(new Position(line, column), 
                                                       new Position(line,command.length() )), 
                                                       command);

            return item;
        }).collect(Collectors.toList());

    }

}
