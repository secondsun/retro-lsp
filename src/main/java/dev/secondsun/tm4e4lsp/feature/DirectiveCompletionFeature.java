package dev.secondsun.tm4e4lsp.feature;

import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collector;
import java.util.stream.Collectors;

import com.google.gson.JsonObject;

import dev.secondsun.lsp.CompletionItem;
import dev.secondsun.lsp.CompletionItemKind;
import dev.secondsun.lsp.CompletionList;
import dev.secondsun.lsp.Position;
import dev.secondsun.lsp.Range;
import dev.secondsun.lsp.TextDocumentPositionParams;
import dev.secondsun.lsp.TextEdit;
import dev.secondsun.tm4e4lsp.util.Util;

public class DirectiveCompletionFeature implements CompletionFeature {

    public static final List<String> CONTROL_COMMANDS = List.of(
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
    private Logger LOG = Logger.getLogger(DirectiveCompletionFeature.class.getName());

    @Override
    public void initialize(JsonObject initializationData) {
        // TODO Auto-generated method stub
        
    }

    @Override
    public Optional<CompletionList> handle(TextDocumentPositionParams params, List<String> fileContent) {
        
        var line = fileContent.get(params.position.line);
        LOG.log(Level.INFO, "line:"+line);
        var leftOfCursor = line.substring(0,params.position.character).trim();
        LOG.log(Level.INFO, "left:"+leftOfCursor+"\n");
        if (leftOfCursor.startsWith(".")) {
           var completionItems = CONTROL_COMMANDS.stream()
                                    .filter(cmd->cmd.startsWith(leftOfCursor.toUpperCase()))
                                    .map(text->
                                    {
                                        var completionText = Util.trimCompletion(leftOfCursor, text);
                                        var item = new CompletionItem();
                                        item.label = text;
                                        item.kind = CompletionItemKind.Struct;
                                        LOG.log(Level.INFO, text);
                                        LOG.log(Level.INFO, completionText);
                                        item.textEdit = new TextEdit(new Range(new Position(params.position.line, params.position.character), 
                                                                               new Position(params.position.line,line.length() )), 
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
    public boolean canComplete(TextDocumentPositionParams params, List<String> fileContent) {
        var line = fileContent.get(params.position.line).trim();
        var leftOfCursor = line.substring(0,params.position.character).trim();
        return !CONTROL_COMMANDS.stream().filter(string -> string.startsWith(leftOfCursor.toUpperCase())).findAny().isEmpty();
    }

}
