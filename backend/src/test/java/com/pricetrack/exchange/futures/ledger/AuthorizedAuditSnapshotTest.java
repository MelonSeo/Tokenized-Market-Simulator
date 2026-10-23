package com.pricetrack.exchange.futures.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricetrack.exchange.futures.audit.*;
import com.pricetrack.exchange.futures.chain.*;
import com.pricetrack.exchange.futures.ledger.FuturesRecords.Operation;
import java.math.BigInteger;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

/** H2 transactions and local signatures, MOCK receipt/RPC. Not PostgreSQL/Anvil or browser acceptance. */
class AuthorizedAuditSnapshotTest {
    AuthorizedV2RepricingTest fixture;
    ObjectMapper json;
    @BeforeEach void setup()throws Exception{
        fixture=new AuthorizedV2RepricingTest();fixture.setup();
        // The trading-only fixture mapper has no Java Time module; Spring Boot's product mapper does.
        json=fixture.v.f.context.getBean(ObjectMapper.class).copy().findAndRegisterModules();
    }
    @AfterEach void close(){fixture.close();}
    FuturesAuditSnapshot snapshot(){
        var transaction=new TransactionTemplate(fixture.v.f.context.getBean(PlatformTransactionManager.class));
        transaction.setReadOnly(true);transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return transaction.execute(status->FuturesAuditSnapshot.readAuthorized(fixture.v.f.jdbc,json));
    }
    FuturesAuthorizedAuditVerifier.Result verify(FuturesAuditSnapshot s,String operation){
        var o=s.operations().stream().filter(v->v.id().equals(operation)).findFirst().orElseThrow();
        var p=s.positions().stream().filter(v->v.id().equals(o.positionId())).findFirst().orElseThrow();
        var tx=s.transactions().stream().filter(v->v.stored().operationId().equals(operation)).map(FuturesAuditSnapshot.Tx::stored).findFirst().orElse(null);
        return FuturesAuthorizedAuditVerifier.verify(AuthorizedV2IntegrationTest.C,p,o,tx,s.authorizations().get(operation));
    }
    Operation confirmed(boolean reprice)throws Exception{
        var op=fixture.v.accepted();if(reprice)fixture.tickDuringEstimate(false);
        fixture.execution().submitPrepared(op.id());var signed=fixture.v.f.txs.find(op.id()).orElseThrow();
        assertThat(fixture.v.receipt(op,signed).reconcile(op.id())).isEqualTo(FuturesReconciler.Result.CONFIRMED);
        return fixture.v.f.ledger.operation(op.id());
    }
    @Test void revisionZeroSameCutIncludesOriginalConsentAndConfirmedJournal()throws Exception{
        var op=confirmed(false);var s=snapshot();assertThat(verify(s,op.id()).activeRevision()).isZero();
        assertThat(s.authorizations()).containsKey(op.id());assertThat(s.positionAuthorizations()).containsKey(op.positionId());
        assertThat(s.transactions().get(0).stored().authorization()).isEqualTo(s.authorizations().get(op.id()).operationAuthorization());
        assertThat(s.journal()).extracting(FuturesAuditSnapshot.Journal::type).containsExactlyInAnyOrder("RESERVE","CONFIRM");
        assertThat(FuturesAuditAccounting.impact(s,json).checks()).allMatch(c->c.matches());
        assertThat(s.unsettled()).isFalse();
    }
    @Test void revisionOneSameCutVerifiesFinalReportAndZeroDeltaRepriceJournal()throws Exception{
        var op=confirmed(true);var s=snapshot();var e=s.authorizations().get(op.id());
        assertThat(verify(s,op.id()).activeRevision()).isEqualTo(1);assertThat(e.revisions()).hasSize(2);
        assertThat(e.originalReport()).isEqualTo(e.revisions().get(0).report());
        assertThat(s.transactions().get(0).stored().report()).isEqualTo(e.revisions().get(1).report()).isNotEqualTo(e.originalReport());
        var reprices=s.journal().stream().filter(j->j.type().equals("REPRICE")).toList();assertThat(reprices).hasSize(1);
        var j=reprices.get(0);assertThat(List.of(j.wallet(),j.locked(),j.house(),j.debt())).allMatch(BigInteger.ZERO::equals);
        assertThat(FuturesAuditAccounting.impact(s,json).checks()).allMatch(c->c.matches());
    }
    @Test void legacyReadStillRefusesAuthorizedCoverageAndDoesNotMutateLedger(){
        fixture.v.accepted();long before=fixture.count("futures_journal");
        assertThatThrownBy(()->FuturesAuditSnapshot.read(fixture.v.f.jdbc,json)).isInstanceOf(FuturesAuditSnapshot.AuthorizedCoverageUnavailable.class);
        assertThat(fixture.count("futures_journal")).isEqualTo(before);assertThat(fixture.count("futures_transactions")).isZero();
    }
    @Test void storedSnapshotVerifierDoesNotRequeryChangedDatabase()throws Exception{
        var op=confirmed(true);var s=snapshot();verify(s,op.id());
        fixture.v.f.jdbc.update("UPDATE futures_wallet_links SET wallet=? WHERE user_id=1",AuthorizedFuturesStoreTest.OTHER.getAddress());
        fixture.v.f.jdbc.update("UPDATE futures_v2_execution_heads SET active_revision=0 WHERE operation_id=?",op.id());
        // A previously acquired consistent cut remains immutable, proving no live Store/RPC lookup in verification.
        assertThat(verify(s,op.id()).activeRevision()).isEqualTo(1);
        var changed=snapshot();assertThatThrownBy(()->verify(changed,op.id())).hasMessage("AUDIT_AUTHORIZATION_CONFLICT");
    }
    @Test void headPointingToOldReportCannotMatchSignedRevisionOne()throws Exception{
        var op=confirmed(true);fixture.v.f.jdbc.update("UPDATE futures_v2_execution_heads SET active_revision=0 WHERE operation_id=?",op.id());
        var s=snapshot();assertThatThrownBy(()->verify(s,op.id())).hasMessage("AUDIT_AUTHORIZATION_CONFLICT");
        assertThat(FuturesAuditAccounting.impact(s,json).checks()).anyMatch(c->c.name().equals("FUTURES_REPRICE_JOURNAL_COUNT_"+op.id())&&!c.matches());
    }
    @Test void signedReportPollutionIsDetectedAgainstActiveRevision()throws Exception{
        var op=confirmed(true);var s=snapshot();var original=s.authorizations().get(op.id()).originalReport();
        fixture.v.f.jdbc.update("UPDATE futures_operations SET report_json=? WHERE id=?",json.writeValueAsString(original),op.id());
        var changed=snapshot();assertThatThrownBy(()->verify(changed,op.id())).hasMessage("AUDIT_AUTHORIZATION_CONFLICT");
    }
    @Test void walletAndAuthorizationOwnerMismatchIsDetectedFromSameCut()throws Exception{
        var op=confirmed(false);fixture.v.f.jdbc.update("UPDATE futures_wallet_links SET wallet=? WHERE user_id=1",AuthorizedFuturesStoreTest.OTHER.getAddress());
        var s=snapshot();assertThatThrownBy(()->verify(s,op.id())).hasMessage("AUDIT_AUTHORIZATION_CONFLICT");
    }
    @Test void authorizedGuardStillRejectsAnotherActivePosition()throws Exception{
        var op=confirmed(false);fixture.v.f.jdbc.update("UPDATE futures_user_guards SET active_position='not-current' WHERE user_id=1");
        assertThat(FuturesAuditAccounting.impact(snapshot(),json).checks()).anyMatch(c->c.name().equals("FUTURES_GUARD_"+op.positionId())&&!c.matches());
    }
    @Test void authorizedGuardStillRejectsMalformedAdmissionReference()throws Exception{
        var op=confirmed(false);fixture.v.f.jdbc.update("UPDATE futures_user_guards SET account_ref='malformed' WHERE user_id=1");
        assertThat(FuturesAuditAccounting.impact(snapshot(),json).checks()).anyMatch(c->c.name().equals("FUTURES_GUARD_"+op.positionId())&&!c.matches());
    }
    @Test void walletDerivedPositionAccountCannotBeChangedToAnotherOwner()throws Exception{
        var op=confirmed(false);fixture.v.f.jdbc.update("UPDATE futures_positions SET account_ref=? WHERE id=?",com.pricetrack.exchange.futures.authorization.UserIntentCodec.account(AuthorizedFuturesStoreTest.OTHER.getAddress()),op.positionId());
        var s=snapshot();assertThatThrownBy(()->verify(s,op.id())).hasMessage("AUDIT_AUTHORIZATION_CONFLICT");
        assertThat(FuturesAuditAccounting.impact(s,json).checks()).anyMatch(c->c.name().equals("FUTURES_GUARD_"+op.positionId())&&!c.matches());
    }
    @Test void zeroDeltaRepriceEvidenceCannotBeDroppedOrItsJournalGivenValue()throws Exception{
        // Preserve the DB's wallet+house conservation CHECK while violating the zero-delta REPRICE rule.
        var op=confirmed(true);fixture.v.f.jdbc.update("UPDATE futures_journal SET wallet_delta=1,house_delta=-1 WHERE operation_id=? AND entry_type='REPRICE'",op.id());
        var s=snapshot();assertThat(FuturesAuditAccounting.impact(s,json).checks()).anyMatch(c->c.name().startsWith("FUTURES_JOURNAL_VALUES_")&&!c.matches());
        fixture.v.f.jdbc.update("DELETE FROM futures_journal WHERE operation_id=? AND entry_type='REPRICE'",op.id());
        var missing=snapshot();assertThat(FuturesAuditAccounting.impact(missing,json).checks()).anyMatch(c->c.name().equals("FUTURES_REPRICE_JOURNAL_COUNT_"+op.id())&&!c.matches());
    }
}
