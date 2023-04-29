package dev.secondsun.tm4e4lsp.util;

import java.net.URI;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import dev.secondsun.tm4e.core.grammar.IGrammar;
import dev.secondsun.tm4e.core.registry.Registry;
import dev.secondsun.tm4e4lsp.CC65LanguageServer;

public class SymbolService {

    private static final String VARIABLE_DEFINITION = "variable.other.definition";

    public Map<String, Location> definitions = new HashMap<>();

    final Registry registry;
    final IGrammar grammar;

    public SymbolService() {
        registry = new Registry();
        try {
            grammar = registry.loadGrammarFromPathSync("snes.json",
                    CC65LanguageServer.class.getClassLoader().getResourceAsStream("snes.json"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public SymbolService(Registry registry, IGrammar grammar) {
        this.registry = registry;
        this.grammar = grammar;
    }

    public void addDefinition(String name, Location location) {
        definitions.put(name, location);
    }

    public Location getLocation(String name) {
        return definitions.get(name);
    }

    public void extractDefinitions(URI fileName, List<String> lines) {

        IntStream.range(0, lines.size()).forEach((idx) -> {

            String line = lines.get(idx);
            var tokenized = grammar.tokenizeLine(line);
            // only one definition per line so find first is ok
            var foundLabelDef = Arrays.stream(tokenized.getTokens())
                    .filter(token -> token.getScopes().contains(VARIABLE_DEFINITION)).findFirst();
            
            
            foundLabelDef.ifPresent(
                token ->{
                    var stringToken = line.substring(token.getStartIndex(), token.getEndIndex());
                    if (stringToken.endsWith(":")) {
                        // remove the colon
                        addDefinition(stringToken.replace(":", ""),
                                new Location(fileName, idx, token.getStartIndex(), token.getEndIndex()));
                        } else if (stringToken.endsWith("=")) {
                            addDefinition(stringToken.split("=")[0].trim(),
                                new Location(fileName, idx, token.getStartIndex(), token.getEndIndex()));
                        }        
                }
            );

            // find structure
            {
                var split = line.split("(?i).*\\.struct");
                if (split.length > 1) {

                    var namePlusRight = split[1].trim();
                    var tok = grammar.tokenizeLine(namePlusRight).getTokens()[0];
                    var def = namePlusRight.subSequence(tok.getStartIndex(), tok.getEndIndex()).toString();
                    addDefinition(def,
                            new Location(fileName, idx, 0, line.length()));
                }
            }// find proc
            {
                var split = line.split("(?i).*\\.proc");
                if (split.length > 1) {

                    var namePlusRight = split[1].trim();
                    var tok = grammar.tokenizeLine(namePlusRight).getTokens()[0];
                    var def = namePlusRight.subSequence(tok.getStartIndex(), tok.getEndIndex()).toString();
                    addDefinition(def,
                            new Location(fileName, idx, 0, line.length()));
                }
            }
            {
                var split = line.split("(?i).*\\.enum");
                if (split.length > 1) {

                    var namePlusRight = split[1].trim();
                    var tok = grammar.tokenizeLine(namePlusRight).getTokens()[0];
                    var def = namePlusRight.subSequence(tok.getStartIndex(), tok.getEndIndex()).toString();
                    addDefinition(def,
                            new Location(fileName, idx, 0, line.length()));
                }
            }
            // find macro
            {
                var macro = line.split("(?i).*\\.macro");
                if (macro.length > 1) {

                    var namePlusRight = macro[1].trim();
                    var tok = grammar.tokenizeLine(namePlusRight).getTokens()[0];
                    var def = namePlusRight.subSequence(tok.getStartIndex(), tok.getEndIndex()).toString();
                    addDefinition(def,
                            new Location(fileName, idx, 0, line.length()));
                }
            }

// find functions. Fuunctions are a summersism
{
    var macro = line.split("(?i).*function");
    if (macro.length > 1) {

        var namePlusRight = macro[1].trim();
        var tok = grammar.tokenizeLine(namePlusRight).getTokens()[0];
        var def = namePlusRight.subSequence(tok.getStartIndex(), tok.getEndIndex()).toString();
        addDefinition(def,
                new Location(fileName, idx, 0, line.length()));
    }
}

        });

    }

}
