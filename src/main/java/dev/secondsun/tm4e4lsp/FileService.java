package dev.secondsun.tm4e4lsp;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

@FunctionalInterface
public interface FileService {
	List<String> readLines(URI fileUri, URI workSpaceUri ) throws IOException;
	default FileService addRepository(URI repoURI) {return this;};
	default List<URI> find(URI file) {return new ArrayList<>();};

}
