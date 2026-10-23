package com.pricetrack.exchange.futures.chain;

import java.math.BigInteger;
import java.io.IOException;
import com.pricetrack.exchange.futures.ledger.FuturesLedger.Failure;
import org.web3j.crypto.*;
import org.web3j.utils.Numeric;

/** One in-flight transaction per dedicated executor. Process-local serialization, NOT distributed nonce allocation. */
public final class FuturesTransactionSender {
    public interface Transport {
        /** Validates chainId and immutable Engine executor/signer/symbol/policy/code, not merely RPC liveness. */
        void verifyDeployment(FuturesChainIdentity expected) throws IOException;
        BigInteger pendingNonce(String sender) throws IOException;
        BigInteger gasPrice() throws IOException;
        BigInteger estimate(String sender,BigInteger nonce,BigInteger gasPrice,String engine,String data) throws IOException;
        String broadcast(String raw) throws IOException;
        boolean known(String hash) throws IOException;
    }
    public record Submission(String operationId,String txHash,String status) {}
    private final FuturesChainIdentity chain;
    private final Credentials executor;
    private final FuturesTransactionStore store;
    private final Transport rpc;
    public FuturesTransactionSender(FuturesChainIdentity chain,Credentials executor,FuturesTransactionStore store,Transport rpc) {
        if(!chain.executor().equalsIgnoreCase(executor.getAddress())) throw new IllegalArgumentException("EXECUTOR_KEY_MISMATCH");
        this.chain=chain; this.executor=executor; this.store=store; this.rpc=rpc;
    }

    /** Caller rejects only an UNSIGNED preparation on failure. Once a signed row exists, retain locks and recover it. */
    public synchronized Submission submit(String id,FuturesReport report,String signature) {
        return submit(id,report,signature,()->{});
    }

    /** Recheck the fixed price/generation AFTER RPC estimate, before any new raw is signed or committed. */
    public synchronized Submission submit(String id,FuturesReport report,String signature,Runnable beforeSigning) {
        return FuturesDiagnostics.work(FuturesDiagnostics.Stage.SENDER,()->submitInternal(id,report,signature,beforeSigning));
    }
    private Submission submitInternal(String id,FuturesReport report,String signature,Runnable beforeSigning) {
        var previous=store.find(id);
        if(previous.isPresent()) return recover(id);
        if(!FuturesReportCodec.signedBy(report,chain.chainId(),chain.priceContract(),signature,chain.priceSigner())) throw new Failure("REPORT_NOT_AUTHORIZED");
        try {
            FuturesDiagnostics.io(FuturesDiagnostics.Stage.DEPLOYMENT,()->{rpc.verifyDeployment(chain);return null;});
            if(store.hasUnresolved(chain.executor())) throw new Failure("FUTURES_EXECUTOR_BUSY");
            BigInteger nonce=FuturesDiagnostics.io(FuturesDiagnostics.Stage.RELAYER_NONCE,()->rpc.pendingNonce(chain.executor())),gasPrice=FuturesDiagnostics.io(FuturesDiagnostics.Stage.GAS_PRICE,rpc::gasPrice);
            String data=FuturesDiagnostics.work(FuturesDiagnostics.Stage.ENCODE_VERIFY,()->store.encoding(id,chain,report,signature));
            BigInteger gas=FuturesDiagnostics.io(FuturesDiagnostics.Stage.ESTIMATE,()->rpc.estimate(chain.executor(),nonce,gasPrice,chain.engine(),data)).multiply(BigInteger.valueOf(120)).divide(BigInteger.valueOf(100));
            if(gas.signum()<=0 || gasPrice.signum()<0 || nonce.signum()<0) throw new Failure("INVALID_RPC_TRANSACTION_INPUT");
            beforeSigning.run();
            store.assertActiveExecution(id,report,signature);
            byte[] signed=FuturesDiagnostics.work(FuturesDiagnostics.Stage.RAW_SIGN,()->TransactionEncoder.signMessage(RawTransaction.createTransaction(nonce,gasPrice,gas,chain.engine(),BigInteger.ZERO,data),chain.chainId().longValueExact(),executor));
            String raw=Numeric.toHexString(signed),hash=Numeric.toHexString(Hash.sha3(signed));
            // REQUIRES_NEW returns only after durable COMMIT. No network send before this point.
            FuturesDiagnostics.work(FuturesDiagnostics.Stage.SIGNED_COMMIT,()->{store.saveSigned(id,chain,report,signature,raw,hash,nonce.longValueExact());return null;});
            return broadcast(id,raw,hash);
        } catch(FuturesRpcGateway.PrecheckFailure rejected) { throw new Failure(rejected.code); }
        catch(IOException e) { throw FuturesDiagnostics.unavailable(FuturesDiagnostics.Stage.SENDER,e); }
    }

    public synchronized Submission recover(String id) {
        return FuturesDiagnostics.work(FuturesDiagnostics.Stage.RECOVERY,()->recoverInternal(id));
    }
    private Submission recoverInternal(String id) {
        var s=store.find(id).orElseThrow(()->new Failure("SIGNED_TRANSACTION_NOT_FOUND"));
        if(!s.chainId().equals(chain.chainId()) || !s.engine().equals(chain.engine()) || !s.sender().equals(chain.executor())) throw new Failure("RECOVERY_DEPLOYMENT_CONFLICT");
        if(!s.status().equals("SIGNED")) return new Submission(id,s.hash(),s.status());
        FuturesTransactionStore.validateRaw(chain,s.report(),s.signature(),s.raw(),s.hash(),s.nonce(),s.authorization());
        store.assertActiveExecution(id,s.report(),s.signature());
        if(!FuturesReportCodec.signedBy(s.report(),chain.chainId(),chain.priceContract(),s.signature(),chain.priceSigner())) throw new Failure("STORED_REPORT_NOT_AUTHORIZED");
        try {
            rpc.verifyDeployment(chain);
            if(rpc.known(s.hash())) { store.markSubmitted(id,s.hash()); return new Submission(id,s.hash(),"SUBMITTED"); }
            // Do not re-sign expired reports: the same raw must be resolved by a canonical receipt.
            return broadcast(id,s.raw(),s.hash());
        } catch(IOException e) { return new Submission(id,s.hash(),"SIGNED"); }
    }
    private Submission broadcast(String id,String raw,String hash) {
        try {
            if(!hash.equalsIgnoreCase(FuturesDiagnostics.io(FuturesDiagnostics.Stage.BROADCAST,()->rpc.broadcast(raw)))) return new Submission(id,hash,"SIGNED");
            store.markSubmitted(id,hash);
            return new Submission(id,hash,"SUBMITTED");
        } catch(IOException e) {
            // Includes provider rejection/unknown response. No error text, unlocked margin or replacement nonce.
            return new Submission(id,hash,"SIGNED");
        }
    }
}
