package dev.secondsun.retrolsp.feature;

import java.util.List;

import dev.secondsun.lsp.CompletionList;
import dev.secondsun.lsp.TextDocumentPositionParams;

public interface CompletionFeature extends Feature<TextDocumentPositionParams, CompletionList> {

    boolean canComplete(TextDocumentPositionParams params, List<String> fileContent);

}
