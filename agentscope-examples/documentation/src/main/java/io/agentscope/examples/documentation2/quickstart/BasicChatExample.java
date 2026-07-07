/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.examples.documentation2.quickstart;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tracing.OtelTracingMiddleware;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * BasicChatExample - The simplest Agent exposed as a Spring Web server.
 *
 * <p>Demonstrates:
 * <ul>
 *   <li>Bootstrapping AgentScope on Spring WebFlux with a single {@code @SpringBootApplication}
 *       class and a nested {@code @RestController}</li>
 *   <li>Shared {@link Model} bean (the HTTP client is safe to share across concurrent requests)
 *       paired with per-request {@link ReActAgent} instances — required because ReActAgent is
 *       not thread-safe (see {@code ReActAgent} Javadoc)</li>
 *   <li>Two response shapes: synchronous JSON ({@code POST /chat}) and Server-Sent Events
 *       ({@code POST /chat/stream}), both driven by {@code agent.streamEvents(...)}</li>
 *   <li>OpenTelemetry tracing via {@link OtelTracingMiddleware} (one per request) plus the
 *       official OpenTelemetry Java Agent — the agent supplies the SDK and OTLP exporter; the
 *       middleware produces the AgentScope business-layer spans</li>
 * </ul>
 *
 * <p>Spans produced: {@code invoke_agent WebAssistant}, {@code chat qwen-plus} (per model call),
 * and HTTP client spans from the Java Agent. All share the same trace id via
 * {@code GlobalOpenTelemetry}.
 *
 * <p><b>Run with the OpenTelemetry Java Agent:</b>
 * <pre>
 *   # 0. One-off: download the agent jar into the project root
 *   curl -L -O \
 *     https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/latest/download/opentelemetry-javaagent.jar
 *
 *   # 1. Start an OTel collector that listens on :4317 (gRPC) and :4318 (HTTP)
 *   docker run -d --name otel-collector \
 *     -p 4317:4317 -p 4318:4318 \
 *     -v "$PWD/otel-collector.yaml":/etc/otelcol/config.yaml \
 *     otel/opentelemetry-collector-contrib:0.114.0
 *
 *   # 2. Set the API key and exporter endpoint (gRPC recommended)
 *   export DASHSCOPE_API_KEY=your_key
 *   export OTEL_SERVICE_NAME=basic-chat-example
 *   export OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317
 *   export OTEL_EXPORTER_OTLP_PROTOCOL=grpc
 *
 *   # 3. Run via run.sh (which attaches the javaagent via spring-boot:run)
 *   bash /data/disk/agentscope-java/agentscope-examples/run.sh
 *
 *   # 4. Exercise the endpoints
 *   curl localhost:8080/health
 *   curl -X POST localhost:8080/chat \
 *        -H 'Content-Type: application/json' \
 *        -d '{"message":"Hello","userId":"u1","sessionId":"s1"}'
 *   curl -N -X POST localhost:8080/chat/stream \
 *        -H 'Content-Type: application/json' \
 *        -d '{"message":"Tell me a story"}'
 * </pre>
 */
@SpringBootApplication
public class BasicChatExample {

    public static void main(String[] args) {
        SpringApplication.run(BasicChatExample.class, args);
    }

    /** REST controller exposing chat endpoints over HTTP. */
    @RestController
    public static class ChatController implements InitializingBean {

        private Model model;

        @Override
        public void afterPropertiesSet() {
            String apiKey = System.getenv("DASHSCOPE_API_KEY");
            if (apiKey == null || apiKey.isBlank()) {
                throw new IllegalStateException(
                        "DASHSCOPE_API_KEY environment variable is required");
            }
            this.model =
                    DashScopeChatModel.builder().apiKey(apiKey).modelName("qwen-plus").stream(true)
                            .build();

            System.out.println("\n=== BasicChatExample (Spring Web) Started ===");
            System.out.println("Server running at: http://localhost:8080");
            System.out.println("\nTry:");
            System.out.println("  curl localhost:8080/health");
            System.out.println(
                    "  curl -X POST localhost:8080/chat"
                            + " -H 'Content-Type: application/json'"
                            + " -d '{\"message\":\"Hello\"}'");
            System.out.println(
                    "  curl -N -X POST localhost:8080/chat/stream"
                            + " -H 'Content-Type: application/json'"
                            + " -d '{\"message\":\"Tell me a story\"}'");
            System.out.println("\nPress Ctrl+C to stop.\n");
        }

        public record ChatRequest(String message, String userId, String sessionId) {}

        public record ChatResponse(String reply, String replyId) {}

        /** Liveness probe. */
        @GetMapping("/health")
        public String health() {
            return "OK";
        }

        /**
         * Synchronous chat — returns the full reply as JSON once the agent finishes.
         */
        @PostMapping(value = "/chat", produces = MediaType.APPLICATION_JSON_VALUE)
        public Mono<ChatResponse> chat(@RequestBody ChatRequest req) {
            ReActAgent agent = newRequestAgent();
            RuntimeContext ctx = buildRuntimeContext(req);

            return agent.call(req.message(), ctx)
                    .map(reply -> new ChatResponse(reply.getTextContent(), null));
        }

        /**
         * Streaming chat — emits one text delta per Server-Sent Event.
         */
        @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        public Flux<String> chatStream(@RequestBody ChatRequest req) {
            ReActAgent agent = newRequestAgent();
            RuntimeContext ctx = buildRuntimeContext(req);

            return agent.streamEvents(new UserMessage(req.message()), ctx)
                    .subscribeOn(Schedulers.boundedElastic())
                    .filter(event -> event instanceof TextBlockDeltaEvent)
                    .map(event -> ((TextBlockDeltaEvent) event).getDelta());
        }

        // ------------------------------------------------------------------
        // helpers
        // ------------------------------------------------------------------

        /**
         * Builds a fresh {@link ReActAgent} for this request. ReActAgent is not thread-safe —
         * creating one per request is the recommended pattern (see {@code ReActAgent} Javadoc
         * and {@code StreamingWebExample}). The shared {@link Model} bean is safe to reuse across
         * agents; {@link Toolkit} is deep-copied at {@code build()} time.
         */
        private ReActAgent newRequestAgent() {
            return ReActAgent.builder()
                    .name("WebAssistant")
                    .sysPrompt("You are a helpful AI assistant. Be friendly and concise.")
                    .model(model)
                    .toolkit(new Toolkit())
                    .middleware(new OtelTracingMiddleware())
                    .build();
        }

        private static RuntimeContext buildRuntimeContext(ChatRequest req) {
            return RuntimeContext.builder()
                    .userId(req.userId() != null ? req.userId() : "anonymous")
                    .sessionId(req.sessionId() != null ? req.sessionId() : "default")
                    .build();
        }
    }
}
