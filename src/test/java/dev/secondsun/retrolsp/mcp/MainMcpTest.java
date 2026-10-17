package dev.secondsun.retrolsp.mcp;

import static org.junit.jupiter.api.Assertions.*;

import dev.secondsun.retrolsp.Main;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

public class MainMcpTest {

  @Test
  public void testMainWithoutLogFlagDoesNotConfigureLogging() throws Exception {
    RetroLspMcpServer.resetLoggingForTesting();
    InputStream originalIn = System.in;
    try {
      System.setIn(new ByteArrayInputStream(new byte[0]));

      AtomicBoolean started = new AtomicBoolean(false);
      Thread thread =
          new Thread(
              () -> {
                started.set(true);
                Main.main("--mcp", "--workspace", ".");
              });
      thread.start();
      thread.interrupt();
      thread.join(2000);
      assertTrue(started.get());
      assertNull(RetroLspMcpServer.getLogFilePath());
    } finally {
      System.setIn(originalIn);
    }
  }

  @Test
  public void testMainWithLogFlagConfiguresLogging() throws Exception {
    RetroLspMcpServer.resetLoggingForTesting();
    InputStream originalIn = System.in;
    try {
      System.setIn(new ByteArrayInputStream(new byte[0]));

      AtomicBoolean started = new AtomicBoolean(false);
      Thread thread =
          new Thread(
              () -> {
                started.set(true);
                Main.main("--mcp", "--workspace", ".", "--log");
              });
      thread.start();
      thread.interrupt();
      thread.join(2000);
      assertTrue(started.get());
      assertNotNull(RetroLspMcpServer.getLogFilePath());
      assertTrue(java.nio.file.Files.exists(RetroLspMcpServer.getLogFilePath()));
    } finally {
      System.setIn(originalIn);
      RetroLspMcpServer.resetLoggingForTesting();
    }
  }

  @Test
  public void testConfigureLoggingFormattingAndLevels(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) throws Exception {
    RetroLspMcpServer.resetLoggingForTesting();
    java.time.LocalDateTime timestamp = java.time.LocalDateTime.of(2026, 10, 16, 14, 25, 30);
    java.nio.file.Path logFile = RetroLspMcpServer.configureLogging(tempDir, timestamp);

    try {
      assertNotNull(logFile);
      assertTrue(java.nio.file.Files.exists(logFile));
      assertEquals("retro-lsp-msp.2026-10-16.142530.log", logFile.getFileName().toString());

      try (var stream = java.nio.file.Files.list(tempDir)) {
        java.util.List<java.nio.file.Path> logFiles =
            stream.filter(p -> p.getFileName().toString().endsWith(".log")).toList();
        assertEquals(1, logFiles.size(), "Only one log file should be created per launch");
        assertEquals(logFile, logFiles.get(0));
      }

      java.util.logging.Logger testLogger = java.util.logging.Logger.getLogger("test.retrolsp.mcp");
      testLogger.fine("Debug fine message should be filtered");
      testLogger.info("Informational message logged");
      testLogger.warning("Warning message logged");

      String content = java.nio.file.Files.readString(logFile);
      assertTrue(content.contains("INFO: Informational message logged"));
      assertTrue(content.contains("WARNING: Warning message logged"));
      assertFalse(content.contains("Debug fine message should be filtered"));
    } finally {
      RetroLspMcpServer.resetLoggingForTesting();
    }
  }
}
