package dev.secondsun.tm4e4lsp.util;

import java.io.InputStream;
import java.text.StringCharacterIterator;
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

    public static boolean isIncludeDirective(String line) {
        return line.toUpperCase().startsWith(".INCLUDE");
    }

        /**
     * This should consume replacement while it matches string left of cursor.
     * 
     * For example consider the two strings
     * 
     * stringLeftOfCursor = .InclUdE          "h
     * replacement = .include "himem.i"
     * 
     * the this method should return the string: imem.i having consumed .include "h from replacement 
     * 
     * @param stringLeftOfCursor
     * @param replacement
     * @return
     */
    public static String trimCompletion(String stringLeftOfCursor, String replacement) {
        var charIterator = new StringCharacterIterator(stringLeftOfCursor);
        while (charIterator.current() != StringCharacterIterator.DONE) {
            var character = charIterator.current();
            var replacementChar = replacement.charAt(0);

            while(Character.isWhitespace(character)) {
                character = charIterator.next();
                if (character == StringCharacterIterator.DONE) {
                    break;
                }
            }

            while(Character.isWhitespace(replacementChar)) {
                if (replacement.isEmpty()) {
                    return "";
                }
                replacement = replacement.substring(1);//pop
                replacementChar = replacement.charAt(0);
            }

            if ((character+"").equalsIgnoreCase(replacementChar+"")) {
                replacement = replacement.substring(1);
            } else {
                break;
            }

            
            charIterator.next();
        }
        return replacement;
    }

}
