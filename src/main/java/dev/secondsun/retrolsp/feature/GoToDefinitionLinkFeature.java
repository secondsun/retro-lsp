package dev.secondsun.retrolsp.feature;

import com.google.gson.JsonObject;
import dev.secondsun.lsp.Location;
import dev.secondsun.lsp.Position;
import dev.secondsun.lsp.Range;
import dev.secondsun.lsp.TextDocumentPositionParams;
import dev.secondsun.retro.util.SymbolService;
import dev.secondsun.retro.util.Token;
import dev.secondsun.retro.util.TokenType;
import dev.secondsun.retro.util.vo.TokenizedFile;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Feature providing navigation to symbol definitions for CA65 assembly code. */
public class GoToDefinitionLinkFeature
    implements Feature<TextDocumentPositionParams, List<Location>> {

  private final SymbolService symbolService;

  /**
   * Constructs a new {@code GoToDefinitionLinkFeature} with the specified symbol service.
   *
   * @param symbolService the symbol service used to look up symbol definitions
   */
  public GoToDefinitionLinkFeature(SymbolService symbolService) {
    this.symbolService = symbolService;
  }

  private static int actualStart(Token t) {
    String text = t.text();
    return (text != null) ? t.getEndIndex() - text.length() : t.getStartIndex();
  }

  private static int actualEnd(Token t) {
    return t.getEndIndex();
  }

  @Override
  public Optional<List<Location>> handle(TextDocumentPositionParams params, TokenizedFile list) {
    if (list == null || params.position.line < 0 || params.position.line >= list.textLines()) {
      return Optional.empty();
    }
    var tokens = list.getLine(params.position.line);
    if (tokens == null || tokens.tokens() == null || tokens.tokens().isEmpty()) {
      return Optional.empty();
    }

    var tokenList = tokens.tokens();
    int column = params.position.character;
    URI uri = params.textDocument != null ? params.textDocument.uri : null;
    int line = params.position.line;

    // 1. If cursor is on or adjacent to a namespace delimiter "::", reconstruct the full qualified
    // name
    for (int i = 0; i < tokenList.size(); i++) {
      Token t = tokenList.get(i);
      if (t.getType() == TokenType.TOK_NAMESPACE) {
        if (isCursorOnOrAdjacentToNamespace(tokenList, i, column)) {
          String qualifiedName = reconstructQualifiedSymbol(tokenList, i);
          if (qualifiedName != null && !qualifiedName.isBlank()) {
            var locations = findLocations(qualifiedName.trim(), uri, line);
            if (!locations.isEmpty()) {
              return Optional.of(locations);
            }
          }
        }
      }
    }

    // 2. Check for token at cursor (TOK_IDENT or TOK_LOCAL_IDENT)
    for (int i = 0; i < tokenList.size(); i++) {
      Token t = tokenList.get(i);
      if (t.getType() == TokenType.TOK_IDENT || t.getType() == TokenType.TOK_LOCAL_IDENT) {
        if (t.getStartIndex() <= column && column <= t.getEndIndex()) {
          // If this token is preceded by "::", also attempt qualified lookup
          if (i > 0 && tokenList.get(i - 1).getType() == TokenType.TOK_NAMESPACE) {
            String qualifiedName = reconstructQualifiedSymbol(tokenList, i - 1);
            if (qualifiedName != null && !qualifiedName.isBlank()) {
              var qLocations = findLocations(qualifiedName.trim(), uri, line);
              if (!qLocations.isEmpty()) {
                return Optional.of(qLocations);
              }
            }
          }

          // If this token is followed by "::", attempt individual lookup first, then qualified
          // fallback
          String label = t.text();
          if (label != null && !label.isBlank()) {
            var locations = findLocations(label.trim(), uri, line);
            if (!locations.isEmpty()) {
              return Optional.of(locations);
            }
          }

          if (i + 1 < tokenList.size()
              && tokenList.get(i + 1).getType() == TokenType.TOK_NAMESPACE) {
            String qualifiedName = reconstructQualifiedSymbol(tokenList, i + 1);
            if (qualifiedName != null && !qualifiedName.isBlank()) {
              var qLocations = findLocations(qualifiedName.trim(), uri, line);
              if (!qLocations.isEmpty()) {
                return Optional.of(qLocations);
              }
            }
          }
        }
      }
    }

    return Optional.empty();
  }

  private boolean isCursorOnOrAdjacentToNamespace(List<Token> tokenList, int nsIndex, int column) {
    Token nsToken = tokenList.get(nsIndex);
    // Directly on the namespace token or within 1 character of it
    if (column >= actualStart(nsToken) - 1 && column <= actualEnd(nsToken) + 1) {
      return true;
    }
    // On the contiguous token immediately after "::"
    if (nsIndex + 1 < tokenList.size()) {
      Token next = tokenList.get(nsIndex + 1);
      if (actualStart(next) == actualEnd(nsToken)) {
        if (column >= actualStart(next) && column <= actualEnd(next)) {
          return true;
        }
      }
    }
    // On the contiguous token immediately before "::"
    if (nsIndex > 0) {
      Token prev = tokenList.get(nsIndex - 1);
      if (actualEnd(prev) == actualStart(nsToken)) {
        if (column >= actualStart(prev) && column <= actualEnd(prev)) {
          return true;
        }
      }
    }
    return false;
  }

  private String reconstructQualifiedSymbol(List<Token> tokens, int nsIndex) {
    int start = nsIndex;
    int end = nsIndex;

    // Scan backwards from nsIndex
    while (start > 0) {
      Token curr = tokens.get(start);
      Token prev = tokens.get(start - 1);
      if (curr.getType() == TokenType.TOK_NAMESPACE) {
        if ((prev.getType() == TokenType.TOK_IDENT || prev.getType() == TokenType.TOK_LOCAL_IDENT)
            && actualEnd(prev) == actualStart(curr)) {
          start--;
        } else {
          break;
        }
      } else if (curr.getType() == TokenType.TOK_IDENT
          || curr.getType() == TokenType.TOK_LOCAL_IDENT) {
        if (prev.getType() == TokenType.TOK_NAMESPACE && actualEnd(prev) == actualStart(curr)) {
          start--;
        } else {
          break;
        }
      } else {
        break;
      }
    }

    // Scan forwards from nsIndex
    while (end + 1 < tokens.size()) {
      Token curr = tokens.get(end);
      Token next = tokens.get(end + 1);
      if (curr.getType() == TokenType.TOK_NAMESPACE) {
        if ((next.getType() == TokenType.TOK_IDENT || next.getType() == TokenType.TOK_LOCAL_IDENT)
            && actualStart(next) == actualEnd(curr)) {
          end++;
        } else {
          break;
        }
      } else if (curr.getType() == TokenType.TOK_IDENT
          || curr.getType() == TokenType.TOK_LOCAL_IDENT) {
        if (next.getType() == TokenType.TOK_NAMESPACE && actualStart(next) == actualEnd(curr)) {
          end++;
        } else {
          break;
        }
      } else {
        break;
      }
    }

    StringBuilder sb = new StringBuilder();
    for (int k = start; k <= end; k++) {
      sb.append(tokens.get(k).text());
    }
    return sb.toString();
  }

  private List<Location> findLocations(String name, URI uri, int line) {
    List<dev.secondsun.retro.util.vo.Location> matches = null;
    if (uri != null) {
      matches = symbolService.getLocations(name, uri, line);
      if (matches == null || matches.isEmpty()) {
        var single = symbolService.getLocation(name, uri, line);
        if (single != null) {
          matches = List.of(single);
        }
      }
    } else {
      matches = symbolService.getLocations(name);
      if (matches == null || matches.isEmpty()) {
        var single = symbolService.getLocation(name);
        if (single != null) {
          matches = List.of(single);
        }
      }
    }

    if (matches == null || matches.isEmpty()) {
      return List.of();
    }

    List<Location> result = new ArrayList<>(matches.size());
    for (var loc : matches) {
      result.add(
          new Location(
              loc.filename(),
              new Range(
                  new Position(loc.line(), loc.startIndex()),
                  new Position(loc.line(), loc.endIndex()))));
    }
    return result;
  }

  @Override
  public void initialize(JsonObject initializationData) {
    var definitionOptions = new JsonObject();
    definitionOptions.addProperty("workDoneProgress", false);
    initializationData.add("definitionProvider", definitionOptions);
  }
}
