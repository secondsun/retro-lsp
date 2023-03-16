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
        
        IntStream.range(0, lines.size()).forEach( (idx) -> {
            
            String line = lines.get(idx);
            var tokenized = grammar.tokenizeLine(line);
            //only one definition per line so find first is ok
            var found = Arrays.stream(tokenized.getTokens()).filter(token -> token.getScopes().contains(VARIABLE_DEFINITION)).findFirst();
            // -1 to remove the colon
            found.ifPresent(it -> addDefinition(line.substring(it.getStartIndex(), it.getEndIndex()-1), new Location(fileName, idx, it.getStartIndex(), it.getEndIndex())));
        });


    }


}
