package com.frostbite.reposilite.quota;

import java.time.Clock;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

final class QuotaRuntime {
    private static volatile QuotaRuntime current;
    final QuotaConfig config;
    final QuotaStore store;
    private long lastErrorLog;

    private QuotaRuntime(QuotaConfig config, Clock clock) throws Exception {
        this.config = config;
        store = config.enabled ? new QuotaStore(config, clock) : null;
    }

    static synchronized QuotaRuntime start(QuotaConfig config, Clock clock) throws Exception {
        if (current != null) throw new IllegalStateException("Quota runtime already started");
        QuotaRuntime runtime = new QuotaRuntime(config, clock);
        current = runtime;
        if (config.enabled) {
            System.out.println("[r2-quota] Enabled: A=" + config.limitA + " B=" + config.limitB
                + " billing_day=" + config.billingDay + " timezone=" + config.billingZone);
            if (config.syncEnabled) {
                CloudflareSync sync = new CloudflareSync(config, runtime.store, clock);
                Runnable task = () -> {
                    try { if (runtime.store.syncDue()) sync.sync(); }
                    catch (Exception e) { runtime.logBlocked("Cloudflare sync failed: " + e.getMessage()); }
                };
                task.run();
                ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "r2-quota-cloud-sync"); t.setDaemon(true); return t;
                });
                scheduler.scheduleWithFixedDelay(task, 60, 60, TimeUnit.SECONDS);
            }
        } else System.out.println("[r2-quota] WARNING: quota protection explicitly disabled");
        return runtime;
    }

    static QuotaRuntime current() {
        QuotaRuntime runtime = current;
        if (runtime == null) throw new IllegalStateException("QuotaLauncher must initialize the R2 quota guard before Reposilite");
        return runtime;
    }

    synchronized void logBlocked(String message) {
        long now = System.currentTimeMillis();
        if (now - lastErrorLog >= 60_000) {
            System.err.println("[r2-quota] " + message); lastErrorLog = now;
        }
    }
}
