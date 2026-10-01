# Architectural Invariants for retro-lsp

These rules apply across all modifications to the repository:

1. **Java Module Identifier**:
   - Module name is strictly `dev.secondsun.retrolsp`.
   - Never reference `dev.secondsun.tm4e4lsp`.

2. **retro-common Value Objects**:
   - `Location` is in package `dev.secondsun.retro.util.vo.Location`.
   - Do NOT import `dev.secondsun.retro.util.Location`.
   - Distinguish `dev.secondsun.retro.util.vo.Location` (scanner/symbol VO) from `dev.secondsun.lsp.Location` (protocol DTO).

3. **JLink Hermetic Builds**:
   - Always run `mvn clean package` before running `jlink` to prevent stale duplicate module jar conflicts in `target/dependency`.

4. **VS Code Extension Path Resolution**:
   - Never hardcode absolute system paths in `vscode/src/extension.ts`.
   - Always use `getLauncherPath()` which respects `retroca65.serverPath` setting and relative directory resolution.

5. **Self-Verification**:
   - Always run `./scripts/verify.sh` after completing code edits to verify unit tests, JLink packaging, stdio handshake, and extension compilation.
