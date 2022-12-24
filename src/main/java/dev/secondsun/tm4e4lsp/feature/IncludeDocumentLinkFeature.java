package dev.secondsun.tm4e4lsp.feature;

import java.util.List;
import java.util.Optional;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import dev.secondsun.lsp.TextDocumentPositionParams;

public class IncludeDocumentLinkFeature implements Feature {

    @Override
    public void initialize(JsonObject initializationData) {
        var documentLinkOptions = new JsonObject();
        documentLinkOptions.addProperty("resolveProvider", false);
        initializationData.add("documentLinkProvider", documentLinkOptions);
        this.workspaceRoot = workspaceRoot;
        
    }


    @Override
    public Optional executeFeature(TextDocumentPositionParams params, List fileContent) {
        // TODO Auto-generated method stub
        return null;
    }
    
}
