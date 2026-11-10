package com.pricetrack.exchange.ai.agent;
public final class AgentFailure extends RuntimeException {
    public enum EvidenceReason { RESPONSE_CONTRACT, STRUCTURE, FEE_MEANING, UNKNOWN_CITATION, POLICY_CITATION_MISSING,
        TOOL_EVIDENCE_MISSING, FACT_REFERENCE, FACT_VALUE, KNOWLEDGE_FACTS, EXECUTION_CLAIM }
    private final String code;
    private final int http;
    private final EvidenceReason evidenceReason;
    public AgentFailure(String code,int http){this(code,http,null);}
    AgentFailure(String code,int http,EvidenceReason reason){super(code);this.code=code;this.http=http;this.evidenceReason=reason;}
    public String code(){return code;}
    public int http(){return http;}
    public EvidenceReason evidenceReason(){return evidenceReason;}
}
