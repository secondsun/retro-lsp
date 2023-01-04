package dev.secondsun.tm4e4lsp.feature;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import dev.secondsun.lsp.Hover;
import dev.secondsun.lsp.MarkedString;
import dev.secondsun.lsp.Position;
import dev.secondsun.lsp.Range;
import dev.secondsun.lsp.TextDocumentPositionParams;
import dev.secondsun.tm4e.core.grammar.IGrammar;
import dev.secondsun.tm4e.core.grammar.IToken;
import dev.secondsun.tm4e.core.grammar.ITokenizeLineResult;
import dev.secondsun.tm4e4lsp.util.Util;

public class HoverFeature implements Feature<TextDocumentPositionParams, Hover> {

    private IGrammar grammar;

    public HoverFeature(IGrammar grammar) {
        this.grammar = grammar;
	}

	@Override
    public void initialize(JsonObject initializeData) {
        initializeData.add("hoverProvider", new JsonPrimitive(true));
    }

	public Optional<Hover> handle(TextDocumentPositionParams params, List<String> fileContent) {
        String line = fileContent.get(params.position.line);
        ITokenizeLineResult lineTokens = grammar.tokenizeLine(line);

        Optional<IToken> maybeToken = Util.getTokenAt(lineTokens, params.position.character);

        if (maybeToken.isPresent()) {
            var token = maybeToken.get();
            String tokenText =  Util.getTokenText(line, token);
            var hover = new Hover();
            hover.range = new Range(new Position(params.position.line, token.getStartIndex()),
                    new Position(params.position.line, token.getEndIndex()));
            var result = lookupHover(tokenText);
            if (result != null) {
                hover.contents = Arrays.asList(result);
            }
            return Optional.of(hover);
        } else {
            return Optional.empty();
        }
	}

    
    private MarkedString lookupHover(String tokenText) {
        
        return switch (tokenText.toUpperCase()) {
            case "NOP"->new MarkedString("No operation");
            case "R0","R1","R2","R3","R4","R5","R6","R7","R8","R9","R10","R11","R12","R13","R14","R15"->registers();
            case "SFR" -> statusFlagRegister();
            case "BRAMR", "PBR","ROMBR", "CFGR","SCBR","CLSR","SCMR","VCR","RAMBR","CBR"  -> controlRegisters();
            default -> null;
        };
        
        
    }

    private MarkedString controlRegisters() {
        return new MarkedString("""
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
        return new MarkedString("""
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
        return new MarkedString("""
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
        """
        );
    }

    
}
