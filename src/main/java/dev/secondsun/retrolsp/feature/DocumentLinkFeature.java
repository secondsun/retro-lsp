package dev.secondsun.retrolsp.feature;

import com.google.gson.JsonObject;
import dev.secondsun.lsp.DocumentLink;
import dev.secondsun.lsp.DocumentLinkParams;
import dev.secondsun.lsp.Position;
import dev.secondsun.lsp.Range;
import dev.secondsun.retro.util.DefaultWorkspaceIndex;
import dev.secondsun.retro.util.Util;
import dev.secondsun.retro.util.WorkspaceIndex;
import dev.secondsun.retro.util.WorkspaceSnapshot;
import dev.secondsun.retro.util.vo.TokenizedFile;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.IntStream;

/** Feature providing document links for file references, such as {@code .include} directives. */
public class DocumentLinkFeature implements Feature<DocumentLinkParams, List<DocumentLink>> {

  private final WorkspaceIndex workspaceIndex;

  /** Constructs a standalone {@code DocumentLinkFeature} with a default workspace index. */
  public DocumentLinkFeature() {
    this(new DefaultWorkspaceIndex());
  }

  /**
   * Constructs a new {@code DocumentLinkFeature} backed by the specified {@link WorkspaceIndex}.
   *
   * @param workspaceIndex workspace index used to resolve include targets
   */
  public DocumentLinkFeature(WorkspaceIndex workspaceIndex) {
    this.workspaceIndex = Objects.requireNonNull(workspaceIndex, "workspaceIndex must not be null");
  }

  @Override
  public void initialize(JsonObject initializationData) {
    var documentLinkOptions = new JsonObject();
    documentLinkOptions.addProperty("resolveProvider", false);
    initializationData.add("documentLinkProvider", documentLinkOptions);
  }

  @Override
  public Optional<List<DocumentLink>> handle(DocumentLinkParams params, TokenizedFile fileContent) {
    List<DocumentLink> links = new ArrayList<>();
    if (fileContent == null || params == null) {
      return Optional.of(links);
    }
    URI docUri = params.textDocument != null ? params.textDocument.uri : null;
    WorkspaceSnapshot snapshot = workspaceIndex.snapshot();

    IntStream.range(0, fileContent.textLines())
        .forEach(
            idx -> {
              var line = fileContent.getLineText(idx);
              if (Util.isIncludeDirective(line)) {
                try {
                  var lineTokens = fileContent.getLineTokens(idx);
                  if (lineTokens != null && lineTokens.size() > 1) {
                    var fileName = lineTokens.get(1).text().replace("\"", "");
                    if (!fileName.isBlank()) {
                      var files = snapshot.findIncludeUris(docUri, fileName);
                      int startIdx = line.indexOf(fileName);
                      if (startIdx >= 0) {
                        for (URI file : files) {
                          var link = new DocumentLink();
                          link.target = file.toString();
                          link.range =
                              new Range(
                                  new Position(idx, startIdx),
                                  new Position(idx, startIdx + fileName.length()));
                          links.add(link);
                        }
                      }
                    }
                  }
                } catch (Exception ignore) {
                }
              }
            });

    return Optional.of(links);
  }
}
