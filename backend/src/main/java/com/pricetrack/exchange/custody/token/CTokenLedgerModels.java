package com.pricetrack.exchange.custody.token;

import com.pricetrack.exchange.custody.abi.CAbiModels;
import com.pricetrack.exchange.custody.abi.CAbiEventModels.Event;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;

/** C fixed-token projection inputs, not RPC observations or canonical/ownership attestation. */
public final class CTokenLedgerModels {
    private CTokenLedgerModels() {}
    public static final BigInteger FIXED_SUPPLY=BigInteger.valueOf(20_000_000).multiply(BigInteger.TEN.pow(18));
    public static final String ZERO_ADDRESS="0x"+"0".repeat(40);
    public static final String SCOPE="PREVALIDATED_TOKEN_LOGS_ONLY; no actual canonical/hash/code/authorization/EOA, "
        +"current allowance, Vault attribution, custody availability or solvency proof";
    public enum Outcome { SUCCESS, REVERTED }
    public record Block(BigInteger number,String hash,String parentHash) {
        public Block {CAbiModels.uint(number);hash=CAbiModels.hash(hash);parentHash=CAbiModels.hash(parentHash);}
    }
    public record Deployment(String id,BigInteger chainId,String genesisHash,String token,String treasury,
            String tokenCodeHash,Block deploymentBlock,String deploymentTransactionHash,
            BigInteger deploymentTransactionIndex,BigInteger mintLogIndex) {
        public Deployment {
            if(id==null||id.isBlank()||id.length()>128)throw new IllegalArgumentException("C_TOKEN_ID");
            CAbiModels.uint(chainId);if(chainId.signum()==0)throw new IllegalArgumentException("C_TOKEN_CHAIN");
            genesisHash=CAbiModels.hash(genesisHash);token=nonzero(token);treasury=nonzero(treasury);
            tokenCodeHash=CAbiModels.hash(tokenCodeHash);deploymentTransactionHash=CAbiModels.hash(deploymentTransactionHash);
            if(deploymentBlock==null||deploymentBlock.number().signum()==0)throw new IllegalArgumentException("C_TOKEN_DEPLOYMENT_BLOCK");
            CAbiModels.uint(deploymentTransactionIndex);CAbiModels.uint(mintLogIndex);
        }
    }
    public record Log(BigInteger index,String emitter,Event event) {
        public Log {CAbiModels.uint(index);emitter=nonzero(emitter);if(event==null)throw new IllegalArgumentException("C_TOKEN_EVENT");}
    }
    /** Logs are the complete token-address subset asserted by an upstream verifier, not checked by this model. */
    public record Transaction(Deployment deployment,Block block,String hash,BigInteger index,Outcome outcome,List<Log> logs) {
        public Transaction {
            if(deployment==null||block==null||outcome==null)throw new IllegalArgumentException("C_TOKEN_TRANSACTION");
            hash=CAbiModels.hash(hash);CAbiModels.uint(index);logs=List.copyOf(logs);
        }
    }
    public record TxKey(String blockHash,String transactionHash) {}
    public record LogKey(String blockHash,String transactionHash,BigInteger logIndex) {}
    public record ApprovalKey(String owner,String spender) {}
    /** Last observed Approval value, explicitly NOT reconstructed current allowance. */
    public record ApprovalObservation(BigInteger observedValue,LogKey source) {}
    public record State(Deployment deployment,Block cursor,BigInteger lastTransactionIndex,boolean minted,
            BigInteger totalSupply,Map<String,BigInteger> balances,
            Map<ApprovalKey,ApprovalObservation> lastObservedApprovals,Map<TxKey,Transaction> observations,List<Block> headers) {
        public State {balances=Map.copyOf(balances);lastObservedApprovals=Map.copyOf(lastObservedApprovals);observations=Map.copyOf(observations);headers=List.copyOf(headers);}
        public BigInteger balanceOf(String owner) {return balances.getOrDefault(CAbiModels.address(owner),BigInteger.ZERO);}
        public String verificationScope() {return SCOPE;}
        public boolean completeCoverage() {return false;}
        public boolean currentAllowanceVerified() {return false;}
    }
    public static String nonzero(String value) {
        String a=CAbiModels.address(value);if(a.equals(ZERO_ADDRESS))throw new IllegalArgumentException("C_TOKEN_ZERO_ADDRESS");return a;
    }
}
