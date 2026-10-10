package com.frostbite.reposilite.quota;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;

public final class QuotaMain {
    public static void main(String[] args) throws Exception {
        if (args.length != 1 || (!args[0].equals("status") && !args[0].equals("sync"))) {
            System.err.println("Usage: quota-control status|sync"); System.exit(2); return;
        }
        QuotaConfig config = new QuotaConfig(System.getenv());
        try (QuotaStore store = new QuotaStore(config, Clock.systemUTC())) {
            if (args[0].equals("sync")) new CloudflareSync(config, store, Clock.systemUTC()).sync();
            System.out.println(new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(store.status()));
        }
    }
}
