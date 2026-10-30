package com.pricetrack.exchange.ai.retrieval;
import java.util.*;
import java.util.regex.Pattern;
/** Selective exact identifiers, not unconditional Hybrid over ordinary Korean questions. */
public final class IdentifierQuery {
    private IdentifierQuery(){}
    private static final Pattern TOKEN=Pattern.compile("(?<![A-Za-z0-9_])[A-Za-z][A-Za-z0-9_]{2,79}(?![A-Za-z0-9_])");
    private static final Pattern CAMEL=Pattern.compile("[A-Z][a-z]+(?:[A-Z][A-Za-z0-9]*)+");
    public static List<String> terms(String question){
        if(question==null)return List.of();String bare=question.strip().replace("`","");
        if(bare.matches("[A-Za-z][A-Za-z0-9_]{2,79}")&&(bare.contains("_")||CAMEL.matcher(bare).matches()))return List.of(bare);
        var tokens=TOKEN.matcher(question).results().map(m->m.group()).distinct().toList();
        if(tokens.size()>2)return List.of();
        return tokens.stream().filter(t->CAMEL.matcher(t).matches()).limit(2).toList();
    }
}
