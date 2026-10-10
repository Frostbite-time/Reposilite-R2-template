package com.frostbite.reposilite.quota;

import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

final class QuotaStore implements AutoCloseable {
    private final QuotaConfig config;
    private final Clock clock;
    private final Connection connection;

    QuotaStore(QuotaConfig config, Clock clock) throws Exception {
        this.config = config;
        this.clock = clock;
        Files.createDirectories(config.database.getParent());
        Class.forName("org.sqlite.JDBC");
        connection = DriverManager.getConnection("jdbc:sqlite:" + config.database);
        try {
        try (Statement s = connection.createStatement()) {
            s.execute("PRAGMA busy_timeout=10000");
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("PRAGMA synchronous=FULL");
            s.execute("CREATE TABLE IF NOT EXISTS metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS periods (start TEXT PRIMARY KEY, end TEXT NOT NULL, "
                + "local_a INTEGER NOT NULL DEFAULT 0, local_b INTEGER NOT NULL DEFAULT 0, "
                + "offset_a INTEGER NOT NULL DEFAULT 0, offset_b INTEGER NOT NULL DEFAULT 0, "
                + "initialized INTEGER NOT NULL DEFAULT 0, last_sync INTEGER NOT NULL DEFAULT 0, "
                + "cloud_a INTEGER NOT NULL DEFAULT 0, cloud_b INTEGER NOT NULL DEFAULT 0, cloud_cutoff TEXT)");
            s.execute("CREATE TABLE IF NOT EXISTS attempts (period TEXT NOT NULL, minute INTEGER NOT NULL, "
                + "a INTEGER NOT NULL DEFAULT 0, b INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (period, minute))");
        }
        transaction(() -> {
            try (PreparedStatement p = connection.prepareStatement(
                    "INSERT OR IGNORE INTO metadata(key, value) VALUES ('identity', ?)")) {
                p.setString(1, config.identity()); p.executeUpdate();
            }
            try (Statement s = connection.createStatement(); ResultSet r = s.executeQuery(
                    "SELECT value FROM metadata WHERE key='identity'")) {
                if (!r.next() || !config.identity().equals(r.getString(1))) {
                    throw new SQLException("Quota account/endpoint/billing calendar changed; migrate the existing ledger explicitly");
                }
            }
            return null;
        });
        } catch (Exception | Error e) {
            try { connection.close(); } catch (SQLException close) { e.addSuppressed(close); }
            throw e;
        }
    }

    BillingPeriod period() {
        return BillingPeriod.current(clock.instant(), config.billingDay, config.billingZone);
    }

    synchronized void reserve(OperationClass type) throws Exception {
        if (type == OperationClass.FREE) return;
        transaction(() -> {
            Instant now = clock.instant();
            BillingPeriod period = BillingPeriod.current(now, config.billingDay, config.billingZone);
            ensurePeriod(period);
            long[] row = row(period);
            if (config.requireSync && row[4] == 0) {
                throw new QuotaBlocked("Initial Cloudflare usage sync is pending for this billing period");
            }
            boolean a = type == OperationClass.A;
            long used = Math.addExact(row[a ? 0 : 1], row[a ? 2 : 3]);
            long limit = a ? config.limitA : config.limitB;
            if (used >= limit) throw new QuotaBlocked("R2 Class " + type + " quota exhausted (" + used + "/" + limit + ")");
            String column = a ? "local_a" : "local_b";
            try (PreparedStatement p = connection.prepareStatement(
                    "UPDATE periods SET " + column + "=" + column + "+1 WHERE start=?")) {
                p.setString(1, period.start.toString()); p.executeUpdate();
            }
            // Minute buckets retain the exact local side of each cloud query cutoff.
            column = a ? "a" : "b";
            try (PreparedStatement p = connection.prepareStatement(
                    "INSERT INTO attempts(period, minute, " + column + ") VALUES (?, ?, 1) "
                    + "ON CONFLICT(period, minute) DO UPDATE SET " + column + "=" + column + "+1")) {
                p.setString(1, period.start.toString()); p.setLong(2, now.getEpochSecond() / 60);
                p.executeUpdate();
            }
            // COMMIT with synchronous=FULL happens before the caller sends the HTTP request.
            return null;
        });
    }

