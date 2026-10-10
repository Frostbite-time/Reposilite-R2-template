package com.frostbite.reposilite.quota;

import software.amazon.awssdk.core.exception.NonRetryableException;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.services.s3.model.S3Request;

/** Discovered by the AWS SDK from the global execution.interceptors resource. */
public final class R2QuotaInterceptor implements ExecutionInterceptor {
    public R2QuotaInterceptor() { }

    @Override public void beforeTransmission(Context.BeforeTransmission context, ExecutionAttributes attributes) {
        if (!(context.request() instanceof S3Request)) return;
        QuotaRuntime runtime;
        try { runtime = QuotaRuntime.current(); }
        catch (IllegalStateException e) { throw NonRetryableException.create(e.getMessage(), e); }
        if (!runtime.config.enabled) return;
        String host = context.httpRequest().host();
        String expected = runtime.config.endpointHost;
        if (!host.equalsIgnoreCase(expected) && !host.toLowerCase(java.util.Locale.ROOT)
                .endsWith("." + expected.toLowerCase(java.util.Locale.ROOT))) return;
        try {
            runtime.store.reserve(OperationClass.classify(context.request().getClass().getSimpleName()));
        } catch (Exception e) {
            runtime.logBlocked("R2 request blocked: " + e.getMessage());
            // Quota failures must not cause SDK retries or reach the R2 endpoint.
            throw NonRetryableException.create("R2 quota guard: " + e.getMessage(), e);
        }
    }
}
