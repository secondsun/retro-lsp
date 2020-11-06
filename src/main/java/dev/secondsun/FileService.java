package dev.secondsun;

import java.io.IOException;
import java.net.URI;
import java.util.List;

public interface FileService {

	List<String> readLines(URI uri) throws IOException;

}
