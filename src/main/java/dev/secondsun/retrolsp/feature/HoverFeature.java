package dev.secondsun.retrolsp.feature;

import static dev.secondsun.retro.util.instruction.GSUInstruction.isInstruction;
import static dev.secondsun.retro.util.instruction.GSUInstruction.mark;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.secondsun.lsp.Hover;
import dev.secondsun.lsp.MarkedString;
import dev.secondsun.lsp.Position;
import dev.secondsun.lsp.Range;
import dev.secondsun.lsp.TextDocumentPositionParams;
import dev.secondsun.retro.util.*;
import dev.secondsun.retro.util.vo.TokenizedFile;
import dev.secondsun.retro.util.vo.Tokens;
import dev.secondsun.sfxoptimizer.Constants;
import dev.secondsun.sfxoptimizer.IntervalKey;
import dev.secondsun.sfxoptimizer.graphbuilder.CA65Grapher;
import java.net.URI;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Feature providing hover documentation and opcode/directive/symbol information in CA65 assembly
 * code.
 */
public class HoverFeature implements Feature<TextDocumentPositionParams, Hover> {

  private final FileService fileService;
  private final SymbolService symbolService;
  private final ProjectService projectService;
  private final CA65Grapher grapher;

  /**
   * Constructs a new {@code HoverFeature} with the specified file and symbol services.
   *
   * @param fileService the file service used to resolve file references
   * @param symbolService the symbol service used to inspect symbol definitions
   */
  public HoverFeature(FileService fileService, SymbolService symbolService) {
    this(fileService, symbolService, null);
  }

  /**
   * Constructs a new {@code HoverFeature} with file, symbol, and project services.
   *
   * @param fileService the file service used to resolve file references
   * @param symbolService the symbol service used to inspect symbol definitions
   * @param projectService the project service providing cached files
   */
  public HoverFeature(
      FileService fileService, SymbolService symbolService, ProjectService projectService) {
    this.fileService = fileService;
    this.symbolService = symbolService;
    this.projectService = projectService;
    this.grapher = new CA65Grapher(symbolService, fileService);
  }

  @Override
  public void initialize(JsonObject initializeData) {
    initializeData.add("hoverProvider", new JsonPrimitive(true));
  }

