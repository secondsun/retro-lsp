package dev.secondsun.tm4e4lsp.feature;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;
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
import dev.secondsun.tm4e4lsp.util.Util;

public class IncludeCompletionFeature implements CompletionFeature {


    private static final Logger LOG = Logger.getLogger(DocumentLinkFeature.class.getName());
    private static final Gson GSON = new GsonBuilder().create();


    @Override
    public void initialize(JsonObject initializationData) {
        var completionRegistrationOptions = new JsonObject();
        completionRegistrationOptions.addProperty("resolveProvider", false);
        initializationData.add("completionProvider", completionRegistrationOptions);
    }

    @Override
    public Optional<CompletionList> handle(TextDocumentPositionParams params, List<String> fileContent) {

        if (fileContent == null || fileContent.size() < params.position.line) {
            return Optional.empty();
        }
        
        CompletionList list = new CompletionList();
        list.items = new ArrayList<>();
        var line = fileContent.get(params.position.line);
        var stringLeftOfCursor = line.substring(0, params.position.character);// I think that you can't replace the string before the cursor
        
        var filePrefix = getPrefix(stringLeftOfCursor);
        
        var parentDir = new File(params.textDocument.uri).getParentFile();
        if (Util.isIncludeDirective(line)) {
            for (File file : parentDir.listFiles(File::isDirectory)) {
                if (!file.getName().startsWith(filePrefix)){
                    continue;
                }

                String replacement = Util.trimCompletion(stringLeftOfCursor, ".include \""+file.getName()+"\"");

                var item = new CompletionItem();
                item.kind = CompletionItemKind.Folder;
                item.label = file.getName() + "/";
                item.textEdit = new TextEdit(new Range(new Position(params.position.line, params.position.character), 
                                                       new Position(params.position.line,line.length() )), 
                                                       replacement);

                list.items.add(item);
            }
            for (File file : parentDir.listFiles(File::isFile)) {
                if (!file.getName().startsWith(filePrefix)){
                    continue;
                }

                String replacement = Util.trimCompletion(stringLeftOfCursor, ".include \""+file.getName()+"\"");


                var item = new CompletionItem();
                item.kind = CompletionItemKind.File;
                item.label = file.getName();
                item.textEdit = new TextEdit(new Range(new Position(params.position.line, params.position.character), 
                                                       new Position(params.position.line,line.length() )), 
                                            replacement);

                list.items.add(item);
            }
        }
        return Optional.of(list);
    }

    private String getPrefix(String stringLeftOfCursor) {
        var testArray = stringLeftOfCursor.split("\"");
        if (testArray.length>1) {
            return testArray[1];
        } else {
            return "";
        }
        
    }

    @Override
    public boolean canComplete(TextDocumentPositionParams params, List<String> fileContent) {
        var line = fileContent.get(params.position.line).trim();
        var leftOfCursor = line.substring(0,params.position.character).trim();
        return leftOfCursor.toUpperCase().startsWith(".INCLUDE");
    }


    
}
