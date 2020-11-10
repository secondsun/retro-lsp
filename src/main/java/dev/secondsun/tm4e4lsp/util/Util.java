package dev.secondsun.tm4e4lsp.util;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.stream.Collectors;

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
}
