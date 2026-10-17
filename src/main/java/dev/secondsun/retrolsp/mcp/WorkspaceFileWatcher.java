package dev.secondsun.retrolsp.mcp;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;
import static java.nio.file.StandardWatchEventKinds.OVERFLOW;

import dev.secondsun.retro.util.ProjectService;
import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Background filesystem watcher that monitors workspace directories recursively for SuperFX and
 * CA65 source file changes, synchronizing updates and deletions into the shared {@link
 * ProjectService}.
 */
public class WorkspaceFileWatcher implements AutoCloseable {

  private static final Logger LOG = Logger.getLogger(WorkspaceFileWatcher.class.getName());

  private final WatchService watchService;
  private final Map<WatchKey, Path> watchKeys = new ConcurrentHashMap<>();
  private final ProjectService projectService;
  private final Thread watcherThread;
  private volatile boolean running = true;

  public WorkspaceFileWatcher(ProjectService projectService, Path rootPath) throws IOException {
    this.projectService = Objects.requireNonNull(projectService);
    this.watchService = FileSystems.getDefault().newWatchService();

    if (rootPath != null && Files.exists(rootPath)) {
      registerDirectory(rootPath);
    }

    this.watcherThread = Thread.ofVirtual().name("mcp-file-watcher").start(this::runLoop);
  }

  /**
   * Recursively walks and registers the directory hierarchy with the watch service, excluding
   * hidden, build, and dependency directories.
   *
   * @param startPath the directory to start walking
   */
  public void registerDirectory(Path startPath) {
    if (startPath == null || !Files.isDirectory(startPath)) {
      return;
    }
    try {
      Files.walkFileTree(
          startPath,
          new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                throws IOException {
              String name = dir.getFileName() != null ? dir.getFileName().toString() : "";
              if (name.startsWith(".")
                  || "node_modules".equals(name)
                  || "target".equals(name)
                  || "build".equals(name)
                  || "dist".equals(name)
                  || "out".equals(name)) {
                return FileVisitResult.SKIP_SUBTREE;
              }
              registerSingle(dir);
              return FileVisitResult.CONTINUE;
            }
          });
    } catch (IOException e) {
      LOG.log(Level.WARNING, "Failed to register directory tree: " + startPath, e);
    }
  }

  private void registerSingle(Path dir) throws IOException {
    WatchKey key = dir.register(watchService, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE);
    watchKeys.put(key, dir);
  }

  private void runLoop() {
    while (running) {
      WatchKey key;
      try {
        key = watchService.take();
      } catch (InterruptedException | ClosedWatchServiceException e) {
        break;
      }

      Path dir = watchKeys.get(key);
      if (dir == null) {
        key.reset();
        continue;
      }

      for (WatchEvent<?> event : key.pollEvents()) {
        WatchEvent.Kind<?> kind = event.kind();
        if (kind == OVERFLOW) {
          continue;
        }

        @SuppressWarnings("unchecked")
        WatchEvent<Path> pathEvent = (WatchEvent<Path>) event;
        Path child = dir.resolve(pathEvent.context());

        if (kind == ENTRY_CREATE) {
          if (Files.isDirectory(child)) {
            registerDirectory(child);
          } else if (isSourceFile(child)) {
            projectService.refreshFileContents(child.toUri());
          }
        } else if (kind == ENTRY_MODIFY) {
          if (isSourceFile(child)) {
            projectService.refreshFileContents(child.toUri());
          }
        } else if (kind == ENTRY_DELETE) {
          if (isSourceFile(child)) {
            projectService.removeFile(child.toUri());
          }
        }
      }

      boolean valid = key.reset();
      if (!valid) {
        watchKeys.remove(key);
      }
    }
  }

  private static boolean isSourceFile(Path path) {
    if (path == null || path.getFileName() == null) return false;
    String filename = path.getFileName().toString().toLowerCase(Locale.ROOT);
    return filename.endsWith(".s")
        || filename.endsWith(".sgs")
        || filename.endsWith(".i")
        || filename.endsWith(".inc");
  }

  @Override
  public void close() {
    running = false;
    try {
      watchService.close();
    } catch (IOException e) {
      LOG.log(Level.FINE, "Error closing WatchService", e);
    }
    if (watcherThread != null) {
      watcherThread.interrupt();
    }
  }
}
