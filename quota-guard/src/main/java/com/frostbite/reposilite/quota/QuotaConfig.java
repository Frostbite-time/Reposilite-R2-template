package com.frostbite.reposilite.quota;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.Map;

final class QuotaConfig {
    final boolean enabled, syncEnabled, requireSync;
    final long limitA, limitB, syncIntervalSeconds, syncLagSeconds;
    final int billingDay;
    final ZoneId billingZone;
    final Path database;
    final String account, endpointHost, token;

    QuotaConfig(Map<String, String> env) {
        enabled = flag(env, "R2_QUOTA_ENABLED", true);
        syncEnabled = flag(env, "R2_QUOTA_SYNC_ENABLED", true);
        // Without automatic sync nothing would ever initialize a period, so the gate defaults to off.
        requireSync = flag(env, "R2_QUOTA_REQUIRE_INITIAL_SYNC", syncEnabled);
        limitA = number(env, "R2_QUOTA_CLASS_A_LIMIT", 900_000, 0, Long.MAX_VALUE);
        limitB = number(env, "R2_QUOTA_CLASS_B_LIMIT", 9_000_000, 0, Long.MAX_VALUE);
        syncIntervalSeconds = number(env, "R2_QUOTA_SYNC_INTERVAL_SECONDS", 86400, 60, 2_678_400);
        syncLagSeconds = number(env, "R2_QUOTA_SYNC_LAG_SECONDS", 300, 0, 86400);
        billingDay = (int) number(env, "R2_QUOTA_BILLING_DAY", 1, 1, 31);
        billingZone = ZoneId.of(value(env, "R2_QUOTA_BILLING_TIMEZONE", "UTC"));
        database = Path.of(value(env, "R2_QUOTA_DB", "/app/data/r2-quota/quota.db")).toAbsolutePath();
        account = value(env, "R2_ACCOUNT_ID", "");
        String endpoint = value(env, "R2_ENDPOINT", "https://" + account + ".r2.cloudflarestorage.com");
        endpointHost = URI.create(endpoint).getHost();
        if (enabled && (endpointHost == null || endpointHost.isEmpty())) {
            throw new IllegalArgumentException("R2_ENDPOINT must have a hostname");
        }
        if (enabled && syncEnabled && !account.matches("[0-9a-fA-F]{32}")) {
            throw new IllegalArgumentException("R2_ACCOUNT_ID must be a 32-character Cloudflare account ID");
        }
        String secret = value(env, "CF_R2_ANALYTICS_TOKEN", "");
        String tokenFile = value(env, "CF_R2_ANALYTICS_TOKEN_FILE", "");
        if (!tokenFile.isEmpty()) {
            try { secret = Files.readString(Path.of(tokenFile)).trim(); }
            catch (Exception e) { throw new IllegalArgumentException("Cannot read CF_R2_ANALYTICS_TOKEN_FILE", e); }
        }
        token = secret;
    }

    String identity() {
        return account + "|" + endpointHost + "|" + billingDay + "|" + billingZone;
    }

    private static String value(Map<String, String> env, String key, String fallback) {
        String v = env.get(key);
        return v == null || v.trim().isEmpty() ? fallback : v.trim();
    }

    private static long number(Map<String, String> env, String key, long fallback, long min, long max) {
        try {
            long n = Long.parseLong(value(env, key, Long.toString(fallback)));
            if (n < min || n > max) throw new NumberFormatException();
            return n;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be an integer between " + min + " and " + max);
        }
    }

    private static boolean flag(Map<String, String> env, String key, boolean fallback) {
        String v = value(env, key, Boolean.toString(fallback));
        if (v.equalsIgnoreCase("true")) return true;
        if (v.equalsIgnoreCase("false")) return false;
        throw new IllegalArgumentException(key + " must be true or false");
    }
}
