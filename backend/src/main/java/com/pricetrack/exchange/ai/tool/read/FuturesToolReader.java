package com.pricetrack.exchange.ai.tool.read;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.pricetrack.exchange.ai.tool.*;
import com.pricetrack.exchange.futures.audit.*;
import com.pricetrack.exchange.futures.chain.*;
import com.pricetrack.exchange.futures.ledger.FuturesRecords.*;
import com.pricetrack.exchange.futures.math.FuturesMath;
import com.pricetrack.exchange.futures.read.FuturesReadService;
import java.math.BigInteger;
import java.time.Instant;
import java.util.*;

/** Read-only adapter. Signing evidence stays internal; no audit history INSERT, reconcile or broadcast. */
public final class FuturesToolReader {
    private final FuturesAuditStore store; private final FuturesReadService reads;
    private final FuturesRpcGateway rpc; private final FuturesChainIdentity identity; private final ObjectMapper json;private final int confirmations;
    public FuturesToolReader(FuturesAuditStore store,FuturesReadService reads,FuturesRpcGateway rpc,FuturesChainIdentity identity,ObjectMapper json) {
        this(store,reads,rpc,identity,json,1);
    }
    public FuturesToolReader(FuturesAuditStore store,FuturesReadService reads,FuturesRpcGateway rpc,FuturesChainIdentity identity,ObjectMapper json,int confirmations) {
        this.store=store;this.reads=reads;this.rpc=rpc;this.identity=identity;this.json=json;this.confirmations=Math.max(1,confirmations);
    }
    private Position owned(ToolContext ctx,FuturesAuditSnapshot snapshot,String id) {
        ctx.requireAuthenticated();
        return snapshot.positions().stream().filter(p->p.id().equals(id)&&(ctx.admin()||p.userId()==ctx.userId()))
            .findFirst().orElseThrow(()->new ToolFailure("RESOURCE_NOT_FOUND"));
    }
    public JsonNode position(ToolContext ctx,String id) {
        var p=owned(ctx,store.cut().futures(),id);
        var result=json.createObjectNode();result.set("position",json.valueToTree(reads.position(p.userId(),id)));
        result.put("observedAt",Instant.now().toString()).put("onchainEscrow",false).put("payoutGuaranteed",false);
        return result;
    }
    public JsonNode evidence(ToolContext ctx,String id) {
        var cut=store.cut();var s=cut.futures();var p=owned(ctx,s,id);
        var result=json.createObjectNode().put("positionId",id).put("positionStatus",p.status()).put("pending",p.pendingOperation()!=null)
            .put("scope","SELECTED_CALL_AND_LIQUIDATION_ONLY").put("onchainEscrow",false).put("payoutGuaranteed",false)
            .put("observedAt",Instant.now().toString()).put("verification","INCONCLUSIVE").put("reason","NO_CONFIRMED_LIQUIDATION_EVIDENCE");
        var items=result.putArray("operations");
        result.set("positionSnapshot",json.createObjectNode().put("id",p.id()).put("status",p.status()).put("riskStatus",p.riskStatus())
            .put("version",p.version()).put("callEpoch",p.callEpoch()).put("deadline",p.deadline()).put("pendingOperation",p.pendingOperation())
            .put("collateral",FuturesReadService.amount(p.collateral())).put("realized",FuturesReadService.amount(p.realized())).put("badDebt",FuturesReadService.amount(p.badDebt())));
        if(!FuturesAuditAccounting.settlementMatches(p,s.operations()))return result.put("reason","DB_SETTLEMENT_PROJECTION_UNVERIFIED");
        // Two operations at most, not an unbounded history/whole-ledger audit. Full audit remains ADMIN-only.
        var selected=s.operations().stream().filter(o->o.positionId().equals(id)&&(o.action()==Action.START_CALL||o.action()==Action.LIQUIDATE))
            .sorted(Comparator.comparingLong(Operation::expectedVersion).reversed()).limit(2).toList();
        boolean verified=!selected.isEmpty()&&p.pendingOperation()==null&&!p.status().equals("REVIEW_REQUIRED");
        try {
            rpc.verifyDeployment(identity);
            var head=rpc.height();var headHash=rpc.block(head).getHash();
            if(verified&&!FuturesAuditVerifier.current(p,rpc.position(id,head)))throw new IllegalStateException();
            for(var op:selected) {
                if(Thread.currentThread().isInterrupted())throw new IllegalStateException();
                var item=items.addObject().put("operationId",op.id()).put("action",op.action().name()).put("databaseState",op.state())
                    .put("price",FuturesReadService.price(op.price())).put("collateral",FuturesReadService.amount(op.collateralBefore()))
                    .put("observedAtEpoch",op.observedAt()).put("verification","INCONCLUSIVE");
                var m=FuturesMath.mark(p.side(),p.quantity(),p.entryPrice(),op.collateralAfter(),op.price());
                item.put("unrealized",FuturesReadService.amount(m.unrealized())).put("equity",FuturesReadService.amount(m.equity()))
                    .put("maintenance",FuturesReadService.amount(m.maintenance())).put("atRisk",m.atRisk())
                    .put("realized",FuturesReadService.amount(op.expectedRealized())).put("badDebt",FuturesReadService.amount(op.expectedBadDebt()));
                if(op.userId()!=p.userId())throw new IllegalStateException();
                if(!op.state().equals("CONFIRMED")){verified=false;continue;}
                var evidence=s.transactions().stream().filter(t->t.stored().operationId().equals(op.id())).findFirst().orElseThrow();
                var t=evidence.stored();
                if(!t.status().equals("CONFIRMED")||!t.chainId().equals(identity.chainId())||!t.engine().equalsIgnoreCase(identity.engine())||!t.sender().equalsIgnoreCase(identity.executor())
                    ||!t.report().equals(FuturesReport.from(op,p,identity.executor()))||!FuturesReportCodec.signedBy(t.report(),identity.chainId(),identity.engine(),t.signature(),identity.priceSigner()))throw new IllegalStateException();
                FuturesTransactionStore.validateRaw(identity,t.report(),t.signature(),t.raw(),t.hash(),t.nonce());
                var receipt=rpc.receipt(t.hash()).orElseThrow();var tx=rpc.transaction(t.hash()).orElseThrow();var block=rpc.block(receipt.getBlockNumber());
                if(evidence.block()==null||evidence.block()!=block.getNumber().longValueExact()||!Objects.equals(evidence.blockHash(),block.getHash())
                    ||!block.getHash().equalsIgnoreCase(receipt.getBlockHash())||head.subtract(block.getNumber()).add(BigInteger.ONE).compareTo(BigInteger.valueOf(confirmations))<0||block.getNumber().signum()==0)throw new IllegalStateException();
                var before=FuturesAuditVerifier.before(p,op,rpc.position(id,block.getNumber().subtract(BigInteger.ONE)));
                var event=FuturesReceiptVerifier.success(t,before,receipt,tx,block,rpc.position(id,block.getNumber()));
                if(!op.expectedRealized().equals(event.realized())||!op.expectedBadDebt().equals(event.badDebt()))throw new IllegalStateException();
                var entries=s.journal().stream().filter(j->op.id().equals(j.operationId())&&j.type().equals("CONFIRM")).toList();
                if(entries.size()!=1)throw new IllegalStateException();var journal=entries.getFirst();
                var wallet=op.action()==Action.LIQUIDATE?event.realized():BigInteger.ZERO;
                var lock=op.action()==Action.LIQUIDATE?op.collateralBefore().negate():BigInteger.ZERO;
                if(!journal.positionId().equals(id)||journal.userId()!=p.userId()||!journal.wallet().equals(wallet)||!journal.house().equals(wallet.negate())
                    ||!journal.locked().equals(lock)||!journal.debt().equals(event.badDebt()))throw new IllegalStateException();
                item.put("verification","VERIFIED").put("txHash",t.hash()).put("blockNumber",event.blockNumber()).put("blockHash",event.blockHash())
                    .put("chainTime",event.blockTimestamp()).put("graceDeadline",event.deadline()).put("journalId",journal.id()).put("requiredConfirmations",confirmations)
                    .put("walletDelta",FuturesReadService.amount(journal.wallet())).put("lockedDelta",FuturesReadService.amount(journal.locked()));
            }
            if(!store.fingerprint(cut).equals(store.fingerprint(store.cut()))||!headHash.equalsIgnoreCase(rpc.block(head).getHash())) {
                verified=false;result.put("reason","CUT_CHANGED");items.forEach(i->((ObjectNode)i).put("verification","INCONCLUSIVE"));
            } else if(verified)result.put("verification","VERIFIED").put("reason","CANONICAL_SELECTED_EVENTS_AND_JOURNAL");
        } catch(Exception unavailable) {
            verified=false;result.put("reason","RPC_HISTORICAL_OR_LEDGER_EVIDENCE_UNVERIFIED");
            items.forEach(i->((ObjectNode)i).put("verification","INCONCLUSIVE"));
        }
        if(!verified)result.put("verification","INCONCLUSIVE");
        return result;
    }
}
