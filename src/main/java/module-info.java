/**
 * retro-lsp provides a Language Server Protocol (LSP) implementation for retro game development
 * using CA65 assembly, libSFX, and the SuperFX co-processor.
 */
open module dev.secondsun.retrolsp {
  requires java.logging;
  requires java.xml;
  requires com.google.gson;
  requires dev.secondsun.lsp;
  requires dev.secondsun.retro.util;
  requires dev.secondsun.sfxoptimizer;
}
