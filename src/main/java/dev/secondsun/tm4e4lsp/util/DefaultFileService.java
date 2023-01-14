package dev.secondsun.tm4e4lsp.util;

import java.io.FileInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

import com.oracle.truffle.api.dsl.TypeSystem;

import dev.secondsun.tm4e4lsp.FileService;

public class DefaultFileService implements FileService{

    private Set<URI> repositories = new HashSet<>();
    private static final Logger LOG = Logger.getAnonymousLogger();
    @Override
    public FileService addRepository(URI repoURI) {
        if (Paths.get(repoURI).toFile().exists()) {
            repositories.add(repoURI);
        } else {
            throw new RuntimeException(repoURI + " does not exist.");
        }
        return this;
    }


    @Override
    public List<String> readLines(URI fileUri, URI workSpaceUri) throws IOException {
        return Util.readLines(new FileInputStream(Paths.get(fileUri).toFile()));
    }


    @Override
    public List<URI> find(URI file) {
        List<URI> list = new ArrayList<>();
        LOG.info(file.toString());
        repositories.forEach(repo->{
            var pathForRepo = Paths.get(repo).resolve(file.toString()).toFile();
            LOG.info(pathForRepo.getAbsolutePath());
            if (pathForRepo.exists()) {
                list.add(pathForRepo.toURI());
            }
        });
        return list;
    }

    
    
}
