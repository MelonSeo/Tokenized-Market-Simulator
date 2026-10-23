package com.pricetrack.exchange.custody.store;

import static com.pricetrack.exchange.custody.model.CCustodyModels.*;

import com.pricetrack.exchange.custody.projection.CCustodyReducer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import javax.sql.DataSource;

/** Explicit C-only PostgreSQL persistence for prevalidated accounting inputs.
 * Not a Spring bean, chain sender, canonical fork selector, or complete audit. */
public final class CCustodyProjectionStore {
    private static final String NS = "tms_c_custody.";
    private static final int MAX_BATCH = 256;
    private static final int MAX_BATCH_BYTES = 8_388_608;
    private final DataSource source;
    private final CCustodyStateCodec codec;
    private final CCustodyReducer reducer;

    public record Head(long epoch, long revision, String stateHash, Block cursor, int lastTransactionIndex) {
        public Head {
            if (epoch != 1 || revision < 0 || stateHash == null || !stateHash.matches("[0-9a-f]{64}")
                || cursor == null || lastTransactionIndex < -1) throw new IllegalArgumentException("C_STORE_HEAD");
        }
    }
    public record Snapshot(Head head, State state) {}
    /** No SQLException text, credentials, input payload, or raw signatures are exposed. */
    public static final class Failure extends RuntimeException {
        private final String code;
        private final String sqlState;
        public Failure(String code) { this(code, null); }
        private Failure(String code, String sqlState) {
            super(code); this.code = code;
            this.sqlState = sqlState != null && sqlState.matches("[A-Z0-9]{5}") ? sqlState : null;
        }
        public String code() { return code; }
        public String sqlState() { return sqlState; }
        public boolean commitUncertain() { return code.equals("C_STORE_COMMIT_UNKNOWN"); }
    }

    public CCustodyProjectionStore(DataSource source, CCustodyStateCodec codec, CCustodyReducer reducer) {
        this.source = Objects.requireNonNull(source); this.codec = Objects.requireNonNull(codec);
        this.reducer = Objects.requireNonNull(reducer);
    }

    /** Caller must establish ownership of the NEW database before explicit installation. */
    public void installSchema() {
        byte[] sql;
        try (var in = CCustodyProjectionStore.class.getResourceAsStream("/c-custody-schema.sql")) {
            if (in == null) throw new Failure("C_STORE_SCHEMA_MISSING");
            sql = in.readNBytes(65_537);
            if (sql.length > 65_536) throw new Failure("C_STORE_SCHEMA_SIZE");
        } catch (IOException e) { throw new Failure("C_STORE_SCHEMA_READ"); }
        transaction(c -> {
            try (Statement s = c.createStatement()) { s.setQueryTimeout(5); s.execute(new String(sql, StandardCharsets.UTF_8)); }
            return null;
        });
    }

    /** Empty anchor is a caller assertion, not an on-chain deployment proof. Epoch 1 only. */
    public Snapshot bootstrap(Deployment deployment, Block anchor) {
        Objects.requireNonNull(deployment); Objects.requireNonNull(anchor); id(deployment.id());
        byte[] encoded = codec.encodeDeployment(deployment);
        State initial = reducer.initial(deployment, anchor);
        byte[] stateBytes = codec.encodeState(initial);
        String seedHash = CCustodyStateCodec.sha256(stateBytes);
        return transaction(c -> {
            try (PreparedStatement s = statement(c, "INSERT INTO " + NS + "deployments VALUES(?,?,?,?) ON CONFLICT(deployment_id) DO NOTHING")) {
                s.setString(1, deployment.id()); s.setInt(2, CCustodyStateCodec.VERSION);
                s.setBytes(3, encoded); s.setString(4, CCustodyStateCodec.sha256(encoded)); s.executeUpdate();
            }
            Deployment stored = lockDeployment(c, deployment.id());
            if (!stored.equals(deployment)) throw new Failure("C_STORE_DEPLOYMENT_CONFLICT");
            if (hasHead(c, deployment.id())) {
                verifyAnchor(c, deployment.id(), anchor, seedHash);
                return readSnapshot(c, deployment.id(), stored);
            }
            // Refuse an arbitrary pre-existing evidence set under a new empty bootstrap.
            if (observationCount(c, deployment.id()) != 0) throw new Failure("C_STORE_BOOTSTRAP_EVIDENCE");
            try (PreparedStatement s = statement(c, "INSERT INTO " + NS + "canonical_epochs VALUES(?,1,?,?,?,?)")) {
                s.setString(1, deployment.id()); s.setLong(2, anchor.number()); s.setString(3, anchor.hash());
                s.setString(4, anchor.parentHash()); s.setString(5, seedHash); s.executeUpdate();
            }
            Snapshot snapshot = insertSnapshot(c, initial, 0);
            try (PreparedStatement s = statement(c, "INSERT INTO " + NS + "projection_heads VALUES(?,1,0,?)")) {
                s.setString(1, deployment.id()); s.setString(2, snapshot.head().stateHash()); s.executeUpdate();
            }
            return snapshot;
        });
    }

