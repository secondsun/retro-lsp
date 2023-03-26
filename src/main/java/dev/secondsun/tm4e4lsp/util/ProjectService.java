package dev.secondsun.tm4e4lsp.util;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * This service is used for initializing the root directory.
 * 
 * Initialization means loading the directory, importing all files into the fileservice and
 * symbols into the symbol service. It works on the project level
 * 
 */
public class ProjectService {

    private FileService fileService;
    private SymbolService symbolService;
    Map<URI, List<String>> files = new HashMap<>();


    public ProjectService(FileService fileService, SymbolService symbolService) {
        this.fileService = fileService;
        this.symbolService = symbolService;
    }

    

    /**
     * Loads and begins watching all source (*.s, *.i, and *.sgs) files in dir.
     * 
     * @param directory URI of path to directory
     */
    public void includeDir(URI directory) {
        directory = normalize(directory);
        this.fileService.addSearchPath(directory);
        _includeDir(directory);
    }
    private void _includeDir(URI directory) {
        //Include everything in workspace root
        var workspacePath  = Path.of(directory);
        Arrays.stream(workspacePath.toFile().listFiles(new FileFilter() {

            @Override
            public boolean accept(File pathname) {
                return pathname.getName().endsWith(".s") ||
                pathname.getName().endsWith(".sgs") ||
                pathname.getName().endsWith(".i") ||
                pathname.getName().endsWith(".inc");
            }
            
        })).forEach((file)-> {
            var fileUri = normalize(file.toURI());
            files.computeIfAbsent(fileUri, (key) -> {
                try {
                    var lines = fileService.readLines(fileUri);
                    return lines;
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            symbolService.extractDefinitions(fileUri, files.get(fileUri));
        });

        Arrays.stream(workspacePath.toFile().listFiles(file->file.isDirectory())).map(File::toURI).forEach(uri -> this._includeDir(uri));

        
    }



    public void refreshFileContents(URI uri) {
        var fileUri = normalize(uri);
        Logger.getAnonymousLogger().info("refreshFileContents");
        Logger.getAnonymousLogger().info(fileUri.toString());
        Logger.getAnonymousLogger().info(files.keySet().stream().map(URI::toString).collect(Collectors.joining("\n\t")));
        
        try {
            files.put(fileUri, fileService.readLines(fileUri));
            symbolService.extractDefinitions(fileUri, files.get(fileUri));
        } catch (IOException e) {
            Logger.getAnonymousLogger().severe(e.getMessage());
        }
        


        
    }



    public List<String> getFileContents(URI uri) {
        uri = normalize(uri);
        var lines = files.get(uri);
        if (isNullOrEmpty(lines)) {
            Logger.getAnonymousLogger().warning(uri.getRawSchemeSpecificPart() + " could not be found in read files.");
            Logger.getAnonymousLogger().warning(files.keySet().stream().map(URI::getRawSchemeSpecificPart).collect(Collectors.joining("\t\n")));
            return List.of();
        }
        return lines;
    }



    /**
     * On windows unescape the uri we receive from the language client
     * 
     * @param uri
     * @return
     */
    private URI normalize(URI uri) {
      try {
        return  new File(uri.getRawSchemeSpecificPart().replace("%3A", ":")).getCanonicalFile().toURI();
    } catch (IOException e) {
        throw new RuntimeException(e);
    }
    }



    private boolean isNullOrEmpty(List<String> lines) {
        return lines == null || lines.isEmpty();
    }
    
    }
