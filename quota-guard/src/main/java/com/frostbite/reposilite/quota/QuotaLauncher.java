package com.frostbite.reposilite.quota;

import java.lang.reflect.InvocationTargetException;
import java.time.Clock;
import java.util.jar.JarFile;
import software.amazon.awssdk.core.interceptor.ClasspathInterceptorChainFactory;

public final class QuotaLauncher {
    public static void main(String[] args) throws Exception {
        QuotaRuntime runtime = QuotaRuntime.start(new QuotaConfig(System.getenv()), Clock.systemUTC());
        boolean loaded = new ClasspathInterceptorChainFactory().getGlobalInterceptors().stream()
            .anyMatch(i -> i instanceof R2QuotaInterceptor);
        if (runtime.config.enabled && !loaded) throw new IllegalStateException("AWS SDK did not load the R2 quota interceptor");
        if (loaded) System.out.println("[r2-quota] AWS SDK global interceptor verified");
        String mainClass;
        try (JarFile jar = new JarFile("/app/reposilite.jar")) {
            mainClass = jar.getManifest().getMainAttributes().getValue("Main-Class");
        }
        try { Class.forName(mainClass).getMethod("main", String[].class).invoke(null, (Object) args); }
        catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception) throw (Exception) e.getCause();
            if (e.getCause() instanceof Error) throw (Error) e.getCause();
            throw e;
        }
    }
}
