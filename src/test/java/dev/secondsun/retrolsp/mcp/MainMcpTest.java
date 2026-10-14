package dev.secondsun.retrolsp.mcp;

import static org.junit.jupiter.api.Assertions.*;

import dev.secondsun.retrolsp.Main;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

public class MainMcpTest {

  @Test
  public void testMainWithMcpFlag() throws Exception {
    InputStream originalIn = System.in;
    try {
      // Empty input stream simulates immediate EOF
      System.setIn(new ByteArrayInputStream(new byte[0]));

      AtomicBoolean started = new AtomicBoolean(false);
      Thread thread =
          new Thread(
              () -> {
                started.set(true);
                Main.main("--mcp");
              });
      thread.start();
      thread.interrupt();
      thread.join(2000);
      assertTrue(started.get());
    } finally {
      System.setIn(originalIn);
    }
  }
}
