package dev.secondsun.retrolsp;

import dev.secondsun.lsp.LSP;
import java.util.logging.Logger;

/** Entry point for the retro-lsp Language Server process. */
public class Main {

  private static final Logger LOG = Logger.getLogger(Main.class.getName());

  /** Private constructor to prevent instantiation of this entry point class. */
  private Main() {}

  /**
   * Main entry point to start the LSP server communicating over standard input and output.
   *
   * @param args command line arguments
   */
  public static void main(String... args) {
    LOG.info("Starting");

    LSP.connect((langClient) -> new CA65LanguageServer(), System.in, System.out);
  }
}
