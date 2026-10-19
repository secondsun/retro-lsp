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
  requires mcp;
  requires com.fasterxml.jackson.databind;
  requires com.fasterxml.jackson.core;
  requires org.slf4j;

  exports dev.secondsun.retrolsp;
  exports dev.secondsun.retrolsp.feature;
  exports dev.secondsun.retrolsp.mcp;
  exports dev.secondsun.retrolsp.mcp.tools;
  exports dev.secondsun.retrolsp.diagnostic;
}
