package dev.secondsun.tm4e4lsp.util;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Scanner;
import java.util.stream.Collectors;

import dev.secondsun.tm4e.core.grammar.IToken;
import dev.secondsun.tm4e.core.grammar.ITokenizeLineResult;

public final class Util {
    public static List<String> readLines(InputStream stream){
        Scanner scanner = new Scanner(stream);
        var list = new ArrayList<String>();

        while(scanner.hasNextLine()) {
            list.add(scanner.nextLine());
        }
        return list;
        
    }

	public static String toString(InputStream resourceAsStream) {
		return readLines(resourceAsStream).stream().collect(Collectors.joining("\n"));
    }
    


    public static String getTokenText(String line, IToken token) {
        var start = token.getStartIndex();
        var end = token.getEndIndex();
        return line.substring(start, end);
    }

    public static Optional<IToken> getTokenAt(ITokenizeLineResult lineTokens, int position) {
        var tokenList = Arrays.asList(lineTokens.getTokens());
        return tokenList.stream().filter(token -> token.getStartIndex() < position && token.getEndIndex() >= position)
                .findFirst();

    }


}
