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
import java.util.Set;
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
  private final Set<Path> registeredDirs = ConcurrentHashMap.newKeySet();
  private final ProjectService projectService;
  private final Thread watcherThread;
  private volatile boolean running = true;

  /**
   * Constructs and starts a virtual-thread filesystem watcher over the workspace root.
   *
   * @param projectService project service to notify when files change
   * @param rootPath canonical root directory to watch recursively
   * @throws IOException if WatchService creation or directory registration fails
   */
  public WorkspaceFileWatcher(ProjectService projectService, Path rootPath) throws IOException {
    this.projectService = Objects.requireNonNull(projectService);
    this.watchService = FileSystems.getDefault().newWatchService();

    this.watcherThread =
        Thread.ofVirtual()
            .name("mcp-file-watcher")
            .start(
                () -> {
                  if (rootPath != null && Files.exists(rootPath)) {
                    registerDirectory(rootPath);
                  }
                  runLoop();
                });
  }

  /**
   * Recursively walks and registers the directory hierarchy with the watch service, excluding
   * hidden, build, and dependency directories.
   *
   * @param startPath the directory to start walking
   */
  public void registerDirectory(Path startPath) {
    if (!running || startPath == null || !Files.isDirectory(startPath)) {
      return;
    }
    Path rootNormalized = startPath.toAbsolutePath().normalize();
    if (registeredDirs.contains(rootNormalized)) {
      return;
    }
    try {
      Files.walkFileTree(
          rootNormalized,
          new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                throws IOException {
              if (!running) {
                return FileVisitResult.TERMINATE;
              }
              Path normalized = dir.toAbsolutePath().normalize();
              String name = dir.getFileName() != null ? dir.getFileName().toString() : "";
              if (!normalized.equals(rootNormalized)
                  && (name.startsWith(".")
                      || "node_modules".equals(name)
                      || "target".equals(name)
                      || "build".equals(name)
                      || "dist".equals(name)
                      || "out".equals(name)
                      || "tools".equals(name)
                      || "build_tools".equals(name))) {
                return FileVisitResult.SKIP_SUBTREE;
              }
              if (registeredDirs.contains(normalized)) {
                return FileVisitResult.SKIP_SUBTREE;
              }
              registerSingle(normalized);
              return FileVisitResult.CONTINUE;
            }
          });
    } catch (ClosedWatchServiceException e) {
      // Watcher was closed while scanning; exit quietly.
    } catch (IOException e) {
      LOG.log(Level.WARNING, "Failed to register directory tree: " + startPath, e);
    }
  }

  private void registerSingle(Path dir) throws IOException {
    Path normalized = dir.toAbsolutePath().normalize();
    if (!registeredDirs.add(normalized)) {
      return;
    }
    try {
      WatchKey key = normalized.register(watchService, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE);
      watchKeys.put(key, normalized);
    } catch (IOException | RuntimeException e) {
      registeredDirs.remove(normalized);
      throw e;
    }
  }

  Set<Path> getRegisteredDirectories() {
    return Set.copyOf(registeredDirs);
  }

  int getRegisteredDirectoryCount() {
    return registeredDirs.size();
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
        Path removed = watchKeys.remove(key);
        if (removed != null) {
          registeredDirs.remove(removed.toAbsolutePath().normalize());
        }
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
    watchKeys.clear();
    registeredDirs.clear();
  }
}