    public Snapshot load(String deploymentId) {
        id(deploymentId);
        return transaction(c -> readSnapshot(c, deploymentId, readDeployment(c, deploymentId, false)));
    }

    /** One bounded batch and one snapshot/head commit, no retry or network action. */
    public Snapshot append(String deploymentId, Head expected, List<Bundle> input) {
        id(deploymentId); Objects.requireNonNull(expected); List<Bundle> batch = List.copyOf(input);
        if (batch.isEmpty() || batch.size() > MAX_BATCH) throw new Failure("C_STORE_BATCH_LIMIT");
        // Codec bounds and deterministic bytes are checked before acquiring any DB lock.
        List<byte[]> payloads = new ArrayList<>();
        int bytesUsed = 0;
        for (Bundle b : batch) {
            byte[] encoded = codec.encodeBundle(b);
            if (encoded.length > MAX_BATCH_BYTES - bytesUsed) throw new Failure("C_STORE_BATCH_BYTES");
            bytesUsed += encoded.length; payloads.add(encoded);
        }
        return transaction(c -> {
            Deployment deployment = lockDeployment(c, deploymentId);
            Snapshot current = readSnapshot(c, deploymentId, deployment);
            boolean fullReplay = batch.stream().allMatch(b -> b.equals(current.state().observations()
                .get(new TxKey(b.block().hash(), b.transactionHash()))));
            if (!current.head().equals(expected) && !fullReplay) throw new Failure("C_STORE_STALE_HEAD");
            State next = current.state();
            for (int n = 0; n < batch.size(); n++) {
                Bundle b = batch.get(n);
                // Explicit equality is required even for replay before relying on C4a's reducer.
                if (!b.deployment().equals(deployment)) throw new Failure("C_STORE_DEPLOYMENT_CONFLICT");
                Bundle old = next.observations().get(new TxKey(b.block().hash(), b.transactionHash()));
                if (old != null && !old.equals(b)) throw new Failure("C_STORE_OBSERVATION_CONFLICT");
                next = reducer.apply(next, b);
                if (old == null) putObservation(c, b, payloads.get(n));
                else verifyObservation(c, b, payloads.get(n));
            }
            if (fullReplay) return current;
            return moveHead(c, current, next);
        });
    }

    /** An explicit empty-block assertion, not a completed chain scan. */
    public Snapshot advanceEmpty(String deploymentId, Head expected, Block block) {
        id(deploymentId); Objects.requireNonNull(expected); Objects.requireNonNull(block);
        return transaction(c -> {
            Deployment deployment = lockDeployment(c, deploymentId);
            Snapshot current = readSnapshot(c, deploymentId, deployment);
            if (current.state().cursor().equals(block) && current.state().lastTransactionIndex() == -1) return current;
            if (!current.head().equals(expected)) throw new Failure("C_STORE_STALE_HEAD");
            return moveHead(c, current, reducer.advanceEmptyBlock(current.state(), block));
        });
    }

    private Snapshot moveHead(Connection c, Snapshot current, State next) throws SQLException {
        long revision;
        try { revision = Math.addExact(current.head().revision(), 1); }
        catch (ArithmeticException e) { throw new Failure("C_STORE_REVISION_LIMIT"); }
        Snapshot result = insertSnapshot(c, next, revision);
        try (PreparedStatement s = statement(c, "UPDATE " + NS
            + "projection_heads SET epoch=1,revision=?,state_hash=? WHERE deployment_id=? AND epoch=? AND revision=? AND state_hash=?")) {
            s.setLong(1, revision); s.setString(2, result.head().stateHash()); s.setString(3, next.deployment().id());
            s.setLong(4, current.head().epoch()); s.setLong(5, current.head().revision()); s.setString(6, current.head().stateHash());
            if (s.executeUpdate() != 1) throw new Failure("C_STORE_HEAD_CONFLICT");
        }
        return result;
    }

