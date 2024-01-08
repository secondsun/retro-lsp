package dev.secondsun.retrolsp.feature;

import dev.secondsun.lsp.CompletionList;
import dev.secondsun.lsp.TextDocumentPositionParams;
import dev.secondsun.retro.util.vo.TokenizedFile;

public interface CompletionFeature extends Feature<TextDocumentPositionParams, CompletionList> {

    boolean canComplete(TextDocumentPositionParams params, TokenizedFile fileContent);

}
