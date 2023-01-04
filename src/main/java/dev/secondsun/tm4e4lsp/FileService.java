package dev.secondsun.tm4e4lsp;

import java.io.IOException;
import java.net.URI;
import java.util.List;

@FunctionalInterface
public interface FileService {
	List<String> readLines(URI fileUri, URI workSpaceUri ) throws IOException;

}