    private Snapshot insertSnapshot(Connection c, State state, long revision) throws SQLException {
        byte[] payload = codec.encodeState(state); String digest = CCustodyStateCodec.sha256(payload);
        try (PreparedStatement s = statement(c, "INSERT INTO " + NS + "projection_snapshots VALUES(?,1,?,?,?,?,?,?,?)")) {
            s.setString(1, state.deployment().id()); s.setLong(2, revision); s.setLong(3, state.cursor().number());
            s.setString(4, state.cursor().hash()); s.setInt(5, state.lastTransactionIndex());
            s.setInt(6, CCustodyStateCodec.VERSION); s.setBytes(7, payload); s.setString(8, digest); s.executeUpdate();
        }
        return new Snapshot(new Head(1, revision, digest, state.cursor(), state.lastTransactionIndex()), state);
    }

    private Snapshot readSnapshot(Connection c, String id, Deployment deployment) throws SQLException {
        try (PreparedStatement s = statement(c, "SELECT s.* FROM " + NS + "projection_heads h JOIN " + NS
            + "projection_snapshots s ON s.deployment_id=h.deployment_id AND s.epoch=h.epoch AND s.revision=h.revision AND s.state_hash=h.state_hash WHERE h.deployment_id=?")) {
            s.setString(1, id);
            try (ResultSet r = s.executeQuery()) {
                if (!r.next()) throw new Failure("C_STORE_HEAD_MISSING");
                byte[] payload = payload(r, "state_hash"); State state = codec.decodeState(payload);
                if (!state.deployment().equals(deployment) || state.cursor().number() != r.getLong("block_number")
                    || !state.cursor().hash().equals(r.getString("block_hash"))
                    || state.lastTransactionIndex() != r.getInt("tx_index")) throw new Failure("C_STORE_SNAPSHOT_CONFLICT");
                Head head = new Head(r.getLong("epoch"), r.getLong("revision"), r.getString("state_hash"), state.cursor(), state.lastTransactionIndex());
                // Missing or conflicting DB evidence is not silently repaired from a snapshot.
                for (Bundle b : state.observations().values()) verifyObservation(c, b, codec.encodeBundle(b));
                return new Snapshot(head, state);
            }
        }
    }

    private void putObservation(Connection c, Bundle bundle, byte[] bytes) throws SQLException {
        try (PreparedStatement s = statement(c, "INSERT INTO " + NS + "chain_observations VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(deployment_id,block_hash,tx_hash) DO NOTHING")) {
            s.setString(1, bundle.deployment().id()); s.setString(2, bundle.block().hash()); s.setString(3, bundle.transactionHash());
            s.setLong(4, bundle.block().number()); s.setInt(5, bundle.transactionIndex()); s.setInt(6, CCustodyStateCodec.VERSION);
            s.setBytes(7, bytes); s.setString(8, CCustodyStateCodec.sha256(bytes)); s.executeUpdate();
        }
        verifyObservation(c, bundle, bytes);
    }
    private void verifyObservation(Connection c, Bundle bundle, byte[] bytes) throws SQLException {
        try (PreparedStatement s = statement(c, "SELECT * FROM " + NS + "chain_observations WHERE deployment_id=? AND block_hash=? AND tx_hash=?")) {
            s.setString(1, bundle.deployment().id()); s.setString(2, bundle.block().hash()); s.setString(3, bundle.transactionHash());
            try (ResultSet r = s.executeQuery()) {
                if (!r.next() || !Arrays.equals(payload(r, "payload_hash"), bytes)
                    || r.getLong("block_number") != bundle.block().number() || r.getInt("tx_index") != bundle.transactionIndex())
                    throw new Failure("C_STORE_OBSERVATION_CONFLICT");
            }
        }
    }

