package dev.secondsun.retrolsp.feature;

import dev.secondsun.lsp.CompletionList;
import dev.secondsun.lsp.TextDocumentPositionParams;
import dev.secondsun.retro.util.vo.TokenizedFile;

/** Feature interface for autocompletion providers in retro-lsp. */
public interface CompletionFeature extends Feature<TextDocumentPositionParams, CompletionList> {

  /**
   * Determines whether this completion feature can handle the request at the given position.
   *
   * @param params text document position parameters from the language client
   * @param fileContent the tokenized content of the file
   * @return {@code true} if this completion feature can provide completions, {@code false}
   *     otherwise
   */
  boolean canComplete(TextDocumentPositionParams params, TokenizedFile fileContent);
}
