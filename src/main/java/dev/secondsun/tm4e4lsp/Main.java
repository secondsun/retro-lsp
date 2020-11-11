package dev.secondsun.tm4e4lsp;


import java.io.FileInputStream;
import java.nio.file.Paths;
import java.util.logging.Logger;



import dev.secondsun.lsp.LSP;
import dev.secondsun.tm4e4lsp.util.Util;

public class Main {

    private static final Logger LOG = Logger.getLogger(Main.class.getName());

    public static void main(String... args) {
        LOG.info("Starting");
        
        FileService fileService = (uri, workspaceRoot)->{
            return Util.readLines(new FileInputStream(Paths.get(uri).toFile()));
        };
      LSP.connect((langClient) -> new CC65LanguageServer(fileService, langClient), System.in, System.out);
  }
}
