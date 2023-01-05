package dev.secondsun.tm4e4lsp.feature;

import java.util.List;
import java.util.Optional;

import com.google.gson.JsonObject;

import dev.secondsun.lsp.CompletionList;
import dev.secondsun.lsp.TextDocumentPositionParams;

public class IncludeCompletionFeature implements Feature<TextDocumentPositionParams, Optional<CompletionList>>{

    @Override
    public void initialize(JsonObject initializationData) {
        var completionRegistrationOptions = new JsonObject();
        completionRegistrationOptions.addProperty("resolveProvider", false);
        initializationData.add("completionProvider", completionRegistrationOptions);
    }

    @Override
    public Optional<Optional<CompletionList>> handle(TextDocumentPositionParams params, List<String> fileContent) {
        // TODO Auto-generated method stub
        return Optional.empty();
    }
    
}
