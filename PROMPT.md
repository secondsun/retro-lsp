# Task Prompt: Implement Support for retro-common 1.5.0 (Changes Since 1.3.1)

## Context & Objectives
`retro-common` was upgraded from version `1.3.1` to `1.5.0`. This upgrade introduces significant enhancements:
1. **Hierarchical Lexical Scopes (`Scope`, `ScopeType`)**:
   - `SymbolService` now organizes symbols within nested scopes: `GLOBAL`, `FILE`, `FUNCTION`, `PROC`, `STRUCT`, `SCOPE`, `ENUM`, `MACRO`.
   - Context-aware symbol lookups:
     - `symbolService.getLocation(String name, URI uri, int line)`
     - `symbolService.getLocation(Token token, URI uri)`
     - `symbolService.getLocations(String name, URI uri, int line)` (for multiple/scoped definitions)
     - `symbolService.getDocumentation(String name, URI uri, int line)`
     - `symbolService.getScopeAt(URI uri, int line)`
   - Scoped definitions:
     - Function parameters and non-register return variables are registered in their function's lexical scope (and globally qualified as `<func>::<param>`).
     - Struct members, procedure locals, and macro locals are resolved within their enclosing scope or via `::` qualified names.
2. **First-Class Pseudo-Macro Statements (`function`, `endfunction`, `call`, `return`)**:
   - `FunctionSyntaxHelper` parses and validates high-level control-flow statements into typed VO records: `FunctionDeclaration`, `CallStatement`, and `ReturnStatement`.
   - `TokenType` additions: `TOK_FUNCTION`, `TOK_ENDFUNCTION`, `TOK_CALL`, `TOK_RETURN`, and `TOK_REGISTER_KEYWORD`.
   - Syntax validation marks offending tokens with `TokenAttribute.ERROR` and `token.message`, setting `hasErrors() == true`.
3. **Location Record Addition**:
   - `Location(URI filename, int line)` convenience constructor.

Currently, `retro-lsp` only uses flat global symbol resolution, does not display extracted symbol documentation in hovers, does not offer completion for pseudo-macros, and misses `endfunction` in TextMate syntax definitions.

Your task is to implement full support for these `retro-common` features across `retro-lsp`.

---

## Required Tasks & Implementation Plan

### 1. Dependency & Documentation Alignment
- In `pom.xml`: ensure the `retro-common` dependency is set to `1.5.0`.
- In `AGENTS.md` and `.agents/rules/invariants.md`: update all references to `retro-common:1.2.7` or `1.3.1` to reflect `retro-common:1.5.0`.
- Ensure `./mvnw compile` succeeds.

### 2. Context-Aware Go-to-Definition (`GoToDefinitionLinkFeature`)
- **File**: `src/main/java/dev/secondsun/retrolsp/feature/GoToDefinitionLinkFeature.java`
- **Updates**:
  1. Replace global-only lookup `symbolService.getLocation(label.trim())` with context-aware lookup:
     `symbolService.getLocation(label.trim(), params.textDocument.uri, params.position.line)`.
  2. Support both `TokenType.TOK_IDENT` and `TokenType.TOK_LOCAL_IDENT` tokens at the cursor.
  3. Support qualified symbol references (`scope::name` or `::name`). If the cursor is on or adjacent to a namespace delimiter `::`, reconstruct the full qualified symbol name and query `symbolService.getLocation(qualifiedName, uri, line)`.
  4. Support multi-definition navigation: If multiple definitions exist (e.g. across scopes or interfaces), use `symbolService.getLocations(name, uri, line)` and map all matches to LSP `Location` DTOs.
  5. Validate that navigation works for:
     - Local labels inside `.proc` and `function` blocks (resolving to the local declaration, not conflicting labels in other functions).
     - Function parameters and return variables (resolving to the `function` definition header).
     - Struct fields accessed via `struct::field`.

