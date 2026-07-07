/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.agentscope.examples.documentation2.quickstart;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Access log filter — logs one line per HTTP request on completion, including method, path,
 * status, duration, and remote address.
 *
 * <p>Wired automatically by Spring as a {@code @Component}. Uses a dedicated SLF4J logger named
 * {@code ACCESS_LOG} so access logs can be routed to a separate appender if desired.
 */
@Component
public class AccessLogFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger("ACCESS_LOG");
    private static final String START_TIME_ATTR = "AccessLogFilter.startNanos";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        exchange.getAttributes().put(START_TIME_ATTR, System.nanoTime());

        return chain.filter(exchange).then(Mono.fromRunnable(() -> writeAccessLog(exchange)));
    }

    private static void writeAccessLog(ServerWebExchange exchange) {
        Long startNanos = exchange.getAttribute(START_TIME_ATTR);
        long durationMs = startNanos == null ? -1L : (System.nanoTime() - startNanos) / 1_000_000L;

        ServerHttpRequest request = exchange.getRequest();
        HttpStatusCode status = exchange.getResponse().getStatusCode();
        String method = request.getMethod() != null ? request.getMethod().name() : "-";
        String path = request.getPath().value();
        String remote =
                request.getRemoteAddress() != null
                        ? request.getRemoteAddress().getAddress().getHostAddress()
                        : "-";

        log.info("{} {} -> {} ({} ms) from {}", method, path, status, durationMs, remote);
    }
}
