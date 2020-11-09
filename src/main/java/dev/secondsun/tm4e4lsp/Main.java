package dev.secondsun.tm4e4lsp;


import java.io.FileInputStream;
import java.nio.file.Paths;
import java.util.logging.Logger;

import org.apache.commons.io.IOUtils;

import dev.secondsun.lsp.LSP;

public class Main {

    private static final Logger LOG = Logger.getLogger(Main.class.getName());

    public static void main(String... args) {
        LOG.info("Starting");
        @SuppressWarnings("unchecked")
        FileService fileService = (uri, workspaceRoot)->{
            LOG.info(String.format("return IOUtils.readLines(new FileInputStream(Paths.get(%s + %s).toFile()));",workspaceRoot.toString(), uri.toString()));
            return IOUtils.readLines(new FileInputStream(Paths.get(workspaceRoot.toString()+ uri.toString()).toFile()));
        };
      LSP.connect((langClient) -> new CC65LanguageServer(fileService, langClient), System.in, System.out);
  }
}
