package dev.secondsun;

import java.util.Arrays;

import dev.secondsun.tm4e.core.grammar.IGrammar;
import dev.secondsun.tm4e.core.grammar.IToken;
import dev.secondsun.tm4e.core.grammar.ITokenizeLineResult;
import dev.secondsun.tm4e.core.registry.Registry;
import dev.secondsun.retrolsp.CC65LanguageServer;
import dev.secondsun.retro.util.Util;



public class FirstTest {
    /**
     * Confirm that tokens from a source file can be parsed
     * 
     * @throws Exception
     */
    //@Test
    public void printTestSgs() throws Exception {
        var registry = new Registry();
        IGrammar grammar = registry.loadGrammarFromPathSync("snes.json",
                CC65LanguageServer.class.getClassLoader().getResourceAsStream("snes.json"));
        
        var sgsProgram = Util.toString(CC65LanguageServer.class.getClassLoader().getResourceAsStream("libSFX.i"));
        
        Arrays.asList(sgsProgram.split("\n")).forEach(line -> {
            ITokenizeLineResult lineTokens = grammar.tokenizeLine(line);
            System.out.println(line);
            for (int i = 0; i < lineTokens.getTokens().length; i++) {
                IToken token = lineTokens.getTokens()[i];
                System.out.println("Token from " + token.getStartIndex() + " to " + token.getEndIndex() + " with scopes "
                        + token.getScopes());
            }
        });

    }
}
