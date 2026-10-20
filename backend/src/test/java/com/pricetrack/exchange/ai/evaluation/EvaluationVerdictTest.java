package com.pricetrack.exchange.ai.evaluation;

import static org.assertj.core.api.Assertions.*;
import com.pricetrack.exchange.ai.agent.AgentResponse;
import java.util.List;
import org.junit.jupiter.api.Test;

class EvaluationVerdictTest {
    AgentResponse response(String status,int calls){return new AgentResponse("run",status,"STATE","fact",null,List.of(),List.of(),List.of(),List.of(),List.of(),new AgentResponse.Metrics(1,0,calls,0,0,1),null,200);}
    @Test void unattemptedAndBlockedNeverBecomePass(){
        assertThat(EvaluationVerdict.classify(null,false,true)).isEqualTo(new EvaluationVerdict.Result("NOT_RUN","NOT_RUN","NOT_RUN"));
        assertThat(EvaluationVerdict.classify(null,true,true).correctness()).isEqualTo("NOT_RUN");
    }
    @Test void attemptedFailureRetainsCalledVersusNotRun(){
        assertThat(EvaluationVerdict.classify(AgentResponse.failure("run","INVALID_TOOL_ARGUMENTS",503),true,true))
            .isEqualTo(new EvaluationVerdict.Result("FAIL","NOT_RUN","BLOCKED"));
    }
    @Test void answeredIsNotAutomaticallyJudgedCorrect(){
        assertThat(EvaluationVerdict.classify(response("ANSWERED",2),true,false).correctness()).isEqualTo("REVIEW_REQUIRED");
        assertThat(EvaluationVerdict.classify(response("ANSWERED",2),true,true).correctness()).isEqualTo("PASS");
    }
    @Test void sixteenQuestionAccountingDoesNotInventModelAccuracy(){
        var results=new java.util.ArrayList<EvaluationVerdict.Result>();
        results.add(EvaluationVerdict.classify(response("ANSWERED",2),true,true));
        for(int i=2;i<=16;i++)results.add(EvaluationVerdict.classify(AgentResponse.failure("run","INVALID_TOOL_ARGUMENTS",503),true,false));
        assertThat(results).hasSize(16);assertThat(results.stream().filter(r->r.correctness().equals("PASS")).count()).isEqualTo(1);
        assertThat(results.stream().filter(r->r.correctness().equals("BLOCKED")).count()).isEqualTo(15);
    }
}
