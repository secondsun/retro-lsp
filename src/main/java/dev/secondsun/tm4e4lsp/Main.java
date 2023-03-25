package dev.secondsun.tm4e4lsp;
import java.util.logging.Logger;
import dev.secondsun.lsp.LSP;


public class Main {

    private static final Logger LOG = Logger.getLogger(Main.class.getName());

    public static void main(String... args) {
        LOG.info("Starting");
        
      LSP.connect((langClient) -> new CC65LanguageServer(), System.in, System.out);
  }
}
