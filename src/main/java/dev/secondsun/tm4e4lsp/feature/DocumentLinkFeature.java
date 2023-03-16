package dev.secondsun.tm4e4lsp.feature;

import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.google.gson.JsonObject;

import dev.secondsun.lsp.DocumentLink;
import dev.secondsun.lsp.DocumentLinkParams;
import dev.secondsun.lsp.Position;
import dev.secondsun.lsp.Range;
import dev.secondsun.tm4e.core.grammar.IGrammar;
import dev.secondsun.tm4e4lsp.util.FileService;
import dev.secondsun.tm4e4lsp.util.Util;

public class DocumentLinkFeature implements Feature<DocumentLinkParams, List<DocumentLink>> {
    private static final Logger LOG = Logger.getLogger(DocumentLinkFeature.class.getName());

    private IGrammar grammar;
    private final  FileService fs;
    public DocumentLinkFeature(IGrammar grammar, FileService fileService) {
        this.grammar = grammar;
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
    public Optional<List<DocumentLink>> handle(DocumentLinkParams params, List<String>  fileContent) {
        
        List<DocumentLink> links = new ArrayList<>();
        URI currentDir = getCurrentDirectory(params.textDocument.uri);
        IntStream.range(0, fileContent.size()).forEach(idx -> {

            var line = fileContent.get(idx);
            if (Util.isIncludeDirective(line)) {
                try {
                    var fileName = line.split(";")[0].split("\"")[1];
                    LOG.info(fileName);
                    var files = fs.find(URI.create(fileName), currentDir);
                    LOG.info(files.stream().map(Object::toString).collect(Collectors.joining("\n\t")));
                    for (URI file : files) {

                        var link = new DocumentLink();
                        link.target = file.toString();
                        link.range = new Range(new Position(idx, line.indexOf(fileName)),
                                              new Position(idx,  line.indexOf(fileName)+fileName.length()));
                        links.add(link);
                        LOG.info(link.target);
                    }
                    
                    
                } catch (ArrayIndexOutOfBoundsException ignore) {}
            }
        });

        return Optional.of(links);
    }
    private URI getCurrentDirectory(URI uri) {
        
        
        if (uri.isAbsolute()) {
            var file = new File(uri);
            if (!file.isDirectory()) {
                return file.getParentFile().getAbsoluteFile().toURI();
            } else {
                return file.getAbsoluteFile().toURI();
            }
        }  else {
            var path = uri.getPath();
            if (path.indexOf("/") == -1  || path.endsWith("/")) {
                return uri;
            } else {
                return URI.create(path.substring(0, path.lastIndexOf("/")) + "/");
            }

        }

    }

}