  @Override
  public Optional<Hover> handle(TextDocumentPositionParams params, TokenizedFile fileContent) {
    if (fileContent == null
        || params.position.line < 0
        || params.position.line >= fileContent.textLines()) {
      return Optional.of(new Hover(new ArrayList<>()));
    }

    var lineTokens = fileContent.getLineTokens(params.position.line);
    if (lineTokens == null || lineTokens.isEmpty()) {
      return Optional.of(new Hover(new ArrayList<>()));
    }

    int col = Math.max(0, params.position.character);
    Optional<Token> maybeToken = Util.getTokenAt(lineTokens, col);

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

    // 1. GSU instruction opcode hover
    if (isInstruction(token)) {
      mark(token);
      var result = lookupHover(tokenText);
      if (result != null) {
        hover.contents.add(result);
      }
      return Optional.of(hover);
    }

    URI docUri = params.textDocument != null ? params.textDocument.uri : null;
    int line = params.position.line;

    // 2. Pseudo-macro keyword hover: function, endfunction, call, return
    var pseudoMacroHover = lookupPseudoMacroHover(tokenText);
    if (pseudoMacroHover != null) {
      List<String> macroSections = new ArrayList<>();
      macroSections.add(pseudoMacroHover.value);

      // If hovering over 'call', also show signature of target if present
      if (tokenText.equalsIgnoreCase("call") && FunctionSyntaxHelper.isCall(lineTokens)) {
        var callStmt = FunctionSyntaxHelper.parseCall(lineTokens);
        if (callStmt != null && callStmt.targetName() != null) {
          String sig = findFunctionSignature(callStmt.targetName(), docUri, line, fileContent);
          if (sig != null) {
            macroSections.add("```ca65\n" + sig + "\n```");
          }
        }
      }

      hover.contents.add(new MarkedString(String.join("\n\n", macroSections)));
      return Optional.of(hover);
    }

    // 3. Signature help for functions (at function declaration or at call site)
    String functionSignature = null;
    if (FunctionSyntaxHelper.isCall(lineTokens)) {
      var callStmt = FunctionSyntaxHelper.parseCall(lineTokens);
      if (callStmt != null && callStmt.targetName() != null) {
        if (tokenText.equalsIgnoreCase(callStmt.targetName())) {
          functionSignature =
              findFunctionSignature(callStmt.targetName(), docUri, line, fileContent);
        }
      }
    }
    if (functionSignature == null) {
      functionSignature = findFunctionSignature(tokenText, docUri, line, fileContent);
    }

    // 4. Extracted symbol documentation
    String doc = symbolService.getDocumentation(tokenText, docUri, line);
    if (doc == null || doc.isBlank()) {
      doc = symbolService.getDocumentation(tokenText);
    }

    // 5. GSU grapher analysis (context-aware lookup)
    String registerUsage = null;
    var symbol = symbolService.getLocation(tokenText, docUri, line);
    if (symbol == null) {
      symbol = symbolService.getLocation(tokenText);
    }
    if (symbol != null) {
      try {
        TokenizedFile targetFile = null;
        if (docUri != null && symbol.filename() != null && docUri.equals(symbol.filename())) {
          targetFile = fileContent;
        }
        if (targetFile == null && projectService != null) {
          targetFile = projectService.getFileContents(symbol.filename());
        }
        if (targetFile == null || targetFile == TokenizedFile.EMPTY) {
          targetFile = fileService.readLines(symbol.filename());
        }
        if (targetFile != null && targetFile != TokenizedFile.EMPTY) {
          var graph = grapher.graph(targetFile, symbol.line());
          if (graph != null && graph.getStartNode() != null) {
            var intervals =
                Arrays.stream(Constants.Register.values())
                    .map(
                        register ->
                            graph.getStartNode().intervals(new IntervalKey.RegisterKey(register)))
                    .filter(Objects::nonNull)
                    .toList();

            var string = new StringBuilder(" Uses : ");
            if (intervals.isEmpty()) {
              string.append(" NONE");
            } else {
              intervals.forEach(
                  interval -> {
                    string.append(String.format(" %s, ", interval.getKey().toString()));
                  });
            }
            registerUsage = string.toString();
          }
        }
      } catch (Exception e) {
        Logger.getAnonymousLogger()
            .log(Level.WARNING, "Failed to analyze hover graph: " + e.getMessage(), e);
      }
    }

    // Combine signature, doc, and register usage into hover content
    List<String> sections = new ArrayList<>();
    if (functionSignature != null) {
      sections.add("```ca65\n" + functionSignature + "\n```");
    }
    if (doc != null && !doc.isBlank()) {
      sections.add(doc.strip());
    }
    if (registerUsage != null && !registerUsage.isBlank()) {
      sections.add(registerUsage.strip());
    }

    if (!sections.isEmpty()) {
      hover.contents.add(new MarkedString(String.join("\n\n", sections)));
      return Optional.of(hover);
    }

    return Optional.of(new Hover(new ArrayList<>()));
  }

  private String findFunctionSignature(
      String functionName, URI uri, int line, TokenizedFile fileContent) {
    if (functionName == null || functionName.isBlank()) {
      return null;
    }
    var defLoc = symbolService.getLocation(functionName, uri, line);
    if (defLoc == null) {
      defLoc = symbolService.getLocation(functionName);
    }
    if (defLoc == null || defLoc.filename() == null) {
      return null;
    }
    TokenizedFile targetFile = null;
    try {
      if (uri != null && defLoc.filename() != null && uri.equals(defLoc.filename())) {
        targetFile = fileContent;
      }
      if (targetFile == null && projectService != null) {
        targetFile = projectService.getFileContents(defLoc.filename());
      }
      if (targetFile == null || targetFile == TokenizedFile.EMPTY) {
        targetFile = fileService.readLines(defLoc.filename());
      }
      if (targetFile != null && defLoc.line() >= 0 && defLoc.line() < targetFile.textLines()) {
        Tokens defLineTokens = targetFile.getLine(defLoc.line());
        if (defLineTokens != null && FunctionSyntaxHelper.isFunction(defLineTokens)) {
          var decl = FunctionSyntaxHelper.parseFunction(defLineTokens);
          if (decl != null) {
            StringBuilder sig = new StringBuilder("function ").append(decl.name());
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
    } catch (Exception e) {
      Logger.getAnonymousLogger()
          .log(Level.FINE, "Failed to load function definition file: " + e.getMessage(), e);
    }
    return null;
  }

  private MarkedString lookupPseudoMacroHover(String keyword) {
    return switch (keyword.toUpperCase()) {
      case "FUNCTION" ->
          new MarkedString(
              """
          `function <name> [param1, param2, ...] [: <return_var>]`

          Defines a high-level function with scoped parameters and optional return variable.
          """);
      case "ENDFUNCTION" ->
          new MarkedString(
              """
          `endfunction`

          Terminates a `function` definition block.
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

  private MarkedString lookupHover(String tokenText) {
    return switch (tokenText.toUpperCase()) {
      case "NOP" -> new MarkedString("No operation");
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
