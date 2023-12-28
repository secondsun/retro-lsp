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
import dev.secondsun.retrolsp.feature.DirectiveCompletionFeature;

public class CompletionTest {
    
    public static final List<String> FILE_CONENTS = List.of("""
            .
            ;Comment
            """);

    @Test
    public void testDirectiveCompletion() {
        var completionFeature = new DirectiveCompletionFeature();
        var completionList = completionFeature.handle(documentPositionParams(0, 1), FILE_CONENTS);
        var completionItems = completionList.get().items;
        
        IntStream.range(0, completionItems.size()).forEach(idx -> {
            assertEquals(DirectiveCompletionFeature.CONTROL_COMMANDS.get(idx).substring(1), completionItems.get(idx).textEdit.newText);
        });
        

    }


    private static TextDocumentPositionParams documentPositionParams(int line, int column) {
        final URI filepath = URI.create("file://bar/foo/baz.s");
        return new TextDocumentPositionParams(
            new TextDocumentIdentifier(filepath),
            new Position(line, column)
        );
    }

    public static List<CompletionItem> buildItems(int line, int column) {

        

        return DirectiveCompletionFeature.CONTROL_COMMANDS.stream().map(command -> {
            var item = new CompletionItem();
                item.label = command;
                item.textEdit = new TextEdit(new Range(new Position(line, column), 
                                                       new Position(line,command.length() )), 
                                                       command);

            return item;
        }).collect(Collectors.toList());

    }

}
