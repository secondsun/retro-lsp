package dev.secondsun;

import java.util.Arrays;

import org.apache.commons.io.IOUtils;
import org.eclipse.tm4e.core.grammar.IGrammar;
import org.eclipse.tm4e.core.grammar.IToken;
import org.eclipse.tm4e.core.grammar.ITokenizeLineResult;
import org.eclipse.tm4e.core.registry.Registry;
import org.junit.Test;

public class FirstTest {
    /**
     * Confirm that tokens from a source file can be parsed
     * 
     * @throws Exception
     */
    @Test
    public void printTestSgs() throws Exception {
        var registry = new Registry();
        IGrammar grammar = registry.loadGrammarFromPathSync("snes.json",
                CC65LanguageServer.class.getClassLoader().getResourceAsStream("snes.json"));
        
        var sgsProgram = IOUtils.toString(CC65LanguageServer.class.getClassLoader().getResourceAsStream("test.sgs"));
        
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
