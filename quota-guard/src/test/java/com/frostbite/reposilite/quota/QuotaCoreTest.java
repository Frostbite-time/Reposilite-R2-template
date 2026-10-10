package com.frostbite.reposilite.quota;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.core.interceptor.ClasspathInterceptorChainFactory;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/** Core safety tests only; every HTTP request uses a local mock, never a real R2 bucket. */
public final class QuotaCoreTest {
    public static void main(String[] args) throws Exception {
        System.setProperty("tinylog.writerFile", "console");
        System.setProperty("tinylog.writerFile.level", "off");
        Path root = Files.createTempDirectory("r2-quota-core-");
        try {
            concurrencyAndRestart(root);
            reconciliation(root);
            rollover(root);
            analyticsParsing();
            sdkTransmission(root);
            System.out.println("PASS: all quota core tests");
        } finally {
            try (java.util.stream.Stream<Path> files = Files.walk(root)) {
                for (Path p : files.sorted(java.util.Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(p);
            }
        }
    }

    private static Map<String, String> env(Path database) {
        Map<String, String> e = new HashMap<>();
        e.put("R2_ACCOUNT_ID", "00000000000000000000000000000000");
        e.put("R2_QUOTA_DB", database.toString());
        e.put("R2_QUOTA_SYNC_ENABLED", "false");
        e.put("R2_QUOTA_REQUIRE_INITIAL_SYNC", "false");
        e.put("R2_QUOTA_CLASS_A_LIMIT", "20"); e.put("R2_QUOTA_CLASS_B_LIMIT", "8");
        e.put("R2_QUOTA_BILLING_DAY", "27");
        return e;
    }

    private static void concurrencyAndRestart(Path root) throws Exception {
        QuotaConfig c = new QuotaConfig(env(root.resolve("concurrency.db")));
        Clock clock = Clock.fixed(Instant.parse("2026-10-10T12:00:00Z"), ZoneOffset.UTC);
        AtomicInteger accepted = new AtomicInteger();
        try (QuotaStore one = new QuotaStore(c, clock); QuotaStore two = new QuotaStore(c, clock)) {
            ExecutorService workers = Executors.newFixedThreadPool(12);
            try {
                List<Future<?>> futures = new ArrayList<>();
                for (int i = 0; i < 60; i++) {
                    QuotaStore store = i % 2 == 0 ? one : two;
                    futures.add(workers.submit(() -> {
                        try { store.reserve(OperationClass.A); accepted.incrementAndGet(); }
                        catch (QuotaStore.QuotaBlocked expected) { }
                        catch (Exception failure) { throw new RuntimeException(failure); }
                    }));
                }
                for (Future<?> f : futures) f.get();
            } finally { workers.shutdownNow(); }
            check(accepted.get() == 20, "concurrent connections exceeded the Class A limit");
            one.reserve(OperationClass.B);
            check(used(one, "class_b") == 1, "Class A incorrectly consumed Class B");
        }
        try (QuotaStore reopened = new QuotaStore(c, clock)) {
            blocked(() -> reopened.reserve(OperationClass.A));
            check(used(reopened, "class_a") == 20, "restart lost committed reservations");
        }
        System.out.println("PASS: atomic concurrent limits, separate A/B budgets and persistence");
    }

    private static void reconciliation(Path root) throws Exception {
        Map<String, String> e = env(root.resolve("sync.db"));
        e.put("R2_QUOTA_CLASS_A_LIMIT", "100"); e.put("R2_QUOTA_CLASS_B_LIMIT", "100");
        MovingClock clock = new MovingClock("2026-10-10T10:00:00Z");
        try (QuotaStore store = new QuotaStore(new QuotaConfig(e), clock)) {
            for (int i = 0; i < 3; i++) store.reserve(OperationClass.A);
            for (int i = 0; i < 2; i++) store.reserve(OperationClass.B);
            BillingPeriod p = store.period(); Instant cutoff = Instant.parse("2026-10-10T10:01:00Z");
            clock.now = Instant.parse("2026-10-10T10:02:00Z");
            store.reserve(OperationClass.A); store.reserve(OperationClass.A); store.reserve(OperationClass.B);
            store.reconcile(p, cutoff, 10, 20);
            check(used(store, "class_a") == 12 && used(store, "class_b") == 21, "sync discarded requests after the cutoff");
            store.reconcile(p, cutoff, 10, 20);
            check(used(store, "class_a") == 12, "repeated sync double counted cloud usage");
            store.reconcile(p, cutoff, 1, 1);
            check(used(store, "class_a") == 12 && used(store, "class_b") == 21, "lower cloud estimate restored quota");
            clock.now = Instant.parse("2026-10-10T10:04:00Z");
            store.reconcile(p, Instant.parse("2026-10-10T10:03:00Z"), 12, 21);
            check(used(store, "class_a") == 12 && used(store, "class_b") == 21, "later sync double counted local requests");
        }
        System.out.println("PASS: cloud cutoffs, idempotent sync and monotonic adjustments");
    }

    private static void rollover(Path root) throws Exception {
        Map<String, String> e = env(root.resolve("rollover.db"));
        e.put("R2_QUOTA_REQUIRE_INITIAL_SYNC", "true");
        QuotaConfig c = new QuotaConfig(e); MovingClock clock = new MovingClock("2026-10-26T23:58:00Z");
        try (QuotaStore store = new QuotaStore(c, clock)) {
            blocked(() -> store.reserve(OperationClass.A));
            store.reconcile(store.period(), clock.instant(), 2, 3); store.reserve(OperationClass.A);
            clock.now = Instant.parse("2026-10-27T00:00:00Z");
            check(store.period().start.equals(clock.instant()), "billing day did not roll over on the 27th");
            blocked(() -> store.reserve(OperationClass.A));
            store.reconcile(store.period(), clock.instant(), 0, 0); store.reserve(OperationClass.A);
            check(used(store, "class_a") == 1, "new billing period retained old usage");
        }
        e.put("R2_QUOTA_BILLING_DAY", "1");
        fails(() -> { try (QuotaStore ignored = new QuotaStore(new QuotaConfig(e), clock)) { } });
        Map<String, String> manual = env(root.resolve("manual.db"));
        manual.remove("R2_QUOTA_REQUIRE_INITIAL_SYNC");
        check(!new QuotaConfig(manual).requireSync, "disabling sync left the initial-sync gate permanently closed");
        BillingPeriod february = BillingPeriod.current(Instant.parse("2026-02-28T00:00:00Z"), 31, ZoneOffset.UTC);
        check(february.start.equals(Instant.parse("2026-02-28T00:00:00Z")), "short-month boundary failed");
        System.out.println("PASS: initial sync gate, billing rollover and calendar-change protection");
    }

    private static void analyticsParsing() throws Exception {
        String body = new ObjectMapper().writeValueAsString(Map.of("data", Map.of("viewer", Map.of("accounts", List.of(
            Map.of("r2OperationsAdaptiveGroups", List.of(
                group("ListObjects", 5), group("GetObject", 8.2), group("DeleteObject", 50),
                group("GetBucketNotificationConfiguration", 3), group("GetBucketSippyConfiguration", 5))))))));
        long[] counts = CloudflareSync.parse(body);
        check(counts[0] == 13 && counts[1] == 17, "analytics classification/rounding/unknown-operation reserve failed");
        fails(() -> CloudflareSync.parse("{\"errors\":[{\"message\":\"denied\"}]}"));
        fails(() -> CloudflareSync.parse("{\"data\":{\"viewer\":{\"accounts\":[]}}}"));
        fails(() -> OperationClass.classify("UnknownNewOperation"));
        check(OperationClass.classify("CreateBucketRequest") == OperationClass.A, "SDK operation alias failed");
        System.out.println("PASS: analytics validation, conservative rounding and unknown-operation denial");
    }

    private static Map<String, Object> group(String action, Number count) {
        return Map.of("dimensions", Map.of("actionType", action), "sum", Map.of("requests", count));
    }

    @SuppressWarnings("deprecation")
    private static void sdkTransmission(Path root) throws Exception {
        AtomicInteger heads = new AtomicInteger(), pages = new AtomicInteger(), all = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            all.incrementAndGet();
            if (exchange.getRequestMethod().equals("HEAD")) {
                int number = heads.incrementAndGet();
                exchange.sendResponseHeaders(number == 1 ? 503 : 200, -1);
            } else {
                int number = pages.incrementAndGet();
                String xml = "<?xml version=\"1.0\"?><ListBucketResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">"
                    + "<Name>bucket</Name><KeyCount>0</KeyCount><MaxKeys>1000</MaxKeys><IsTruncated>"
                    + (number == 1 ? "true" : "false") + "</IsTruncated>"
                    + (number == 1 ? "<NextContinuationToken>next</NextContinuationToken>" : "") + "</ListBucketResult>";
                byte[] bytes = xml.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/xml");
                exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.start();
        Map<String, String> e = env(root.resolve("sdk.db"));
        e.put("R2_ENDPOINT", "http://127.0.0.1:" + server.getAddress().getPort());
        e.put("R2_QUOTA_CLASS_A_LIMIT", "2"); e.put("R2_QUOTA_CLASS_B_LIMIT", "3");
        QuotaRuntime runtime = QuotaRuntime.start(new QuotaConfig(e), Clock.systemUTC());
        try (S3Client s3 = S3Client.builder().endpointOverride(URI.create(e.get("R2_ENDPOINT")))
                .region(Region.US_EAST_1).credentialsProvider(AnonymousCredentialsProvider.create())
                .forcePathStyle(true).httpClientBuilder(UrlConnectionHttpClient.builder())
                .overrideConfiguration(c -> c.retryPolicy(RetryPolicy.builder().numRetries(2).build())).build()) {
            check(new ClasspathInterceptorChainFactory().getGlobalInterceptors().stream()
                .anyMatch(i -> i instanceof R2QuotaInterceptor), "global interceptor was not loaded");
            s3.headObject(r -> r.bucket("bucket").key("file"));
            check(heads.get() == 2 && used(runtime.store, "class_b") == 2, "SDK retry was not counted per transmission");
            s3.headObject(r -> r.bucket("bucket").key("file"));
            int sent = all.get();
            fails(() -> s3.headObject(r -> r.bucket("bucket").key("file")));
            check(all.get() == sent && heads.get() == 3, "quota rejection reached HTTP transport");
            check(s3.listObjectsV2Paginator(r -> r.bucket("bucket")).stream().count() == 2, "pagination fixture failed");
            check(pages.get() == 2 && used(runtime.store, "class_a") == 2, "SDK pagination bypassed Class A quota");
            sent = all.get(); fails(() -> s3.listObjectsV2(r -> r.bucket("bucket")));
            fails(() -> s3.getBucketAcl(r -> r.bucket("bucket")));
            check(all.get() == sent, "blocked or unclassified operation reached HTTP transport");
            runtime.store.close();
            fails(() -> s3.headObject(r -> r.bucket("bucket").key("file")));
            check(all.get() == sent, "database failure allowed an R2 transmission");
        } finally { server.stop(0); }
        System.out.println("PASS: real SDK global loading, retries, pagination and fail-closed transmission");
    }

    private static long used(QuotaStore store, String type) throws Exception {
        return ((Number) ((Map<?, ?>) store.status().get(type)).get("used")).longValue();
    }
    private static void blocked(Action action) throws Exception {
        try { action.run(); } catch (QuotaStore.QuotaBlocked expected) { return; }
        throw new AssertionError("Expected quota rejection");
    }
    private static void fails(Action action) throws Exception {
        try { action.run(); } catch (Exception expected) { return; }
        throw new AssertionError("Expected fail-closed rejection");
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private interface Action { void run() throws Exception; }
    private static final class MovingClock extends Clock {
        Instant now;
        MovingClock(String value) { now = Instant.parse(value); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
