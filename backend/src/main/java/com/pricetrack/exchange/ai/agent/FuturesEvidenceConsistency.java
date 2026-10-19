package com.pricetrack.exchange.ai.agent;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** Compare stable financial/version fields, not independently sampled live mark prices. Missing data is not equality. */
public final class FuturesEvidenceConsistency {
    private FuturesEvidenceConsistency() {}
    public static boolean samePosition(JsonNode position,JsonNode evidence) {
        var snapshot=evidence.path("positionSnapshot");
        for(String field:List.of("id","status","riskStatus","version","callEpoch","deadline","pendingOperation","collateral","realized","badDebt"))
            if(!position.has(field)||!snapshot.has(field)||!position.get(field).equals(snapshot.get(field)))return false;
        return true;
    }
}
