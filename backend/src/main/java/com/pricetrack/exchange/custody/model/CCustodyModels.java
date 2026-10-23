package com.pricetrack.exchange.custody.model;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable inputs for a Vault accounting projection; not an RPC/signature/canonicality proof. */
public final class CCustodyModels {
    private CCustodyModels() {}
    public static final BigInteger ZERO = BigInteger.ZERO;
    public static final BigInteger UINT_MAX = BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE);
    public static final String SCOPE = "VAULT_ACCOUNTING_PREVALIDATED_INPUT_ONLY; canonical RPC, signatures, "
        + "Report/Intent economics and timing, full ERC20 supply/wallet ownership, complete scan coverage "
        + "and canonical reorg selection are NOT verified here";
    public enum UserAction { OPEN, TOP_UP, CLOSE }
    public enum RiskAction { START_CALL, CLEAR_CALL, LIQUIDATE }
    public enum Status { ACTIVE, MARGIN_CALL, CLOSED, LIQUIDATED }
    public enum Outcome { SUCCESS, REVERTED }
    public enum VaultAction { DEPOSIT, WITHDRAW, FUND_HOUSE }

    public record Deployment(String id, BigInteger chainId, String genesisHash, String token,
            String vault, String controller, String rules, String relayer, String pauseAdmin,
            String oracleSigner, String policyHash) {
        public Deployment {
            Objects.requireNonNull(id); Objects.requireNonNull(chainId);
            if (id.isBlank() || chainId.signum() <= 0) throw new IllegalArgumentException("C_IDENTITY");
            genesisHash = hash(genesisHash); policyHash = hash(policyHash);
            token = address(token); vault = address(vault); controller = address(controller);
            rules = address(rules); relayer = address(relayer); pauseAdmin = address(pauseAdmin);
            oracleSigner = address(oracleSigner);
            if (relayer.equals(pauseAdmin) || relayer.equals(oracleSigner) || pauseAdmin.equals(oracleSigner))
                throw new IllegalArgumentException("C_ROLES");
        }
    }
    public record Block(long number, String hash, String parentHash) {
        public Block { if (number < 0) throw new IllegalArgumentException("C_BLOCK"); hash=CCustodyModels.hash(hash); parentHash=CCustodyModels.hash(parentHash); }
    }
    /** Terms already decoded/validated by a future C receipt verifier, never arbitrary API financial commands. */
    public sealed interface Call permits VaultCall, UserCall, RiskCall, CancelCall, PauseCall, ProcessClaimCall, DonationCall {}
    public record VaultCall(VaultAction action, String owner, BigInteger amount) implements Call {}
    public record UserCall(UserAction action, String owner, String operationId, String positionId,
            BigInteger nonce, BigInteger expectedVersion, BigInteger collateralBefore,
            BigInteger collateralAfter, String intentDigest, String reportDigest, boolean direct) implements Call {}
    public record RiskCall(RiskAction action, String owner, String operationId, String positionId,
            BigInteger expectedVersion, BigInteger collateral, String reportDigest) implements Call {}
    public record CancelCall(String owner, BigInteger expectedNonce) implements Call {}
    public record PauseCall(boolean paused) implements Call {}
    public record ProcessClaimCall() implements Call {}
    public record DonationCall(BigInteger amount) implements Call {}

    public sealed interface Event permits Transfer, Deposited, Withdrawn, HouseFunded, Locked,
            RuleApplied, ReportConsumed, UserExecuted, RiskExecuted, PositionSettled,
            ClaimCreated, ClaimPaid, NonceCancelled, PauseChanged {}
    public record Transfer(String from, String to, BigInteger amount) implements Event {}
    public record Deposited(String owner, BigInteger amount) implements Event {}
    public record Withdrawn(String owner, BigInteger amount) implements Event {}
    public record HouseFunded(String contributor, BigInteger amount) implements Event {}
    public record Locked(String operationId, String positionId, String owner,
            BigInteger delta, BigInteger lockedAfter) implements Event {}
    /** Already-decoded actual OperationApplied fields relevant to this accounting subset. */
    public record RuleApplied(String operationId, String positionId, String action,
            BigInteger version, BigInteger collateral, BigInteger realized, BigInteger badDebt,
            BigInteger callEpoch, BigInteger deadline) implements Event {}
    public record ReportConsumed(String operationId, BigInteger observedAt, BigInteger validUntil) implements Event {}
    public record UserExecuted(String operationId, String positionId, String owner, String intentDigest,
            String reportDigest, String action, BigInteger nonce, boolean direct) implements Event {}
    public record RiskExecuted(String operationId, String positionId, String owner,
            String reportDigest, String action) implements Event {}
    public record PositionSettled(String operationId, String positionId, String owner, BigInteger collateral,
            BigInteger realized, BigInteger badDebt, BigInteger paidProfit, BigInteger claimId) implements Event {}
    public record ClaimCreated(BigInteger claimId, String owner, String positionId,
            String operationId, BigInteger amount) implements Event {}
    public record ClaimPaid(BigInteger claimId, String owner, BigInteger amount,
            BigInteger remaining) implements Event {}
    public record NonceCancelled(String owner, BigInteger nonce) implements Event {}
    public record PauseChanged(boolean paused) implements Event {}

    public record Log(int index, String emitter, Event event) {
        public Log { if (index<0) throw new IllegalArgumentException("C_LOG_INDEX"); emitter=address(emitter); Objects.requireNonNull(event); }
    }
    public record Bundle(Deployment deployment, Block block, String transactionHash, int transactionIndex,
            String from, String to, Outcome outcome, Call call, List<Log> logs) {
        public Bundle {
            Objects.requireNonNull(deployment); Objects.requireNonNull(block); Objects.requireNonNull(outcome);
            Objects.requireNonNull(call); transactionHash=hash(transactionHash); from=address(from); to=address(to);
            if (transactionIndex<0) throw new IllegalArgumentException("C_TX_INDEX"); logs=List.copyOf(logs);
        }
    }
    public record TxKey(String blockHash, String transactionHash) {}
    public record Balance(BigInteger free, BigInteger locked) {}
    /** collateral is the Vault lock amount (zero after terminal), NOT rules' retained terminal collateral field. */
    public record Position(String owner, BigInteger collateral, BigInteger version,
            BigInteger callEpoch, BigInteger deadline, Status status) {}
    public record Claim(String owner, String operationId, String positionId,
            BigInteger original, BigInteger paid, BigInteger remaining) {}
    public record State(Deployment deployment, Block cursor, int lastTransactionIndex,
            Map<String,Balance> balances, Map<String,Position> positions, Map<BigInteger,Claim> claims,
            Map<String,BigInteger> nonces, Map<String,String> usedOperations,
            BigInteger totalFree, BigInteger totalLocked, BigInteger house, BigInteger actualAssets,
            BigInteger totalUnpaid, BigInteger head, BigInteger next, boolean paused,
            Map<TxKey,Bundle> observations) {
        public State {
            balances=Map.copyOf(balances); positions=Map.copyOf(positions); claims=Map.copyOf(claims);
            nonces=Map.copyOf(nonces); usedOperations=Map.copyOf(usedOperations); observations=Map.copyOf(observations);
        }
        public BigInteger recognizedAssets() { return totalFree.add(totalLocked).add(house); }
        public BigInteger unassignedAssets() { return actualAssets.subtract(recognizedAssets()); }
        public String verificationScope() { return SCOPE; }
        public boolean completeCoverage() { return false; }
    }
    public static String address(String value) {
        if (value == null || !value.matches("0x[0-9a-fA-F]{40}") || value.matches("0x0{40}"))
            throw new IllegalArgumentException("C_ADDRESS");
        return value.toLowerCase(java.util.Locale.ROOT);
    }
    public static String hash(String value) {
        if (value == null || !value.matches("0x[0-9a-fA-F]{64}")) throw new IllegalArgumentException("C_HASH");
        return value.toLowerCase(java.util.Locale.ROOT);
    }
}
