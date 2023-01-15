package dev.secondsun.tm4e4lsp;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

@FunctionalInterface
/**
 * This class is an evolving grab bag of file related features.
 * 
 * Ideally file search paths are managed by calls to addSearchPaths (for things like global includes),
 * and file specific search paths (like the current firctory of the open file) are passed with the "optionalSearchPaths" parameters.
 * 
 * There are some open design questions, specifically "readLines" requires an explicit search path. 
 * This is because it is supposed to load a specific file, but I would rather find a better way to 
 * manage searchPaths and remove the trailing searchPath and optional search path params.
 * 
 */
public interface FileService {
	
	
	default FileService addSearchPath(URI repoURI) {return this;};

	List<String> readLines(URI fileUri, URI searchPath ) throws IOException;
	default List<URI> find(URI file, URI... optionalSearchPaths) {return new ArrayList<>();};

}