### 3. Documentation & Signature Hover Support (`HoverFeature`)
- **File**: `src/main/java/dev/secondsun/retrolsp/feature/HoverFeature.java`
- **Updates**:
  1. When hovering over symbols (labels, functions, procs, macros, parameters):
     - Query `symbolService.getDocumentation(tokenText, params.textDocument.uri, params.position.line)`.
     - If documentation exists, render it cleanly in the hover contents as Markdown (`new MarkedString(doc)`).
  2. Context-aware symbol resolution for GSU grapher:
     - Use `symbolService.getLocation(tokenText, params.textDocument.uri, params.position.line)` so that registers/data analysis targets the scoped symbol.
  3. Signature help in hover for `function` and `call`:
     - If the token is a function name or cursor is on a `call <target>` statement, look up the `FunctionDeclaration` (or function definition line) and display the function signature:
       e.g. `function <name> [<param1>, <param2>, ...] [: <return_var>]`.
  4. Pseudo-macro keywords:
     - When hovering over `function`, `endfunction`, `call`, or `return`, provide a concise syntax description of the pseudo-macro statement.

### 4. Code Completion for Pseudo-Macros & Scoped Symbols (`CompletionFeature`)
- **Files**:
  - `src/main/java/dev/secondsun/retrolsp/feature/DirectiveCompletionFeature.java` (or a dedicated completion feature)
  - `src/main/java/dev/secondsun/retrolsp/CA65LanguageServer.java`
- **Updates**:
  1. Add autocompletion for pseudo-macro keywords:
     - Suggest `function`, `endfunction`, `call`, `return` when typing at statement position.
  2. Scope-aware symbol completion:
     - When completing identifiers, suggest symbols visible in the current lexical scope via `symbolService.getScopeAt(params.textDocument.uri, params.position.line)` alongside global symbols.

### 5. Diagnostics Reporting (Optional / Recommended Enhancement)
- If wiring `LanguageClient.publishDiagnostics`:
  - When tokenizing/indexing a file in `ProjectService` or on document changes, check lines with `FunctionSyntaxHelper`:
    - `isFunction`, `isEndFunction`, `isCall`, `isReturn`.
  - Collect tokens with `TokenAttribute.ERROR` and publish LSP diagnostics with `token.message`.

### 6. VS Code TextMate Grammar Highlighting
- **File**: `vscode/syntaxes/snes.json`
- **Update**:
  - Find pattern matching `"\\b(?i:function|call|return)\\b"` and update it to include `endfunction`:
    `"\\b(?i:function|endfunction|call|return)\\b"`.

### 7. Unit Tests & Verification
- **Unit Tests**:
  - Create or update tests in `src/test/java/dev/secondsun/`:
    - `DefinitionTest.java`: Add tests for:
      - Go-to-definition resolving local labels in scoped functions (avoiding false hits in other scopes).
      - Go-to-definition on function parameters resolving to the `function` definition line.
      - Go-to-definition on qualified references `scope::symbol`.
    - `HoverTest.java`: Add tests for:
      - Hover displaying doc comments extracted from preceding comment blocks on functions/labels.
      - Hover on function declarations displaying signature.
    - `CompletionTest.java`: Add tests for:
      - Pseudo-macro keyword completions (`function`, `call`, `return`, `endfunction`).
- **Pipeline Verification**:
  - Run `./mvnw clean test` to ensure all JUnit tests pass.
  - Run `./mvnw spotless:apply` and `./mvnw spotless:check` to ensure code formatting complies.
  - Run `./scripts/verify.sh` to run the full verification suite (Java tests, JLink packaging, headless LSP handshake, VS Code extension compile/lint).

---

## Architectural Constraints & Invariants
- **Module Name**: Strictly `dev.secondsun.retrolsp` (do not reference `tm4e4lsp`).
- **Location Packages**: Keep `dev.secondsun.retro.util.vo.Location` (scanner/symbol VO) distinct from `dev.secondsun.lsp.Location` (LSP protocol DTO).
- **Paths**: Never use hardcoded machine paths (`/Users/...`, `/home/...`). Always use `java.net.URI` and relative paths.
- **Java Release**: Target Java 26 via `./mvnw`.

