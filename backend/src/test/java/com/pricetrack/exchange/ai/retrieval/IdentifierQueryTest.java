package com.pricetrack.exchange.ai.retrieval;
import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
class IdentifierQueryTest {
    @Test void exactIdentifiersAndUnseenWording(){
        assertThat(IdentifierQuery.terms("QuoteAlreadyUsed")).containsExactly("QuoteAlreadyUsed");
        assertThat(IdentifierQuery.terms("MinimumOutputNotMet 오류를 설명해 주세요")).containsExactly("MinimumOutputNotMet");
        assertThat(IdentifierQuery.terms("`INVALID_CREDENTIALS`")).containsExactly("INVALID_CREDENTIALS");
    }
    @Test void ordinaryQuestionsAndSqlDoNotBecomeGlobalHybrid(){
        for(String q:new String[]{"REVIEW_REQUIRED는 주문 상태야?","견적은 언제부터 30초 동안 유효해?","다른 사람의 quoteId로 주문할 수 있어?","FuturesEngine Receipt journal DB 선물","'; DROP TABLE ai.chunks; --"})
            assertThat(IdentifierQuery.terms(q)).as(q).isEmpty();
    }
}
