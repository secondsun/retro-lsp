package dev.secondsun.tm4e4lsp.feature;

import java.util.List;
import java.util.Optional;

import com.google.gson.JsonObject;

import dev.secondsun.lsp.CompletionList;
import dev.secondsun.lsp.TextDocumentPositionParams;

public class DirectiveCompletionFeature implements CompletionFeature {

    @Override
    public void initialize(JsonObject initializationData) {
        // TODO Auto-generated method stub
        
    }

    @Override
    public Optional<CompletionList> handle(TextDocumentPositionParams params, List<String> fileContent) {
        // TODO Auto-generated method stub
        return Optional.empty();
    }

    @Override
    public boolean canComplete(TextDocumentPositionParams params, List<String> fileContent) {
        // TODO Auto-generated method stub
        return false;
    }

}
