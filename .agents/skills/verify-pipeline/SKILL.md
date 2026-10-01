---
name: verify-pipeline
description: Runs and diagnoses the complete retro-lsp verification pipeline, including Java unit tests, JLink native image generation, headless stdio LSP handshake, and VS Code extension compile/lint.
---

# Verify Pipeline Skill for retro-lsp

Use this skill whenever verifying changes, before committing, or when diagnosing pipeline failures across the Java backend and TypeScript client.

## Standard Execution

Run the unified verification script from the repository root:

```bash
./scripts/verify.sh
```

A healthy build completes in under 10 seconds and concludes with:
`✓ ALL VERIFICATION CHECKS PASSED!`

---

## Step-by-Step Breakdown & Diagnostics

### Step 1: Java Unit Tests (`./mvnw test`)
* **What it does**: Executes all JUnit 5 tests against `CA65LanguageServer` and feature handlers.
* **Common Failure**: `NoClassDefFoundError: dev/secondsun/retro/util/Location`
  * **Cause**: `Location` was moved to `dev.secondsun.retro.util.vo.Location` in `retro-common:1.2.7`.
  * **Fix**: Ensure `sfx-optimizer` is built against `retro-common:1.2.7` and all references import `dev.secondsun.retro.util.vo.Location`.

### Step 2: JLink Native Packaging (`./scripts/link_linux.sh`)
* **What it does**: Uses JDK's `jlink` to build a self-contained runtime into `dist/linux/bin/launcher`.
* **Common Failure**: `Two versions of module dev.secondsun.retro.util found in target/dependency`
  * **Cause**: Stale jar files in `target/dependency` after version bumps.
  * **Fix**: Run `./mvnw clean package -DskipTests` before executing `link_linux.sh`.
* **Common Failure**: `module not found: dev.secondsun.tm4e4lsp`
  * **Cause**: The module name is `dev.secondsun.retrolsp`. Verify `src/main/java/module-info.java` matches the launcher argument.

### Step 3: Headless LSP Handshake (`node ./scripts/verify_lsp_handshake.mjs`)
* **What it does**: Launches `dist/linux/bin/launcher` in stdio mode, transmits an LSP `initialize` request, asserts that `capabilities` contains `hoverProvider` and `completionProvider`, and cleanly shuts down.
* **Common Failure**: Timeout or `Cannot invoke "java.net.URI.isAbsolute()" because "uri" is null`.
  * **Cause**: `initialize` was called without a `rootUri`. Ensure `CA65LanguageServer.initialize` checks for null `workspaceRoot`.

### Step 4: VS Code Extension (`cd vscode && npm run compile && npm run lint`)
* **What it does**: Compiles TypeScript with Node16 module resolution and runs ESLint.
* **Common Failure**: `Cannot find module 'vscode-languageclient/node' under your current moduleResolution`.
  * **Fix**: `vscode/tsconfig.json` must specify `"module": "Node16"` and `"moduleResolution": "Node16"`.
* **Common Failure**: `Definition for rule '@typescript-eslint/semi' was not found`.
  * **Fix**: Formatting rules in typescript-eslint v8 use core ESLint `"semi": ["warn", "always"]`.
