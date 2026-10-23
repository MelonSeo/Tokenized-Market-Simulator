package com.pricetrack.exchange.futures.audit;

import com.pricetrack.exchange.futures.authorization.UserIntentCodec.Authorization;
import com.pricetrack.exchange.futures.chain.FuturesReport;
import java.math.BigInteger;
import java.util.List;

/** Internal evidence supplied by ONE consistent DB cut. Never expose through API, AI or history DTOs. */
public record FuturesAuthorizedAuditEvidence(String operationId,String positionId,long userId,
        Authorization positionAuthorization,Authorization operationAuthorization,String linkedWallet,
        String v2PositionController,String v2OperationController,FuturesReport originalReport,
        String originalPriceSignature,Integer activeRevision,List<Revision> revisions) {
    public FuturesAuthorizedAuditEvidence { revisions=List.copyOf(revisions); }
    public record Revision(int revision,FuturesReport report,String priceSignature,BigInteger price,
            long observedAt,String provider,Long generation) {
        @Override public String toString(){return "FuturesAuditRevision[internal evidence redacted]";}
    }
    @Override public String toString(){return "FuturesAuthorizedAuditEvidence[internal evidence redacted]";}
}