    private Deployment lockDeployment(Connection c, String id) throws SQLException { return readDeployment(c, id, true); }
    private Deployment readDeployment(Connection c, String id, boolean lock) throws SQLException {
        try (PreparedStatement s = statement(c, "SELECT * FROM " + NS + "deployments WHERE deployment_id=?" + (lock ? " FOR UPDATE" : ""))) {
            s.setString(1, id);
            try (ResultSet r = s.executeQuery()) {
                if (!r.next()) throw new Failure("C_STORE_DEPLOYMENT_MISSING");
                Deployment deployment = codec.decodeDeployment(payload(r, "payload_hash"));
                if (!deployment.id().equals(id)) throw new Failure("C_STORE_DEPLOYMENT_CONFLICT");
                return deployment;
            }
        }
    }
    private void verifyAnchor(Connection c, String id, Block anchor, String seedHash) throws SQLException {
        try (PreparedStatement s = statement(c, "SELECT * FROM " + NS + "canonical_epochs WHERE deployment_id=? AND epoch=1")) {
            s.setString(1, id);
            try (ResultSet r = s.executeQuery()) {
                if (!r.next() || r.getLong("anchor_number") != anchor.number() || !r.getString("anchor_hash").equals(anchor.hash())
                    || !r.getString("anchor_parent_hash").equals(anchor.parentHash()) || !r.getString("seed_state_hash").equals(seedHash))
                    throw new Failure("C_STORE_ANCHOR_CONFLICT");
            }
        }
    }
    private long observationCount(Connection c, String id) throws SQLException {
        try (PreparedStatement s = statement(c, "SELECT count(*) FROM " + NS + "chain_observations WHERE deployment_id=?")) {
            s.setString(1, id); try (ResultSet r = s.executeQuery()) { r.next(); return r.getLong(1); }
        }
    }
    private boolean hasHead(Connection c, String id) throws SQLException {
        try (PreparedStatement s = statement(c, "SELECT 1 FROM " + NS + "projection_heads WHERE deployment_id=?")) {
            s.setString(1, id); try (ResultSet r = s.executeQuery()) { return r.next(); }
        }
    }
    private static byte[] payload(ResultSet r, String hashColumn) throws SQLException {
        byte[] value = r.getBytes("payload");
        if (r.getInt("codec_version") != CCustodyStateCodec.VERSION || value == null || value.length == 0 || value.length > 4_194_304
            || !CCustodyStateCodec.sha256(value).equals(r.getString(hashColumn))) throw new Failure("C_STORE_PAYLOAD_INTEGRITY");
        return value;
    }
    private static void id(String id) {
        if (id == null || id.isBlank() || id.length() > 128) throw new Failure("C_STORE_DEPLOYMENT_ID");
    }
    private static PreparedStatement statement(Connection c, String sql) throws SQLException {
        PreparedStatement s = c.prepareStatement(sql); s.setQueryTimeout(5); return s;
    }
    private interface Work<T> { T apply(Connection connection) throws SQLException; }
    private <T> T transaction(Work<T> work) {
        boolean commitAttempted = false;
        try (Connection c = source.getConnection()) {
            if (!"PostgreSQL".equals(c.getMetaData().getDatabaseProductName())) throw new Failure("C_STORE_POSTGRES_REQUIRED");
            if (!c.getAutoCommit()) throw new Failure("C_STORE_CONNECTION_STATE");
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED); c.setAutoCommit(false);
            try {
                try (Statement s = c.createStatement()) {
                    s.setQueryTimeout(5); s.execute("SET LOCAL statement_timeout='5000ms'; SET LOCAL lock_timeout='2000ms'");
                }
                T result = work.apply(c); commitAttempted = true; c.commit(); return result;
            } catch (SQLException e) {
                if (!commitAttempted) rollback(c);
                throw new Failure(commitAttempted ? "C_STORE_COMMIT_UNKNOWN" : "C_STORE_SQL_FAILURE", e.getSQLState());
            } catch (RuntimeException e) { rollback(c); throw e; }
        } catch (SQLException e) {
            throw new Failure(commitAttempted ? "C_STORE_COMMIT_UNKNOWN" : "C_STORE_CONNECTION_FAILURE", e.getSQLState());
        } catch (RuntimeException e) {
            // Resource close errors can otherwise attach raw driver text as suppressed data.
            if (e instanceof Failure failure) throw new Failure(failure.code(), failure.sqlState());
            if (e.getSuppressed().length != 0) throw new Failure("C_STORE_INPUT_OR_CLOSE_FAILURE");
            throw e;
        }
    }
    private static void rollback(Connection c) { try { c.rollback(); } catch (SQLException ignored) { /* outcome is not promoted */ } }
}
