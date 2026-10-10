package com.frostbite.reposilite.quota;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

final class CloudflareSync {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final URI ENDPOINT = URI.create("https://api.cloudflare.com/client/v4/graphql");
    private static final String QUERY = "query QuotaUsage($account: string!, $start: Time!, $end: Time!) { "
        + "viewer { accounts(filter: {accountTag: $account}) { "
        + "r2OperationsAdaptiveGroups(limit: 1000, filter: {datetime_geq: $start, datetime_lt: $end}) { "
        + "sum { requests } dimensions { actionType } } } } }";
    private final QuotaConfig config;
    private final QuotaStore store;
    private final Clock clock;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    CloudflareSync(QuotaConfig config, QuotaStore store, Clock clock) {
        this.config = config; this.store = store; this.clock = clock;
    }

    void sync() throws Exception {
        if (config.token.isEmpty()) throw new IOException("CF_R2_ANALYTICS_TOKEN is missing (Account Analytics: Read required)");
        BillingPeriod period = store.period();
        Instant cutoff = clock.instant().minusSeconds(config.syncLagSeconds).truncatedTo(ChronoUnit.MINUTES);
        if (!cutoff.isAfter(period.start)) throw new IOException("Waiting for the first complete analytics window of this period");
        long a = 0, b = 0;
        // Short windows reduce sampling and stay within GraphQL's maximum query range.
        for (Instant start = period.start; start.isBefore(cutoff);) {
            Instant end = start.plus(1, ChronoUnit.DAYS);
            if (end.isAfter(cutoff)) end = cutoff;
            String body = JSON.writeValueAsString(Map.of("query", QUERY, "variables", Map.of(
                "account", config.account, "start", start.toString(), "end", end.toString())));
            HttpRequest request = HttpRequest.newBuilder(ENDPOINT).timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + config.token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new IOException("Cloudflare Analytics returned HTTP " + response.statusCode());
            long[] counts = parse(response.body());
            a = Math.addExact(a, counts[0]); b = Math.addExact(b, counts[1]); start = end;
        }
        // All windows must succeed before changing either counter.
        store.reconcile(period, cutoff, a, b);
        System.out.println("[r2-quota] Cloudflare sync complete: cutoff=" + cutoff + " A=" + a + " B=" + b);
    }

    static long[] parse(String body) throws IOException {
        JsonNode root = JSON.readTree(body);
        JsonNode errors = root.get("errors");
        if (errors != null && !errors.isNull() && (!errors.isArray() || errors.size() != 0)) {
            // Do not log response bodies, variables or credentials.
            throw new IOException("Cloudflare GraphQL query failed; check token permissions and dataset availability");
        }
        JsonNode accounts = root.path("data").path("viewer").path("accounts");
        if (!accounts.isArray() || accounts.size() != 1) throw new IOException("Cloudflare account analytics is unavailable");
        JsonNode groups = accounts.get(0).get("r2OperationsAdaptiveGroups");
        if (groups == null || !groups.isArray() || groups.size() >= 1000) throw new IOException("Missing or truncated R2 analytics result");
        long a = 0, b = 0;
        for (JsonNode group : groups) {
            JsonNode amount = group.path("sum").path("requests");
            JsonNode action = group.path("dimensions").path("actionType");
            if (!amount.isNumber() || !action.isTextual()) throw new IOException("Invalid R2 analytics group");
            long n;
            try { n = amount.decimalValue().setScale(0, RoundingMode.CEILING).longValueExact(); }
            catch (ArithmeticException e) { throw new IOException("Invalid R2 analytics count", e); }
            if (n < 0) throw new IOException("Negative R2 analytics count");
            OperationClass type;
            try { type = OperationClass.classify(action.asText()); }
            catch (IllegalArgumentException e) { throw new IOException(e.getMessage(), e); }
            if (type == OperationClass.A) a = Math.addExact(a, n);
            if (type == OperationClass.B) b = Math.addExact(b, n);
        }
        return new long[]{a, b};
    }
}