    synchronized void reconcile(BillingPeriod period, Instant cutoff, long cloudA, long cloudB) throws Exception {
        if (cloudA < 0 || cloudB < 0 || cutoff.isBefore(period.start) || cutoff.isAfter(period.end)
                || cutoff.getEpochSecond() % 60 != 0 || cutoff.getNano() != 0) {
            throw new IllegalArgumentException("Invalid Cloudflare reconciliation window");
        }
        transaction(() -> {
            ensurePeriod(period);
            long localA = 0, localB = 0;
            try (PreparedStatement p = connection.prepareStatement(
                    "SELECT COALESCE(SUM(a),0), COALESCE(SUM(b),0) FROM attempts WHERE period=? AND minute<?")) {
                p.setString(1, period.start.toString()); p.setLong(2, cutoff.getEpochSecond() / 60);
                try (ResultSet r = p.executeQuery()) { r.next(); localA = r.getLong(1); localB = r.getLong(2); }
            }
            long[] old = row(period);
            long offsetA = Math.max(old[2], Math.max(0, cloudA - localA));
            long offsetB = Math.max(old[3], Math.max(0, cloudB - localB));
            Math.addExact(old[0], offsetA); Math.addExact(old[1], offsetB);
            try (PreparedStatement p = connection.prepareStatement(
                    "UPDATE periods SET offset_a=?, offset_b=?, initialized=1, last_sync=?, "
                    + "cloud_a=?, cloud_b=?, cloud_cutoff=? WHERE start=?")) {
                p.setLong(1, offsetA); p.setLong(2, offsetB); p.setLong(3, clock.instant().getEpochSecond());
                p.setLong(4, cloudA); p.setLong(5, cloudB); p.setString(6, cutoff.toString());
                p.setString(7, period.start.toString()); p.executeUpdate();
            }
            return null;
        });
    }

    synchronized boolean syncDue() throws Exception {
        return transaction(() -> {
            BillingPeriod p = period(); ensurePeriod(p); long[] r = row(p);
            return r[4] == 0 || clock.instant().getEpochSecond() - r[5] >= config.syncIntervalSeconds;
        });
    }

    synchronized Map<String, Object> status() throws Exception {
        return transaction(() -> {
            BillingPeriod p = period(); ensurePeriod(p); long[] r = row(p);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("enabled", config.enabled); result.put("period_start", p.start.toString());
            result.put("period_end", p.end.toString()); result.put("initialized", r[4] != 0);
            result.put("initial_sync_required", config.requireSync);
            result.put("class_a", classStatus(r[0], r[2], config.limitA, r[6]));
            result.put("class_b", classStatus(r[1], r[3], config.limitB, r[7]));
            result.put("last_sync", r[5] == 0 ? null : Instant.ofEpochSecond(r[5]).toString());
            result.put("sync_enabled", config.syncEnabled);
            result.put("sync_interval_seconds", config.syncIntervalSeconds);
            try (PreparedStatement q = connection.prepareStatement("SELECT cloud_cutoff FROM periods WHERE start=?")) {
                q.setString(1, p.start.toString());
                try (ResultSet rs = q.executeQuery()) { rs.next(); result.put("cloud_cutoff", rs.getString(1)); }
            }
            return result;
        });
    }

    private Map<String, Long> classStatus(long local, long offset, long limit, long cloud) {
        long used = Math.addExact(local, offset);
        Map<String, Long> m = new LinkedHashMap<>();
        m.put("limit", limit); m.put("used", used); m.put("remaining", Math.max(0, limit - used));
        m.put("local_attempts", local); m.put("cloud_adjustment", offset); m.put("last_cloud_total", cloud);
        return m;
    }

    private void ensurePeriod(BillingPeriod p) throws SQLException {
        try (PreparedStatement q = connection.prepareStatement("INSERT OR IGNORE INTO periods(start,end) VALUES (?,?)")) {
            q.setString(1, p.start.toString()); q.setString(2, p.end.toString()); q.executeUpdate();
        }
    }

    private long[] row(BillingPeriod p) throws SQLException {
        try (PreparedStatement q = connection.prepareStatement(
                "SELECT local_a,local_b,offset_a,offset_b,initialized,last_sync,cloud_a,cloud_b FROM periods WHERE start=?")) {
            q.setString(1, p.start.toString());
            try (ResultSet r = q.executeQuery()) {
                if (!r.next()) throw new SQLException("Missing quota billing period");
                long[] values = new long[8]; for (int i = 0; i < values.length; i++) values[i] = r.getLong(i + 1);
                return values;
            }
        }
    }

    private <T> T transaction(Work<T> work) throws Exception {
        try (Statement s = connection.createStatement()) { s.execute("BEGIN IMMEDIATE"); }
        try {
            T result = work.run();
            try (Statement s = connection.createStatement()) { s.execute("COMMIT"); }
            return result;
        } catch (Exception | Error e) {
            try (Statement s = connection.createStatement()) { s.execute("ROLLBACK"); }
            catch (SQLException rollback) { e.addSuppressed(rollback); }
            throw e;
        }
    }

    @Override public synchronized void close() throws SQLException { connection.close(); }
    private interface Work<T> { T run() throws Exception; }
    static final class QuotaBlocked extends Exception {
        QuotaBlocked(String message) { super(message); }
    }
}
