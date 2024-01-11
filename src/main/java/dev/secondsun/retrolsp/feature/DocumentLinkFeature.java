package dev.secondsun.retrolsp.feature;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.google.gson.JsonObject;

import dev.secondsun.lsp.DocumentLink;
import dev.secondsun.lsp.DocumentLinkParams;
import dev.secondsun.lsp.Position;
import dev.secondsun.lsp.Range;
import dev.secondsun.retro.util.FileService;
import dev.secondsun.retro.util.Util;
import dev.secondsun.retro.util.vo.TokenizedFile;


public class DocumentLinkFeature implements Feature<DocumentLinkParams, List<DocumentLink>> {
    private static final Logger LOG = Logger.getLogger(DocumentLinkFeature.class.getName());

    private final FileService fs;
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
        Logger.getAnonymousLogger().info("DocumentLinkFeature.handle:" + fileContent.uri);
        Logger.getAnonymousLogger().info("DocumentLinkFeature.handle:" + fileContent.textLines());
        List<DocumentLink> links = new ArrayList<>();
        URI currentDir;
        try {
            currentDir = getCurrentDirectory(params.textDocument.uri);
            Logger.getAnonymousLogger().info("Getting file contents for " + params.textDocument.uri.toString() + " in dir" + currentDir.toString());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        IntStream.range(0, fileContent.textLines()).forEach(idx -> {

            var line = fileContent.getLineText(idx);
            Logger.getAnonymousLogger().info("line " + idx + ":" + line);
            if (Util.isIncludeDirective(line)) {
                try {
                    //Ok we're parsing out the filename from the line.
                    //the format is .include "fileName" ; comment
                    // this is pretty hardcoded in ca65 so I'm not feeling 
                    // bad about being messy here though using the grammar would be smarter

                    //We're splitting comments, then splitting the string
                    var fileName = fileContent.getLineTokens(idx).get(1).text().replace("\"","");
                    Logger.getAnonymousLogger().info(".include filename " + fileName);
                    Logger.getAnonymousLogger().info("currentDir " + currentDir);
                    //Find knows about relative files and resolves to files on the hard disk.
                    var files = fs.find(URI.create(fileName), currentDir);
                    Logger.getAnonymousLogger().info("files " + files.stream().map(Object::toString).collect(Collectors.joining(",")));
                    for (URI file : files) {

                        var link = new DocumentLink();
                        link.target = file.toString();
                        link.range = new Range(new Position(idx, line.indexOf(fileName)),
                                              new Position(idx,  line.indexOf(fileName)+fileName.length()));
                        links.add(link);
                        
                    }
                    
                    
                } catch (ArrayIndexOutOfBoundsException ignore) {}
            }
        });

        return Optional.of(links);
    }
    private URI getCurrentDirectory(URI uri) throws IOException {


        // Logger.getAnonymousLogger().info("getCurrentDirectory:" + uri.toString());
        // Logger.getAnonymousLogger().info("getCurrentDirectory.relativize:" + uri.relativize(URI.create("../")).toString());
        // Logger.getAnonymousLogger().info("getCurrentDirectory.resolve:" + uri.resolve(URI.create("../")).toString());
        try {
        Logger.getAnonymousLogger().info("getCurrentDirectory.file:" +  new File(uri).getParentFile().toURI());
        return new File(uri).getParentFile().toURI();
        } catch (Exception ignore) {}
        if (uri.isAbsolute()) {
            var file = new File(uri.getRawSchemeSpecificPart());
            if (!file.isDirectory()) {
                return file.getParentFile().getCanonicalFile().toURI();
            } else {
                return file.getCanonicalFile().toURI();
            }
        }  else {
            var path = uri.getRawSchemeSpecificPart();
            if (path.indexOf("/") == -1  || path.endsWith("/")) {
                return uri;
            } else {
                return URI.create(path.substring(0, path.lastIndexOf("/")) + "/");
            }

        }

    }

}
