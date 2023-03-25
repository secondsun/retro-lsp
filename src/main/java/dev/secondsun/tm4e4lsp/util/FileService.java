package dev.secondsun.tm4e4lsp.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;

public class FileService {

    private Set<URI> repositories = new HashSet<>();
    private static final Logger LOG = Logger.getAnonymousLogger();
    
    
    public FileService addSearchPath(URI repoURI) {
        if (Path.of(repoURI).toFile().exists()) {
            repositories.add(repoURI);
        } else {
            System.out.println(repoURI + " does not exist.");
        }
        return this;
    }


    public List<String> readLines(final URI fileUri) throws IOException {

        var possibleFile = new File(fileUri.getRawSchemeSpecificPart());
        if (possibleFile.exists()) {
            try (var stream = new FileInputStream(possibleFile)) {
                return Util.readLines(stream);
            }
        }

        final var fileToRead = repositories.stream()
        .map( it -> Path.of(it).resolve(Path.of(fileUri.getRawSchemeSpecificPart())).toFile())
        .filter(File::exists)
        .findFirst();

        if (fileToRead.isPresent()) {
            var file  = fileToRead.get();
            try (var stream = new FileInputStream(file)) {
                return Util.readLines(stream);
            }
        }
        
        return new ArrayList<>();
        
    }

    public List<URI> find(URI file, URI... optionalSearchPaths) {
        
        //We're allocating a copy of the localRepos and adding optionalSearchPaths
        var localRepos = new ArrayList<>(this.repositories);
        if (optionalSearchPaths != null && optionalSearchPaths.length >0) {
            localRepos.addAll(List.of(optionalSearchPaths));
        }

        List<URI> list = new ArrayList<>();

        localRepos.forEach(repo->{
            var pathForRepo = Path.of(repo).resolve(file.toString()).toFile();
            LOG.info(pathForRepo.getAbsolutePath());
            if (pathForRepo.exists()) {
                list.add(pathForRepo.toURI());
            }
        });
        return list;
    }

    
    
}
