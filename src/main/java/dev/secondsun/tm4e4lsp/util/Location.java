package dev.secondsun.tm4e4lsp.util;

import java.net.URI;

public record Location(URI filename, int line, int startIndex, int endIndex) {
    
}
