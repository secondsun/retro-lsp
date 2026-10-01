# AGENTS.md — VS Code Extension Subsystem

This document governs the TypeScript client extension located in `vscode/`.

---

## Subsystem Overview
* **Role**: Visual Studio Code extension client that connects to the `retro-lsp` language server binary over stdio.
* **Target Environment**: VS Code `^1.91.0`, Node.js 20+, TypeScript 5.7+.
* **Communication**: Standard LSP JSON-RPC over `stdio`.

---

## Key Workflows
* **Compile**: `npm run compile` (`tsc -p ./`)
* **Watch Mode**: `npm run watch`
* **Lint**: `npm run lint` (`eslint src --ext ts`)
* **Package**: `npm run package` (`vsce package --no-git-tag-version`)
* **Test**: `npm test` (`node ./out/test/runTest.js`)

---

## Technical Constraints & Invariants

1. **Module System**:
   - `tsconfig.json` specifies `"module": "Node16"` and `"moduleResolution": "Node16"`.
   - All imports from Node-enabled packages with exports (like `vscode-languageclient`) must target subpaths: `vscode-languageclient/node`.
2. **Binary Resolution**:
   - The extension launches the language server binary dynamically via `getLauncherPath()`.
   - Precedence:
     1. User setting: `retroca65.serverPath`
     2. Relative path: `dist/<platform>/bin/launcher` (or `.bat` on Windows)
     3. Parent directory: `../dist/<platform>/bin/launcher`
   - Never reintroduce hardcoded absolute developer machine paths.
3. **Packaging Integrity**:
   - The extension root must maintain LICENSE so `vsce package` can package non-interactively without prompting.
   - Ignore patterns in .vscodeignore prevent test files, maps, and intermediate typescript outputs from bloating the final `.vsix`.
4. **Grammar & Configuration**:
   - Assembly language ID: `retroca65`.
   - TextMate grammar definition: `vscode/syntaxes/snes.json`.
   - Language configuration: `vscode/language-configuration.json`.
