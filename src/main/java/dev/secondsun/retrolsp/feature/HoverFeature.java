package dev.secondsun.retrolsp.feature;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.secondsun.lsp.Hover;
import dev.secondsun.lsp.MarkedString;
import dev.secondsun.lsp.Position;
import dev.secondsun.lsp.Range;
import dev.secondsun.lsp.TextDocumentPositionParams;
import dev.secondsun.retro.util.CA65Scanner;
import dev.secondsun.retro.util.DefaultWorkspaceIndex;
import dev.secondsun.retro.util.FunctionSyntaxHelper;
import dev.secondsun.retro.util.Token;
import dev.secondsun.retro.util.WorkspaceIndex;
import dev.secondsun.retro.util.WorkspaceSnapshot;
import dev.secondsun.retro.util.sfx.ModalStepEvaluator;
import dev.secondsun.retro.util.sfx.SuperFxIsa;
import dev.secondsun.retro.util.vo.TokenizedFile;
import dev.secondsun.retro.util.vo.Tokens;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Feature providing hover documentation and opcode, register, pseudo-macro, and symbol information
 * in CA65 and X-GSU assembly code.
 */
public class HoverFeature implements Feature<TextDocumentPositionParams, Hover> {

  private static final Logger LOG = Logger.getLogger(HoverFeature.class.getName());

  private final WorkspaceIndex workspaceIndex;

  /** Constructs a standalone {@code HoverFeature} with a default workspace index. */
  public HoverFeature() {
    this(new DefaultWorkspaceIndex());
  }

  /**
   * Constructs a new {@code HoverFeature} backed by the specified {@link WorkspaceIndex}.
   *
   * @param workspaceIndex workspace index providing snapshots and symbol documentation
   */
  public HoverFeature(WorkspaceIndex workspaceIndex) {
    this.workspaceIndex = Objects.requireNonNull(workspaceIndex, "workspaceIndex must not be null");
  }

  @Override
  public void initialize(JsonObject initializeData) {
    initializeData.add("hoverProvider", new JsonPrimitive(true));
  }

  @Override
  public Optional<Hover> handle(TextDocumentPositionParams params, TokenizedFile fileContent) {
    if (fileContent == null
        || params == null
        || params.position == null
        || params.position.line < 0
        || params.position.line >= fileContent.textLines()) {
      return Optional.of(new Hover(new ArrayList<>()));
    }

    var lineTokens = fileContent.getLineTokens(params.position.line);
    if (lineTokens == null || lineTokens.isEmpty()) {
      return Optional.of(new Hover(new ArrayList<>()));
    }

    int col = Math.max(0, params.position.character);
    Optional<Token> maybeToken = fileContent.getTokenAt(params.position.line, col);
    if (maybeToken.isEmpty()) {
      return Optional.of(new Hover(new ArrayList<>()));
    }

    var token = maybeToken.get();
    String tokenText = token.text();
    if (tokenText == null || tokenText.isBlank()) {
      return Optional.of(new Hover(new ArrayList<>()));
    }

    var hover = new Hover(new ArrayList<>());
    hover.range =
        new Range(
            new Position(params.position.line, token.getStartIndex()),
            new Position(params.position.line, token.getEndIndex()));

    // 1. Hardware register / status / control register hover
    MarkedString regHover = lookupRegisterHover(tokenText);
    if (regHover != null) {
      hover.contents.add(regHover);
      return Optional.of(hover);
    }

    // 2. GSU instruction opcode hover via SuperFxIsa
    Optional<SuperFxIsa.OpcodeSpec> isaOpt = SuperFxIsa.lookup(tokenText);
    if (isaOpt.isPresent()) {
      hover.contents.add(formatIsaHover(isaOpt.get()));
      return Optional.of(hover);
    }

    URI docUri = params.textDocument != null ? params.textDocument.uri : null;
    int line = params.position.line;

    WorkspaceSnapshot snapshot = workspaceIndex.snapshot();
    if (docUri != null && snapshot.file(docUri).isEmpty() && !fileContent.isEmpty()) {
      snapshot = snapshot.overlay(docUri, String.join("\n", fileContent.getRawLines()));
    }

    // 3. Pseudo-macro keyword hover: inline, function, endfunction, call, return
    var pseudoMacroHover = lookupPseudoMacroHover(tokenText);
    if (pseudoMacroHover != null) {
      List<String> macroSections = new ArrayList<>();
      macroSections.add(pseudoMacroHover.value);

      int opIdx = ModalStepEvaluator.findOpcodeIndex(lineTokens);
      List<Token> tail = opIdx >= 0 ? lineTokens.subList(opIdx, lineTokens.size()) : lineTokens;
      if (tokenText.equalsIgnoreCase("call") && FunctionSyntaxHelper.isCall(tail)) {
        var callStmt = FunctionSyntaxHelper.parseCall(tail);
        if (callStmt != null && callStmt.targetName() != null) {
          String sig =
              findFunctionSignature(snapshot, callStmt.targetName(), docUri, line, fileContent);
          if (sig != null) {
            macroSections.add("```ca65\n" + sig + "\n```");
          }
        }
      }

      hover.contents.add(new MarkedString(String.join("\n\n", macroSections)));
      return Optional.of(hover);
    }

    // 4. Signature help for [inline] functions (at declaration or call site)
    int opIdx = ModalStepEvaluator.findOpcodeIndex(lineTokens);
    List<Token> tail = opIdx >= 0 ? lineTokens.subList(opIdx, lineTokens.size()) : lineTokens;
    String functionSignature = null;
    if (FunctionSyntaxHelper.isCall(tail)) {
      var callStmt = FunctionSyntaxHelper.parseCall(tail);
      if (callStmt != null && callStmt.targetName() != null) {
        if (tokenText.equalsIgnoreCase(callStmt.targetName())) {
          functionSignature =
              findFunctionSignature(snapshot, callStmt.targetName(), docUri, line, fileContent);
        }
      }
    }
    if (functionSignature == null) {
      functionSignature = findFunctionSignature(snapshot, tokenText, docUri, line, fileContent);
    }

    // 5. Extracted symbol documentation
    String doc = snapshot.resolveDocumentation(tokenText, docUri, line);

    List<String> sections = new ArrayList<>();
    if (functionSignature != null) {
      sections.add("```ca65\n" + functionSignature + "\n```");
    }
    if (doc != null && !doc.isBlank()) {
      sections.add(doc.strip());
    }

    if (!sections.isEmpty()) {
      hover.contents.add(new MarkedString(String.join("\n\n", sections)));
      return Optional.of(hover);
    }

    return Optional.of(new Hover(new ArrayList<>()));
  }

