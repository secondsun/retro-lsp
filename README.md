# retro-lsp

Language Server Protocol (LSP) implementation for retro game development on the Super Nintendo Entertainment System (SNES) using CA65, libSFX, and the SuperFX (GSU) co-processor.

## Installation

The extension and language server are distributed as self-contained `.vsix` packages published on [GitHub Releases](https://github.com/secondsun/retro-lsp/releases). Each `.vsix` comes with the native language server runtime bundled inside — **no Java installation or external dependencies are required**.

### 1. Download the `.vsix` for Your Platform

Visit the [GitHub Releases](https://github.com/secondsun/retro-lsp/releases) page and download the `.vsix` matching your platform:

| Platform | File |
| :--- | :--- |
| **Linux (x64)** | `retro-vscode-linux-x64-<version>.vsix` |
| **macOS (Apple Silicon)** | `retro-vscode-darwin-arm64-<version>.vsix` |
| **Windows (x64)** | `retro-vscode-win32-x64-<version>.vsix` |

### 2. Install the `.vsix` into VS Code

You can install the `.vsix` file using either the VS Code graphical interface or the command line:

#### Method A: From the VS Code UI
1. Open VS Code.
2. Go to the **Extensions** view by clicking the Extensions icon in the Activity Bar or pressing `Ctrl+Shift+X` (Linux/Windows) / `Cmd+Shift+X` (macOS).
3. Click the **`···`** (Views and More Actions) menu button in the top-right corner of the Extensions panel.
4. Select **Install from VSIX...**
5. Locate and select the downloaded `.vsix` file.
6. Once installation completes, the extension is ready to use!

#### Method B: From the Terminal
Run the following command (substituting your downloaded file name):

```bash
code --install-extension retro-vscode-<platform>-<version>.vsix
```

*(If you use VS Code Insiders, VSCodium, or Cursor, replace `code` with `code-insiders`, `codium`, or `cursor` respectively.)*

## Configuration

The extension and language server can be configured through the following settings (e.g. in `.vscode/settings.json`):

| Setting | Type | Description |
| :--- | :--- | :--- |
| `retroca65.sourceDirectory` | `string` | Relative path from the workspace root to the assembly source files (default: workspace root). |
| `retroca65.libSFXRoot` | `string` | Relative path from the workspace root to the `libSFX` root directory. |
| `retroca65.serverPath` | `string` | Optional path to a custom `launcher` executable (overrides the bundled language server). |
| `retroca65.enableMcp` | `boolean` | Enable Model Context Protocol (MCP) mode on server launch (default: `false`). |
| `retroca65.trace.server` | `string` | Traces communication between VS Code and the language server (`off`, `messages`, `verbose`). |

### VS Code Commands

- **`RetroCA65: Restart Server`**: Gracefully stops and restarts the language server.
- **`RetroCA65: Restart Server with MCP`**: Sets `retroca65.enableMcp` to `true` and restarts the server with `--mcp`.

## Features

- **Customizable source directory**: Use `retroca65.sourceDirectory` to set your project's source root.
- **libSFX support**: Configurable `retroca65.libSFXRoot` for integrating libSFX macros and headers.
- **Go to Definition**: Jump directly to definitions of procs, labels, enums, structs, and macro references.
- **Go to Included File**: Navigate to files specified in CA65 `.include` control commands.
- **Autocomplete File Includes**: Directory and file completions when typing `.include "..."`.
- **Control Command Completion**: Contextual autocompletion for common CA65 control commands and directives.
- **Documentation Hovers**: Accurate subroutine signatures, doc comments, and opcode/directive documentation.
- **Model Context Protocol (MCP) Server**: Run `launcher --mcp` (or `--mcp-port=<port>`, default: `8085`) to expose SuperFX static analysis tools over HTTP (supporting both SSE at `/sse` and direct JSON-RPC POST at `/` or `/mcp`), while LSP continues to run concurrently over stdio:
  - `sfx_audit_invariants`: Audits code for 8-NOP pipeline settling before `stop`, 16-bit word alignment on `ldw`/`stw`, lack of hardware `MERGE` on FX3, and modal instruction lifetimes (`with`, `to`, `from`).
  - `sfx_expand_macro`: Expands X-GSU macros (`function`, `for`, `if_lt`, etc.) to ca65 SuperFX instructions via `CA65MacroExpander`.
  - `sfx_lookup_symbol`: Resolves symbol definitions, scope hierarchies, and doc comments extracted by `retro-common`.
  - `sfx_analyze_registers`: Live-range intervals, register pressure, and suggested register colorings via `sfx-optimizer`.
- **Syntax Highlighting**: Comprehensive SNES and CA65 grammar highlighting based on libSFX syntaxes.


## Future Plans

- Dynamic help text on hovers
- Refactoring tools (rename symbols and files)
- Find all references / Go to usages
- Additional autocompletion for user constants and symbols

## Building from Source

Requirements:
- JDK 26 (Oracle or OpenJDK)
- Node.js 24+ and npm

```bash
# Full build: compiles Java, runs tests, creates JLink image, and packages VSIX
./scripts/build.sh

# Run test suite & headless handshake verification
./scripts/verify.sh
```

## Credits & Acknowledgments

- [Java Language Server](https://github.com/georgewfraser/java-language-server)
- [Google Gson](https://github.com/google/gson)
- [ca65 Assembler](https://cc65.github.io/doc/ca65.html)
- [libSFX by Optiroc](https://github.com/Optiroc/libSFX)
