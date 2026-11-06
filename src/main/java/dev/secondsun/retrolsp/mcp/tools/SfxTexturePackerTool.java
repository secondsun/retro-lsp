package dev.secondsun.retrolsp.mcp.tools;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * MCP Tool implementation for packing, importing, and validating SuperFX Texture Atlases using the
 * headless texture-packer utility.
 */
public class SfxTexturePackerTool {

  private final Path workspaceRoot;

  /**
   * Constructs tool with workspace root boundary.
   *
   * @param workspaceRoot canonical workspace root directory
   */
  public SfxTexturePackerTool(Path workspaceRoot) {
    this.workspaceRoot = Objects.requireNonNull(workspaceRoot);
  }

  /**
   * Result of texture-packer CLI invocation.
   *
   * @param status status string (SUCCESS or FAIL)
   * @param exitCode process exit code
   * @param stdout standard output from texture-packer
   * @param stderr standard error from texture-packer
   */
  public record TexturePackerResult(String status, int exitCode, String stdout, String stderr) {}

  /**
   * Executes texture-packer CLI command against an .sfxproj project.
   *
   * @param projectPathStr path to the .sfxproj project file
   * @param action packer action (validate, export, add-textures)
   * @param autoPack whether to automatically pack atlas during export
   * @param outputDirStr optional output directory for exported files
   * @param imagePaths list of image file paths for add-textures action
   * @param autoQuantize whether to quantize images using Median-Cut algorithm
   * @param snapPalette whether to snap colors to existing palette
   * @return texture-packer execution result
   */
  public TexturePackerResult run(
      String projectPathStr,
      String action,
      Boolean autoPack,
      String outputDirStr,
      List<String> imagePaths,
      Boolean autoQuantize,
      Boolean snapPalette) {

    if (projectPathStr == null || projectPathStr.isBlank()) {
      throw new IllegalArgumentException("project_path is required");
    }

    Path projectPath = Path.of(projectPathStr);
    if (!projectPath.isAbsolute()) {
      projectPath = workspaceRoot.resolve(projectPath).normalize();
    }

    if (!projectPath.startsWith(workspaceRoot)) {
      throw new SecurityException("Project path is outside workspace boundary: " + projectPath);
    }

    String effectiveAction = (action != null && !action.isBlank()) ? action.toLowerCase() : "pack";

    List<String> cmd = new ArrayList<>();

    Path script = workspaceRoot.resolve("tools/texture-packer.sh");
    Path jar =
        workspaceRoot.resolve(
            "snes-sfx-demo/build_tools/texture-packer/target/texture-packer-1.0-SNAPSHOT-jar-with-dependencies.jar");

    if (Files.exists(jar)) {
      cmd.add("java");
      cmd.add("-cp");
      cmd.add(jar.toString());
      cmd.add("dev.secondsun.superx.tools.packer.CliMainKt");
    } else if (Files.isExecutable(script)) {
      cmd.add(script.toString());
    } else {
      cmd.add("bash");
      cmd.add(script.toString());
    }

    cmd.add(effectiveAction);
    cmd.add(projectPath.toString());

    if ("pack".equals(effectiveAction)) {
      if (Boolean.TRUE.equals(autoPack)) {
        cmd.add("--auto-pack");
      }
      if (outputDirStr != null && !outputDirStr.isBlank()) {
        Path out = Path.of(outputDirStr);
        if (!out.isAbsolute()) {
          out = workspaceRoot.resolve(out).normalize();
        }
        cmd.add("--output-dir");
        cmd.add(out.toString());
      }
    } else if ("import".equals(effectiveAction)) {
      if (imagePaths != null) {
        for (String img : imagePaths) {
          Path imgPath = Path.of(img);
          if (!imgPath.isAbsolute()) {
            imgPath = workspaceRoot.resolve(imgPath).normalize();
          }
          cmd.add(imgPath.toString());
        }
      }
      if (Boolean.TRUE.equals(autoQuantize)) {
        cmd.add("--auto-quantize");
      }
      if (Boolean.TRUE.equals(snapPalette)) {
        cmd.add("--snap-palette");
      }
    }

    try {
      ProcessBuilder pb = new ProcessBuilder(cmd);
      pb.directory(workspaceRoot.toFile());
      Process proc = pb.start();

      StringBuilder stdout = new StringBuilder();
      StringBuilder stderr = new StringBuilder();

      Thread outThread =
          new Thread(
              () -> {
                try (BufferedReader reader =
                    new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                  String line;
                  while ((line = reader.readLine()) != null) {
                    stdout.append(line).append("\n");
                  }
                } catch (Exception ignored) {
                }
              });

      Thread errThread =
          new Thread(
              () -> {
                try (BufferedReader reader =
                    new BufferedReader(new InputStreamReader(proc.getErrorStream()))) {
                  String line;
                  while ((line = reader.readLine()) != null) {
                    stderr.append(line).append("\n");
                  }
                } catch (Exception ignored) {
                }
              });

      outThread.start();
      errThread.start();

      boolean finished = proc.waitFor(60, TimeUnit.SECONDS);
      outThread.join();
      errThread.join();

      if (!finished) {
        proc.destroyForcibly();
        return new TexturePackerResult(
            "FAIL", -1, stdout.toString(), "Execution timed out after 60 seconds");
      }

      int exitCode = proc.exitValue();
      return new TexturePackerResult(
          exitCode == 0 ? "SUCCESS" : "FAIL",
          exitCode,
          stdout.toString().trim(),
          stderr.toString().trim());
    } catch (Exception e) {
      return new TexturePackerResult("FAIL", -1, "", e.getMessage());
    }
  }
}
