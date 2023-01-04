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

public class DocumentLinkFeature implements Feature<DocumentLinkParams, List<DocumentLink>> {
    private static final Logger LOG = Logger.getLogger(DocumentLinkFeature.class.getName());

    private IGrammar grammar;

    public DocumentLinkFeature(IGrammar grammar) {
        this.grammar = grammar;
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
        var parentDir = new File(params.textDocument.uri).getParent();
        IntStream.range(0, fileContent.size()).forEach(idx -> {

            var line = fileContent.get(idx);
            if (line.toUpperCase().startsWith(".INCLUDE")) {
                try {
                    var fileName = line.split(";")[0].split("\"")[1];
                    var link = new DocumentLink();
                    
                    link.target = new File(parentDir, fileName).toURI().toString();
                    link.range = new Range(new Position(idx, line.indexOf(fileName)),
                                          new Position(idx,  line.indexOf(fileName)+fileName.length()));
                    links.add(link);
                    LOG.info(link.target);
                } catch (ArrayIndexOutOfBoundsException ignore) {}
            }
        });

        return Optional.of(links);
    }

}
