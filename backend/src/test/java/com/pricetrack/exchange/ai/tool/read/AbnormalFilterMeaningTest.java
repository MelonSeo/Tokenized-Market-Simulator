package com.pricetrack.exchange.ai.tool.read;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricetrack.exchange.ai.tool.*;
import com.pricetrack.exchange.blockchain.transaction.*;
import com.pricetrack.exchange.user.UserRole;
import jakarta.persistence.*;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AbnormalFilterMeaningTest {
    @Test void metadataMatchesActualPredicateForBothFilters(){
        for(String filter:List.of("WAITING_LONG","REVIEW_REQUIRED")){
            EntityManager em=mock(EntityManager.class);
            @SuppressWarnings("unchecked") TypedQuery<BlockchainTransaction> q=mock(TypedQuery.class,RETURNS_SELF);
            when(em.createQuery(anyString(),eq(BlockchainTransaction.class))).thenReturn(q);
            when(q.getResultList()).thenReturn(List.of());
            Instant now=Instant.parse("2026-11-01T00:00:00Z");
            var facade=new ToolReadFacade(em,null,new ObjectMapper(),new ToolProperties(true),Clock.fixed(now,ZoneOffset.UTC));
            var result=facade.abnormal(new ToolContext(1L,UserRole.ADMIN,"test","READ_ONLY",null),
                new ToolRegistry.Arguments(null,null,10,filter,300,null));
            boolean waiting=filter.equals("WAITING_LONG");
            assertThat(result.path("waitingSecondsApplied").booleanValue()).isEqualTo(waiting);
            assertThat(result.path("waitingSeconds").intValue()).isEqualTo(300);
            var hql=ArgumentCaptor.forClass(String.class);
            verify(em).createQuery(hql.capture(),eq(BlockchainTransaction.class));
            assertThat(hql.getValue().contains(":cutoff")).isEqualTo(waiting);
            if(waiting)verify(q).setParameter("cutoff",now.minusSeconds(300));
            else {verify(q,never()).setParameter(eq("cutoff"),any());verify(q).setParameter("status",BlockchainTransactionStatus.REVIEW_REQUIRED);}
            verify(q).setMaxResults(11);
            verify(q).setHint("jakarta.persistence.query.timeout",2000);
        }
    }
    @Test void userCannotReadAdminList(){
        EntityManager em=mock(EntityManager.class);
        var facade=new ToolReadFacade(em,null,new ObjectMapper(),new ToolProperties(true),Clock.systemUTC());
        assertThatThrownBy(()->facade.abnormal(new ToolContext(1L,UserRole.USER,"test","READ_ONLY",null),
            new ToolRegistry.Arguments(null,null,10,"REVIEW_REQUIRED",300,null)))
            .isInstanceOf(ToolFailure.class).hasMessage("TOOL_FORBIDDEN");
        verifyNoInteractions(em);
    }
}
