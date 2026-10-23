package com.pricetrack.exchange.custody.direct;

import com.pricetrack.exchange.custody.authorization.CReportCandidateBuilder;
import com.pricetrack.exchange.custody.runtime.CCustodyIdentity;

/** Fixed isolated Oracle boundary; no user key, relayer key or arbitrary transaction dispatch. */
@FunctionalInterface
public interface CDirectOraclePort {
    String sign(CCustodyIdentity identity,CReportCandidateBuilder.Candidate candidate);
}