  private String findFunctionSignature(
      WorkspaceSnapshot snapshot,
      String functionName,
      URI uri,
      int line,
      TokenizedFile fileContent) {
    if (functionName == null || functionName.isBlank()) {
      return null;
    }
    var defLoc = snapshot.resolveLocation(functionName, uri, line);
    if (defLoc == null || defLoc.filename() == null) {
      return null;
    }
    TokenizedFile targetFile = null;
    try {
      if (uri != null && uri.equals(defLoc.filename())) {
        targetFile = fileContent;
      }
      if (targetFile == null || targetFile.isEmpty()) {
        targetFile = snapshot.file(defLoc.filename());
      }
      if ((targetFile == null || targetFile.isEmpty())
          && workspaceIndex.isInsideWorkspace(defLoc.filename())) {
        targetFile = workspaceIndex.getFileContents(defLoc.filename());
      }
      if ((targetFile == null || targetFile.isEmpty())
          && "file".equalsIgnoreCase(defLoc.filename().getScheme())) {
        Path p = Path.of(defLoc.filename());
        if (Files.isRegularFile(p)) {
          targetFile = new CA65Scanner().tokenize(Files.readString(p), defLoc.filename());
        }
      }
      if (targetFile != null && defLoc.line() >= 0 && defLoc.line() < targetFile.textLines()) {
        Tokens defLineTokens = targetFile.getLine(defLoc.line());
        if (defLineTokens != null) {
          List<Token> toks = defLineTokens.tokens();
          int opIdx = ModalStepEvaluator.findOpcodeIndex(toks);
          List<Token> tail = opIdx >= 0 ? toks.subList(opIdx, toks.size()) : toks;
          if (FunctionSyntaxHelper.isFunction(tail)) {
            var decl = FunctionSyntaxHelper.parseFunction(tail);
            if (decl != null) {
              StringBuilder sig =
                  new StringBuilder(decl.isInline() ? "inline function " : "function ")
                      .append(decl.name());
              if (decl.parameters() != null && !decl.parameters().isEmpty()) {
                sig.append(" ")
                    .append(
                        decl.parameters().stream()
                            .map(Token::text)
                            .collect(Collectors.joining(", ")));
              }
              if (decl.returnVariable() != null && decl.returnVariable().isPresent()) {
                sig.append(" : ").append(decl.returnVariable().get().text());
              }
              return sig.toString();
            }
          }
        }
      }
    } catch (Exception e) {
      LOG.log(Level.FINE, "Failed to load function definition file: " + e.getMessage(), e);
    }
    return null;
  }

  private MarkedString lookupPseudoMacroHover(String keyword) {
    return switch (keyword.toUpperCase(Locale.ROOT)) {
      case "INLINE" ->
          new MarkedString(
              """
          `inline function <name> [param1, param2, ...] [: <return_var>]`

          Defines an inlined high-level SuperFX function expanded directly at each `call` site without `link`/`r11` overhead.
          """);
      case "FUNCTION" ->
          new MarkedString(
              """
          `[inline] function <name> [param1, param2, ...] [: <return_var>]`

          Defines a high-level function with scoped parameters and optional return variable.
          """);
      case "ENDFUNCTION" ->
          new MarkedString(
              """
          `endfunction`

          Terminates a `function` or `inline function` definition block.
          """);
      case "CALL" ->
          new MarkedString(
              """
          `call <target> [arg1, arg2, ...] [: <destination_var>]`

          Invokes a function with arguments and optional destination variable.
          """);
      case "RETURN" ->
          new MarkedString(
              """
          `return [<return_var>]`

          Returns from a `function`, optionally returning a value.
          """);
      default -> null;
    };
  }

