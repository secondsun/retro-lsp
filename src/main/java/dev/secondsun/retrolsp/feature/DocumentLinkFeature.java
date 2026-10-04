package dev.secondsun.retrolsp.feature;

import com.google.gson.JsonObject;
import dev.secondsun.lsp.DocumentLink;
import dev.secondsun.lsp.DocumentLinkParams;
import dev.secondsun.lsp.Position;
import dev.secondsun.lsp.Range;
import dev.secondsun.retro.util.FileService;
import dev.secondsun.retro.util.Util;
import dev.secondsun.retro.util.vo.TokenizedFile;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;
import java.util.stream.IntStream;

/** Feature providing document links for file references, such as {@code .include} directives. */
public class DocumentLinkFeature implements Feature<DocumentLinkParams, List<DocumentLink>> {
  private static final Logger LOG = Logger.getLogger(DocumentLinkFeature.class.getName());

  private final FileService fs;

  /**
   * Constructs a new {@code DocumentLinkFeature} with the specified file service.
   *
   * @param fileService the file service used to resolve relative file references
   */
  public DocumentLinkFeature(FileService fileService) {
    this.fs = fileService;
  }

  @Override
  public void initialize(JsonObject initializationData) {
    var documentLinkOptions = new JsonObject();
    documentLinkOptions.addProperty("resolveProvider", false);
    initializationData.add("documentLinkProvider", documentLinkOptions);
    // this.workspaceRoot = workspaceRoot;

  }

  @Override
  public Optional<List<DocumentLink>> handle(DocumentLinkParams params, TokenizedFile fileContent) {
    List<DocumentLink> links = new ArrayList<>();
    if (fileContent == null) {
      return Optional.of(links);
    }
    URI currentDir;
    try {
      currentDir = getCurrentDirectory(params.textDocument.uri);
    } catch (Exception e) {
      return Optional.of(links);
    }
    IntStream.range(0, fileContent.textLines())
        .forEach(
            idx -> {
              var line = fileContent.getLineText(idx);
              if (Util.isIncludeDirective(line)) {
                try {
                  var lineTokens = fileContent.getLineTokens(idx);
                  if (lineTokens != null && lineTokens.size() > 1) {
                    var fileName = lineTokens.get(1).text().replace("\"", "");
                    URI fileUri;
                    try {
                      fileUri = URI.create(fileName);
                    } catch (Exception e) {
                      fileUri = new File(fileName).toURI();
                    }
                    var files = fs.find(fileUri, currentDir);
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
                } catch (Exception ignore) {
                }
              }
            });

    return Optional.of(links);
  }

  private URI getCurrentDirectory(URI uri) throws IOException {

    // Logger.getAnonymousLogger().info("getCurrentDirectory:" + uri.toString());
    // Logger.getAnonymousLogger().info("getCurrentDirectory.relativize:" +
    // uri.relativize(URI.create("../")).toString());
    // Logger.getAnonymousLogger().info("getCurrentDirectory.resolve:" +
    // uri.resolve(URI.create("../")).toString());
    try {
      return new File(uri).getParentFile().toURI();
    } catch (Exception ignore) {
    }
    if (uri.isAbsolute()) {
      var file = new File(uri.getRawSchemeSpecificPart());
      if (!file.isDirectory()) {
        return file.getParentFile().getCanonicalFile().toURI();
      } else {
        return file.getCanonicalFile().toURI();
      }
    } else {
      var path = uri.getRawSchemeSpecificPart();
      if (path.indexOf("/") == -1 || path.endsWith("/")) {
        return uri;
      } else {
        return URI.create(path.substring(0, path.lastIndexOf("/")) + "/");
      }
    }
  }
}
