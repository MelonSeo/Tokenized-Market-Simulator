package com.pricetrack.exchange.ai.evaluation;

import com.pricetrack.exchange.ai.agent.AgentResponse;

/** Separate transport execution, model reachability, and human-reviewed correctness. */
final class EvaluationVerdict {
    record Result(String request,String model,String correctness){}
    static Result classify(AgentResponse response,boolean attempted,boolean reviewedCorrect) {
        if(!attempted)return new Result("NOT_RUN","NOT_RUN","NOT_RUN");
        if(response==null)return new Result("BLOCKED","NOT_RUN","NOT_RUN");
        String model=response.metrics().modelCalls()>0?"CALLED":"NOT_RUN";
        if(response.httpStatus()>=400 || response.status().equals("ERROR"))return new Result("FAIL",model,"BLOCKED");
        return new Result("COMPLETED",model,reviewedCorrect?"PASS":"REVIEW_REQUIRED");
    }
}
