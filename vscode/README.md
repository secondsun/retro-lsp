# retro-vscode

VS Code extension for SNES retro game development using CA65, libSFX, and the SuperFX (GSU) co-processor.

## Installation

Platform-specific extension packages are published on [GitHub Releases](https://github.com/secondsun/retro-lsp/releases). Each package includes the pre-bundled native language server — **no Java runtime installation required**.

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

## Features

- **Customizable source directory**: Use `retroca65.sourceDirectory` to set your project's source root.
- **libSFX support**: Configurable `retroca65.libSFXRoot` for integrating libSFX macros and headers.
- **Go to Definition**: Jump directly to definitions of procs, labels, enums, structs, and macro references.
- **Go to Included File**: Navigate to files specified in CA65 `.include` control commands.
- **Autocomplete File Includes**: Directory and file completions when typing `.include "..."`.
- **Control Command Completion**: Contextual autocompletion for common CA65 control commands and directives.
- **SuperFX Documentation Hovers**: Hover over register constants in `.sgs` files to view register documentation.
- **Syntax Highlighting**: Comprehensive SNES and CA65 grammar highlighting based on libSFX syntaxes.

## Configuration

The extension can be configured in your settings (`settings.json`):

| Setting | Type | Description |
| :--- | :--- | :--- |
| `retroca65.sourceDirectory` | `string` | Relative path from the workspace root to the assembly source files (default: workspace root). |
| `retroca65.libSFXRoot` | `string` | Relative path from the workspace root to the `libSFX` root directory. |
| `retroca65.serverPath` | `string` | Optional path to a custom `launcher` executable (overrides the bundled language server). |
| `retroca65.trace.server` | `string` | Traces communication between VS Code and the language server (`off`, `messages`, `verbose`). |

## Contributing & Issues

Issues and pull requests are welcome on [GitHub](https://github.com/secondsun/retro-lsp).
