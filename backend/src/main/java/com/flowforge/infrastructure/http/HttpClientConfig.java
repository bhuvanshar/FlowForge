package com.flowforge.infrastructure.http;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The outbound HTTP client: guarded DNS, no automatic redirects (each hop is checked by the executor), no cookies, no
 * retries, no proxy settings from the environment, no auth caching.
 */
@Configuration
@EnableConfigurationProperties(HttpProperties.class)
public class HttpClientConfig {

    @Bean(destroyMethod = "close")
    public CloseableHttpClient workflowHttpClient(HttpProperties properties, OutboundRequestPolicy policy) {
        PoolingHttpClientConnectionManager connections = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(new GuardedDnsResolver(policy))
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.of(properties.connectTimeout()))
                        .build())
                .setMaxConnTotal(50)
                .setMaxConnPerRoute(10)
                .build();
        return HttpClients.custom()
                .setConnectionManager(connections)
                .disableRedirectHandling()
                .disableCookieManagement()
                .disableAutomaticRetries()
                .disableAuthCaching()
                .setUserAgent("FlowForge")
                .build();
    }

    /** Aborts in-flight requests on node timeout or cancellation (blocking socket I/O ignores thread interrupts). */
    @Bean(destroyMethod = "shutdownNow")
    public ScheduledExecutorService httpWatchdog() {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "ff-http-watchdog");
            thread.setDaemon(true);
            return thread;
        });
    }
}
