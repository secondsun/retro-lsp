# AGENTS.md — retro-lsp Development Guide

This file provides context, architectural constraints, and operational runbooks for AI coding agents and human developers working in `retro-lsp`.

---

## 1. Project Mission & Architecture

`retro-lsp` is a Language Server Protocol (LSP) implementation designed for retro game development on the Super Nintendo Entertainment System (SNES) using CA65 assembly, libSFX, and the SuperFX (GSU) co-processor.

### Architecture Overview
The repository contains two coupled subsystems:
1. **Java Language Server Backend** (root directory):
   - Language: Java 26 (Oracle / OpenJDK)
   - Build Tool: Maven (use `./mvnw`)
   - Module: `dev.secondsun.retrolsp` (defined in `src/main/java/module-info.java`)
   - Core dependencies: `dev.secondsun:retro-common:1.5.0`, `dev.secondsun:sfx-optimizer:0.3`, `com.google.code.gson:gson`, `dev.secondsun:languageserver:0.11.0`.
   - Native Image / Distribution: Built via `jlink` into `dist/<platform>/bin/launcher`.
2. **VS Code Extension Client** (`vscode/` directory):
   - Language: TypeScript 5.7+ (Node 24 runtime, Node16 module resolution)
   - Package manager: `npm`
   - Client Library: `vscode-languageclient` v10+
   - Communicates with the language server binary over standard I/O (`stdio`).

---

## 2. Essential Commands

Always verify changes using these standard commands:

| Action | Command | Scope |
| :--- | :--- | :--- |
| **Complete Verification** | `./scripts/verify.sh` | Runs Java tests, JLink packaging, headless LSP handshake, and VS Code compile/lint. |
| **Run Java Tests** | `./mvnw test` | Fast JUnit test suite (<2s). |
| **Format Java Code** | `./mvnw spotless:apply` | Formats all Java sources using Google Java style. |
| **Check Java Formatting** | `./mvnw spotless:check` | Verifies Java sources adhere to Google Java style. |
| **Build JLink Binary** | `./scripts/link_linux.sh` | Packages runtime to `dist/linux/bin/launcher`. |
| **Headless LSP Test** | `node ./scripts/verify_lsp_handshake.mjs` | Spawns launcher via stdio and validates JSON-RPC handshake. |
| **Compile Extension** | `cd vscode && npm run compile` | TypeScript compiler (`tsc -p ./`). |
| **Lint Extension** | `cd vscode && npm run lint` | ESLint verification. |
| **Bundle Extension** | `./scripts/bundle_extension.sh [target]` | Bundles JLink runtime into `vscode/server` and builds target `.vsix`. |
| **Package Extension** | `cd vscode && npm run package` | Builds `.vsix` non-interactively via `@vscode/vsce`. |
| **Full Release Build** | `./scripts/build.sh` | Cleans, packages backend, JLink runtime, and bundles platform extension. |

---

## 3. Critical Invariants & Rules

When modifying or refactoring this repository, you MUST respect these invariants:

1. **Module Name**:
   - The Java module name is `dev.secondsun.retrolsp`.
   - Never revert launcher commands or module descriptors to the legacy `dev.secondsun.tm4e4lsp`.
2. **Location Package**:
   - `Location` in `retro-common:1.5.0` is located at `dev.secondsun.retro.util.vo.Location`.
   - Do not import `dev.secondsun.retro.util.Location` (deprecated/removed in 1.2.7/1.5.0).
   - In LSP feature code (e.g. `GoToDefinitionLinkFeature`), distinguish between the common value object (`dev.secondsun.retro.util.vo.Location`) and the LSP protocol DTO (`dev.secondsun.lsp.Location`).
3. **Hermetic Packaging**:
   - Before running `jlink`, ensure `target/dependency` is cleanly generated (e.g. via `mvn clean package`). Multiple jar versions of the same module in `target/dependency` will cause `jlink` to abort.
4. **No Hardcoded Machine Paths**:
   - Extension launcher discovery must remain dynamic (`getLauncherPath` in `vscode/src/extension.ts`). Never hardcode local paths like `/home/...` or `C:\Users\...`.
   - Launcher discovery checks: `retroca65.serverPath` setting, bundled platform binary at `vscode/server/bin/<launcher>`, bundled subdirectory layouts, and local development `../dist/` fallback.
5. **Node16 Resolution in VS Code**:
   - `vscode/tsconfig.json` uses `"module": "Node16"` and `"moduleResolution": "Node16"`.
   - Imports from `vscode-languageclient` must use subpath exports: `import ... from "vscode-languageclient/node"`.
6. **Java Version Cadence over GraalVM**:
   - Priority is given to adopting newer Java features and staying on current/rapid JDK releases (e.g., Java 26+) rather than holding back language features or downgrading compiler targets for GraalVM Native Image LTS release cycles.
   - Do not spend effort attempting to support GraalVM Native Image or backporting bytecode until the next Java LTS release (JDK 29) is available. JLink remains the standard packaging mechanism across all supported platforms.

---

## 4. LSP Feature Implementation Pattern

To add or modify an LSP feature (e.g., hover, definition, completion):
1. Features implement `dev.secondsun.retrolsp.feature.Feature<PARAMS, RESULT>`.
2. In `initialize(JsonObject initializeData)`, advertise feature capabilities to the client.
3. In `handle(PARAMS params, TokenizedFile fileContent)`, process the request and return `Optional<RESULT>` or `RESULT`.
4. Register the feature in `CA65LanguageServer.java`.
5. Add unit tests under `src/test/java/dev/secondsun/`.
6. Run `./scripts/verify.sh` to confirm both unit tests and the stdio handshake succeed.

---

## 5. CI & Release Pipelines (GitHub Actions)

- **Continuous Integration (`.github/workflows/ci.yml`)**:
  - Triggers on push and pull requests on all branches.
  - Validates code formatting via Spotless (`./mvnw spotless:check`), Java tests, Javadoc validity, packaging, JLink Linux runtime generation, headless LSP handshake, VS Code compilation/linting, and bundles `retro-vscode-linux-x64-*.vsix`.
  - Uploads the built `.vsix` as a workflow artifact.

- **Release Pipeline (`.github/workflows/release.yml`)**:
  - Triggers on version tags (`v*`) or manual `workflow_dispatch`.
  - Uses a GitHub Actions matrix to build native runtimes and platform-specific VSIX packages across `linux-x64`, `darwin-arm64`, and `win32-x64`.
  - Creates a GitHub Release with platform-specific `.vsix` packages and auto-generated release notes.
  - Publishes each platform VSIX to VS Code Marketplace and Open VSX if `VSCE_PAT` / `OVSX_PAT` repository secrets are configured.
