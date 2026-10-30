package com.pricetrack.exchange.futures.chain;
import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.pricetrack.exchange.ai.store.KnowledgeHit;
import java.util.*;
import org.junit.jupiter.api.Test;
class LatestAiNovelHarnessTest {
    final ObjectMapper json=new ObjectMapper();
    @Test void controlMutationUsesAProxyMethodRatherThanAFieldOnTheProxy(){
        var target=new LatestAiConfiguration.NovelReads(null,null,json,null);
        var factory=new org.springframework.aop.framework.ProxyFactory(target);factory.setProxyTargetClass(true);
        var proxy=(LatestAiConfiguration.NovelReads)factory.getProxy();
        proxy.badQuoteLink=true;
        assertThat(target.badQuoteLinkActive()).isFalse();
        proxy.setBadQuoteLink(true);assertThat(target.badQuoteLinkActive()).isTrue();assertThat(proxy.badQuoteLinkActive()).isTrue();
        proxy.setBadQuoteLink(false);assertThat(target.badQuoteLinkActive()).isFalse();
    }
    @Test void automaticSkillKeepsActualDomainsEvenWhenFrozenCaseHasNoExplicitSkill(){
        var actual=Set.of("market","trading");
        assertThat(LatestAiNovelAcceptance.effectiveDomains(Map.of("actualRetrievalDomains",actual),null)).isEqualTo(actual);
        assertThat(LatestAiNovelAcceptance.effectiveDomains(Map.of("actualRetrievalDomains",actual),Set.of("architecture","settlement")))
            .isEqualTo(actual).doesNotContain("architecture","settlement","operations");
        assertThat(LatestAiNovelAcceptance.effectiveDomains(Map.of(),Set.of("support"))).containsExactly("support");
        assertThat(LatestAiNovelAcceptance.effectiveDomains(Map.of(),null)).isNull();
    }
    @Test void stoppedOrExceptionRowsRemainAnnotatableWithoutInventingAnAnswer(){
        var stopped=Map.<String,Object>of("questionId","A1","status","NOT_RUN_BUDGET_STOP");
        var row=LatestAiNovelAcceptance.mutableRow(stopped);row.put("category","A");
        assertThat(row).containsEntry("status","NOT_RUN_BUDGET_STOP").doesNotContainKey("answer");
        assertThat(stopped).doesNotContainKey("category");
    }
    @Test void decompositionUsesOnlyQuestionTextNotExpectedPolicyAtoms(){
        assertThat(LatestAiNovelAcceptance.split("관측 문장입니다. 첫 질문인가요? 둘째 질문인가요?"))
            .containsExactly("관측 문장입니다.","첫 질문인가요? 둘째 질문인가요?");
        assertThat(LatestAiNovelAcceptance.split("한 문장 질문인가요? verbatim" )).isNotEmpty();
    }
    @Test void oneRelevantChunkDoesNotBecomeFullMultihopSupport()throws Exception {
        var d=json.readTree("{\"atoms\":{\"a\":{\"path\":\"a.md\",\"needle\":\"one\"},\"b\":{\"path\":\"b.md\",\"needle\":\"two\"}}}");
        var c=json.readTree("{\"atoms\":[\"a\",\"b\"]}");
        var hits=List.of(new KnowledgeHit("x","z.md","t","h","1","USER","other",.9),new KnowledgeHit("a","a.md","t","h","1","USER","one",.8));
        var m=LatestAiNovelAcceptance.metrics(json,d,c,hits);
        assertThat(m.path("hitAt5").asInt()).isEqualTo(1);assertThat(m.path("mrrAt5").asDouble()).isEqualTo(.5);
        assertThat(m.path("supportRecallAt5").asDouble()).isEqualTo(.5);assertThat(m.path("allSupportAt5").asBoolean()).isFalse();
        assertThat(m.path("nonMatchingCandidateCount").asInt()).isEqualTo(1);
    }
}
