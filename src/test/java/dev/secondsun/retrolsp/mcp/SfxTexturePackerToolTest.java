package dev.secondsun.retrolsp.mcp;

import static org.junit.jupiter.api.Assertions.*;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SfxTexturePackerToolTest {

  @Test
  void testPackTexturesMcpExecution() {
    Path workspaceRoot = Path.of(".").toAbsolutePath().normalize();
    // In multi-repo setup, if current dir is retro-lsp, workspaceRoot is parent
    if (workspaceRoot.endsWith("retro-lsp")) {
      workspaceRoot = workspaceRoot.getParent();
    }

    RetroLspMcpServer server =
        new RetroLspMcpServer(
            new dev.secondsun.retro.util.ProjectService(
                new dev.secondsun.retro.util.FileService(),
                new dev.secondsun.retro.util.SymbolService(),
                workspaceRoot),
            null,
            workspaceRoot);

    CallToolResult result =
        server.executeTool(
            "sfx_pack_textures",
            Map.of(
                "project_path", "snes-sfx-demo/X-GSU/IsoEngine/Data/TEXTURES.sfxproj",
                "action", "validate"));

    assertFalse(result.isError(), "Expected successful tool execution");
    assertNotNull(result.content());
    assertFalse(result.content().isEmpty());

    TextContent text = (TextContent) result.content().get(0);
    assertTrue(text.text().contains("\"status\" : \"SUCCESS\""));
    assertTrue(text.text().contains("Validation passed: OK"));
  }
}
