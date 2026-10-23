package com.pricetrack.exchange.ai.agent;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.pricetrack.exchange.ai.*;
import com.pricetrack.exchange.ai.knowledge.*;
import com.pricetrack.exchange.ai.provider.*;
import com.pricetrack.exchange.ai.retrieval.AuthorizedKnowledgeRetrieval;
import com.pricetrack.exchange.ai.retrieval.AuthorizedKnowledgeRetrieval.*;
import com.pricetrack.exchange.ai.store.*;
import com.pricetrack.exchange.auth.AuthenticatedUser;
import com.pricetrack.exchange.user.UserRole;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;

class RetrievalStageDiagnosticTest {
    final KnowledgeLoader loader=mock(KnowledgeLoader.class);
    final KnowledgeStore store=mock(KnowledgeStore.class);
    final EmbeddingProvider embedding=mock(EmbeddingProvider.class);
    final List<Diagnostic> trace=new ArrayList<>();
    final AuthenticatedUser user=new AuthenticatedUser(1L,null,UserRole.USER);
    final AiProperties p=new AiProperties(true,"unused","unused","unused",".","manifest","","guarded:stdio/",
        "text-embedding-3-small","gpt-5.6-terra",1200,60,5,.25,20);
    final AuthorizedKnowledgeRetrieval retrieval=new AuthorizedKnowledgeRetrieval(p,loader,store,embedding).diagnose(trace::add);
    @BeforeEach void setup() {
        when(loader.load()).thenReturn(new KnowledgeCorpus("index",List.of(),List.of()));
        when(store.active("index")).thenReturn(true);
        when(embedding.embed(any(),any())).thenAnswer(c->{float[] v=new float[1536];v[0]=1;return List.of(v);});
        when(store.searchForRole(any(),any(),anyInt(),anyDouble(),any(),any())).thenReturn(List.of());
    }
    @Test void successfulEmbeddingCanExhaustSharedBudgetBeforeSqlWithoutProvingHistoricalQ5Cause() {
        when(embedding.embed(any(),any())).thenAnswer(c->{Thread.sleep(550);float[] v=new float[1536];v[0]=1;return List.of(v);});
        assertThatThrownBy(()->retrieval.search(user,"SECRET_QUESTION",Duration.ofMillis(500))).hasMessage("AI_QUERY_TIMEOUT");
        assertThat(trace).anyMatch(d->d.stage()==Stage.EMBEDDING&&d.success());
        assertThat(trace.getLast().stage()).isEqualTo(Stage.CANDIDATE_BUDGET);
        assertThat(trace.getLast().code()).isEqualTo("AI_QUERY_TIMEOUT");
        assertThat(trace.getLast().remainingMillis()).isZero();
        verify(store,never()).searchForRole(any(),any(),anyInt(),anyDouble(),any(),any());
        assertThat(trace.toString()).doesNotContain("SECRET_QUESTION","1536","unused");
    }
    @Test void databaseFailureAfterSuccessfulEmbeddingIsDifferentFromDeadlineExhaustion() {
        when(store.searchForRole(any(),any(),anyInt(),anyDouble(),any(),any())).thenThrow(new AiFailure("AI_DATABASE_UNAVAILABLE"));
        assertThatThrownBy(()->retrieval.search(user,"q",Duration.ofSeconds(5))).hasMessage("AI_DATABASE_UNAVAILABLE");
        assertThat(trace.getLast().stage()).isEqualTo(Stage.CANDIDATE_SEARCH);
        assertThat(trace.getLast().code()).isEqualTo("AI_DATABASE_UNAVAILABLE");
    }
    @Test void diagnosticLatencyNeverExtendsTheBudgetPassedToSql() {
        retrieval.diagnose(d->{trace.add(d);if(d.stage()==Stage.CANDIDATE_BUDGET)try{Thread.sleep(550);}catch(InterruptedException e){Thread.currentThread().interrupt();}});
        assertThatThrownBy(()->retrieval.search(user,"q",Duration.ofMillis(500))).hasMessage("AI_QUERY_TIMEOUT");
        assertThat(trace.getLast().stage()).isEqualTo(Stage.CANDIDATE_SEARCH);
        assertThat(trace.getLast().code()).isEqualTo("AI_QUERY_TIMEOUT");
        verify(store,never()).searchForRole(any(),any(),anyInt(),anyDouble(),any(),any());
    }
    @Test void approvalFailureIsNotReportedAsSqlOrEmbeddingFailure() {
        when(loader.load()).thenReturn(new KnowledgeCorpus("index",List.of(),List.of()),new KnowledgeCorpus("changed",List.of(),List.of()));
        assertThatThrownBy(()->retrieval.search(user,"q",Duration.ofSeconds(5))).hasMessage("AI_APPROVAL_MISMATCH");
        assertThat(trace.getLast().stage()).isEqualTo(Stage.APPROVAL_VERIFY);
    }
    @Test void emptyEvidenceIsSuccessfulSearchAndUnknownExceptionIsRedacted() {
        assertThat(retrieval.search(user,"q",Duration.ofSeconds(5)).hits()).isEmpty();
        assertThat(trace.getLast().stage()).isEqualTo(Stage.DEADLINE_CHECK);
        assertThat(trace).allMatch(Diagnostic::success);
        when(loader.load()).thenThrow(new IllegalStateException("SECRET_URL password=SECRET"));trace.clear();
        assertThatThrownBy(()->retrieval.search(user,"q",Duration.ofSeconds(5))).isInstanceOf(IllegalStateException.class);
        assertThat(trace.getLast().code()).isEqualTo("OTHER_FAILURE");
        assertThat(trace.toString()).doesNotContain("SECRET");
    }
    @Test void brokenDiagnosticSinkNeverReplacesOriginalSuccessOrFailure() {
        retrieval.diagnose(d->{throw new IllegalStateException("sink");});
        assertThat(retrieval.search(user,"q",Duration.ofSeconds(5)).hits()).isEmpty();
        when(store.active("index")).thenReturn(false);
        assertThatThrownBy(()->retrieval.search(user,"q",Duration.ofSeconds(5))).hasMessage("AI_INDEX_NOT_READY");
    }
}
