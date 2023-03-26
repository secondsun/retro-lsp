package dev.secondsun.tm4e4lsp.feature;

import java.io.File;
import java.io.IOException;
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
        URI currentDir;
        try {
            currentDir = getCurrentDirectory(params.textDocument.uri);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        IntStream.range(0, fileContent.size()).forEach(idx -> {

            var line = fileContent.get(idx);
            if (Util.isIncludeDirective(line)) {
                try {
                    //Ok we're parsing out the filename from the line.
                    //the format is .include "fileName" ; comment
                    // this is pretty hardcoded in ca65 so I'm not feeling 
                    // bad about being messy here though using the grammar would be smarter

                    //We're splitting comments, then splitting the string
                    var fileName = line.split(";")[0].split("\"")[1];
                    
                    //Find knows about relative files and resolves to files on the hard disk.
                    var files = fs.find(URI.create(fileName), currentDir);
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
