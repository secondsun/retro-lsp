# retro-lsp

Language Server Protocol (LSP) implementation for retro game development on the Super Nintendo Entertainment System (SNES) using CA65, libSFX, and the SuperFX (GSU) co-processor.

## Installation

Pre-built binaries and extension packages are published on [GitHub Releases](https://github.com/secondsun/retro-lsp/releases).

### 1. VS Code Extension

Platform-specific `.vsix` packages come bundled with a self-contained runtime — **no Java installation required**.

1. Download the `.vsix` for your operating system from the latest [GitHub Release](https://github.com/secondsun/retro-lsp/releases):
   - **Linux (x64)**: `retro-vscode-linux-x64-<version>.vsix`
   - **macOS (Apple Silicon)**: `retro-vscode-darwin-arm64-<version>.vsix`
   - **Windows (x64)**: `retro-vscode-win32-x64-<version>.vsix`
2. Install the `.vsix` in VS Code:
   - **Via GUI**: Open the Extensions view (`Ctrl+Shift+X` / `Cmd+Shift+X`), click the `···` menu at the top right, select **Install from VSIX...**, and choose the downloaded file.
   - **Via Command Line**:
     ```bash
     code --install-extension retro-vscode-<platform>-<version>.vsix
     ```

### 2. Standalone Language Server (Neovim, Helix, Emacs, Sublime)

For editors other than VS Code, standalone native runtimes are available:

1. Download the archive for your platform from [GitHub Releases](https://github.com/secondsun/retro-lsp/releases):
   - **Linux (x64)**: `retro-lsp-linux-x64.tar.gz`
   - **macOS (Apple Silicon)**: `retro-lsp-darwin-arm64.tar.gz`
   - **Windows (x64)**: `retro-lsp-win32-x64.zip`
2. Extract the archive into a directory of your choice (e.g. `~/.local/share/retro-lsp` or `C:\tools\retro-lsp`).
3. The executable launcher is in the `bin/` directory:
   - Linux / macOS: `bin/launcher`
   - Windows: `bin/launcher.bat`
4. Configure your editor's LSP client to execute the launcher over standard I/O (`stdio`) for CA65 and SNES assembly files (`.s`, `.sgs`, `.i`, `.inc`).

*(Optional)* If you prefer running with your own Java installation (Java 26+), you can download `retro-lsp-<version>.jar` and run `java -jar retro-lsp-<version>.jar`.

## Configuration

The extension and language server can be configured through the following settings (e.g. in `.vscode/settings.json`):

| Setting | Type | Description |
| :--- | :--- | :--- |
| `retroca65.sourceDirectory` | `string` | Relative path from the workspace root to the assembly source files (default: workspace root). |
| `retroca65.libSFXRoot` | `string` | Relative path from the workspace root to the `libSFX` root directory. |
| `retroca65.serverPath` | `string` | Optional path to a custom `launcher` executable (overrides the bundled language server). |
| `retroca65.trace.server` | `string` | Traces communication between VS Code and the language server (`off`, `messages`, `verbose`). |

## Features

- **Customizable source directory**: Use `retroca65.sourceDirectory` to set your project's source root.
- **libSFX support**: Configurable `retroca65.libSFXRoot` for integrating libSFX macros and headers.
- **Go to Definition**: Jump directly to definitions of procs, labels, enums, structs, and macro references.
- **Go to Included File**: Navigate to files specified in CA65 `.include` control commands.
- **Autocomplete File Includes**: Directory and file completions when typing `.include "..."`.
- **Control Command Completion**: Contextual autocompletion for common CA65 control commands and directives.
- **SuperFX Documentation Hovers**: Hover over register constants in `.sgs` files to view register documentation.
- **Syntax Highlighting**: Comprehensive SNES and CA65 grammar highlighting based on libSFX syntaxes.

## Future Plans

- Dynamic help text using NaturalDocs syntax in hovers
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
