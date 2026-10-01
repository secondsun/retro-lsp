---
name: lsp-feature-dev
description: Procedure and architectural patterns for designing, implementing, and testing new LSP features (e.g. hover, code completion, go-to-definition, document links) in retro-lsp.
---

# LSP Feature Development Skill for retro-lsp

Use this skill when developing new features or expanding existing capabilities in the `retro-lsp` language server.

---

## Architectural Workflow

All LSP capabilities in `retro-lsp` follow a decoupled feature handler pattern:

```
CA65LanguageServer
       │
       ├──> Feature.initialize(JsonObject initializeData)
       │         └── Registers capabilities (e.g. hoverProvider, definitionProvider)
       │
       └──> Feature.handle(PARAMS params, TokenizedFile fileContent)
                 └── Performs AST / Token lookups and returns LSP DTOs
```

---

## Step-by-Step Implementation Guide

### 1. Implement the Feature Interface
Create a new feature class under `src/main/java/dev/secondsun/retrolsp/feature/`:

```java
package dev.secondsun.retrolsp.feature;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.secondsun.lsp.TextDocumentPositionParams;
import dev.secondsun.retro.util.vo.TokenizedFile;
import java.util.Optional;

public class MyNewFeature implements Feature<TextDocumentPositionParams, MyResult> {
    @Override
    public void initialize(JsonObject initializeData) {
        // Advertise capability to LSP client
        initializeData.add("myNewFeatureProvider", new JsonPrimitive(true));
    }

    @Override
    public Optional<MyResult> handle(TextDocumentPositionParams params, TokenizedFile fileContent) {
        // Query AST or tokenized tokens
        var token = fileContent.tokenAt(params.position);
        if (token.isEmpty()) return Optional.empty();
        
        return Optional.of(new MyResult(...));
    }
}
```

### 2. Wire the Feature in `CA65LanguageServer.java`
1. Add a private field and instantiate it in the constructor:
   ```java
   private final MyNewFeature myNewFeature;
   ```
2. Add it to the internal `features` list in the constructor:
   ```java
   this.features.add(this.myNewFeature);
   ```
3. Implement the corresponding protocol endpoint override in `CA65LanguageServer.java`:
   ```java
   @Override
   public Optional<MyResult> myNewFeature(TextDocumentPositionParams params) {
       return myNewFeature.handle(params, projectService.getFileContents(params.textDocument.uri));
   }
   ```

### 3. Add Unit Tests
Add a test in `src/test/java/dev/secondsun/` using existing test fixtures:
- `src/test/resources/includeTest/test.sgs`
- `src/test/resources/X-GSU/`

```java
@Test
public void testMyNewFeature() throws IOException {
    CA65LanguageServer server = new CA65LanguageServer();
    InitializeParams params = new InitializeParams();
    params.rootUri = getTestDirURI();
    server.initialize(params);
    
    // Invoke feature and assert result
}
```

### 4. Verify End-to-End
Run the verification script to ensure the server starts, tests pass, and capabilities are advertised:
```bash
./scripts/verify.sh
```