  private MarkedString formatIsaHover(SuperFxIsa.OpcodeSpec spec) {
    if ("nop".equalsIgnoreCase(spec.mnemonic())) {
      return new MarkedString("No operation");
    }
    return new MarkedString(
        String.format(
            "`%s` — SuperFX %s instruction (%d byte%s)",
            spec.mnemonic(),
            spec.category(),
            spec.baseByteWeight(),
            spec.baseByteWeight() == 1 ? "" : "s"));
  }

  private MarkedString lookupRegisterHover(String tokenText) {
    return switch (tokenText.toUpperCase(Locale.ROOT)) {
      case "R0",
          "R1",
          "R2",
          "R3",
          "R4",
          "R5",
          "R6",
          "R7",
          "R8",
          "R9",
          "R10",
          "R11",
          "R12",
          "R13",
          "R14",
          "R15" ->
          registers();
      case "SFR" -> statusFlagRegister();
      case "BRAMR", "PBR", "ROMBR", "CFGR", "SCBR", "CLSR", "SCMR", "VCR", "RAMBR", "CBR" ->
          controlRegisters();
      default -> null;
    };
  }

  private MarkedString controlRegisters() {
    return new MarkedString(
        """
        | Register | Address |                                   | Size    |     |
        |----------|---------|-----------------------------------|---------|-----|
        | BRAMR    | 3033    | Backup RAM register               | 8 bits  | W   |
        | PBR      | 3034    | program bank register             | 8 bits  | R/W |
        | ROMBR    | 3036    | rom bank register                 | 8 bits  | R   |
        | CFGR     | 3037    | control flags register            | 8 bits  | W   |
        | SCBR     | 3038    | screen base register              | 8 bits  | W   |
        | CLSR     | 3039    | clock speed register              | 8 bits  | W   |
        | SCMR     | 303a    | screen mode register              | 8 bits  | W   |
        | VCR      | 303b    | version code register (read only) | 8 bits  | R   |
        | RAMBR    | 303c    | ram bank register                 | 8 bits  | R   |
        | CBR      | 303e    | cache base register               | 16 bits | R   |
        """);
  }

  private MarkedString statusFlagRegister() {
    return new MarkedString(
        """
        | Bit |                               Description                               |
        |:---:|:-----------------------------------------------------------------------:|
        | 0   | -                                                                       |
        | 1   | Z Zero flag                                                             |
        | 2   | CY Carry flag                                                           |
        | 3   | S Sign flag                                                             |
        | 4   | OV Overflow flag                                                        |
        | 5   | G Go flag (set to 1 when the GSU is running)                            |
        | 6   | R Set to 1 when reading ROM using R14 address                           |
        | 7   | -                                                                       |
        | 8   | ALT1 Mode set-up flag for the next instruction                          |
        | 9   | ALT2 Mode set-up flag for the next instruction                          |
        | 10  | IL Immediate lower 8-bit flag                                           |
        | 11  | IH Immediate higher 8-bit flag                                          |
        | 12  | B Set to 1 when the WITH instruction is executed                        |
        | 13  | -                                                                       |
        | 14  | -                                                                       |
        | 15  | IRQ Set to 1 when GSU caused an interrupt. Set to 0 when read by 658c16 |
        """);
  }

  private MarkedString registers() {
    return new MarkedString(
        """
        | Register | Address | Description                               | Access from SNES |   |
        |----------|---------|-------------------------------------------|------------------|---|
        | R0       | 3000    | default source/destination register       | R/W              |   |
        | R1       | 3002    | pixel plot X position register            | R/W              |   |
        | R2       | 3004    | pixel plot Y position register            | R/W              |   |
        | R3       | 3006    | for general use                           | R/W              |   |
        | R4       | 3008    | lower 16 bit result of lmult              | R/W              |   |
        | R5       | 300a    | for general use                           | R/W              |   |
        | R6       | 300c    | multiplier for fmult and lmult            | R/W              |   |
        | R7       | 300e    | fixed point texel X position for merge    | R/W              |   |
        | R8       | 3010    | fixed point texel Y position for merge    | R/W              |   |
        | R9       | 3012    | for general use                           | R/W              |   |
        | R10      | 3014    | for general use                           | R/W              |   |
        | R11      | 3016    | return address set by link                | R/W              |   |
        | R12      | 3018    | loop counter                              | R/W              |   |
        | R13      | 301a    | loop point address                        | R/W              |   |
        | R14      | 301c    | rom address for getb, getbh, getbl, getbs | R/W              |   |
        | R15      | 301e    | program counter                           | R/W              |   |
        """);
  }
}
