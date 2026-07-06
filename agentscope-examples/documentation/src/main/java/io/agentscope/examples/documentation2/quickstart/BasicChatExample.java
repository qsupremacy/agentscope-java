/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.examples.documentation2.quickstart;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tracing.OtelTracingMiddleware;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * BasicChatExample - The simplest Agent conversation example.
 *
 * <p>Demonstrates:
 * <ul>
 *   <li>Creating an agent with {@code model("dashscope:qwen-plus")} (ModelRegistry auto-resolves
 *       the provider and reads API key from env)</li>
 *   <li>Interactive streaming chat via {@code streamEvents()}</li>
 *   <li>Incremental text output using {@link TextBlockDeltaEvent}</li>
 *   <li>Wiring OpenTelemetry tracing via {@link OtelTracingMiddleware} and the OTLP HTTP
 *       exporter (default endpoint {@code http://localhost:4318})</li>
 * </ul>
 *
 * <p>Spans produced: {@code invoke_agent Assistant}, {@code chat <modelName>} (per model call),
 * {@code execute_tool <name>} (per tool call). Reactor context is propagated by
 * {@code ContextPropagationOperator}, which the middleware registers on first use.
 *
 * <p><b>Run:</b>
 * <pre>
 *   # 1. Start a collector that listens on :4318 (HTTP), e.g.:
 *   docker run --rm -p 4318:4318 -p 4317:4317 \
 *     -v "$PWD/otel-collector.yaml":/etc/otelcol/config.yaml \
 *     otel/opentelemetry-collector-contrib:0.96.0
 *
 *   # 2. Set the key and run:
 *   export DASHSCOPE_API_KEY=your_key
 *   mvn exec:java -pl agentscope-examples/documentation \
 *       -Dexec.mainClass=io.agentscope.examples.documentation2.quickstart.BasicChatExample
 * </pre>
 */
public class BasicChatExample {

    private static final String OTLP_ENDPOINT = "http://localhost:4318/v1/traces";
    private static final String SERVICE_NAME = "basic-chat-example";

    public static void main(String[] args) throws Exception {
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("Error: DASHSCOPE_API_KEY environment variable not set.");
            System.err.println("Get your API key from: https://dashscope.aliyun.com");
            System.err.println("Then set it with: export DASHSCOPE_API_KEY=your_api_key");
            System.exit(1);
        }

        OpenTelemetrySdk otelSdk = initOpenTelemetry(OTLP_ENDPOINT);
        Runtime.getRuntime().addShutdownHook(new Thread(otelSdk::close));

        System.out.println("\n" + "=".repeat(60));
        System.out.println("Basic Chat Example (with OpenTelemetry tracing)");
        System.out.println("=".repeat(60));
        System.out.println("OTLP endpoint: " + OTLP_ENDPOINT);
        System.out.println("A simple interactive chat with streaming output.");
        System.out.println("Type 'exit' to quit.\n");

        ReActAgent agent =
                ReActAgent.builder()
                        .name("Assistant")
                        .sysPrompt("You are a helpful AI assistant. Be friendly and concise.")
                        .model("dashscope:qwen-plus")
                        .toolkit(new Toolkit())
                        .middleware(new OtelTracingMiddleware())
                        .build();

        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));

        while (true) {
            System.out.print("You: ");
            String input = reader.readLine();

            if (input == null || input.trim().equalsIgnoreCase("exit")) {
                System.out.println("\nGoodbye!");
                break;
            }
            if (input.isBlank()) {
                continue;
            }

            Msg userMsg = new UserMessage(input.trim());

            System.out.print("\nAssistant: ");
            agent.streamEvents(userMsg)
                    .doOnNext(
                            event -> {
                                if (event instanceof TextBlockDeltaEvent e) {
                                    System.out.print(e.getDelta());
                                }
                            })
                    .blockLast();
            System.out.println("\n");
        }
    }

    private static OpenTelemetrySdk initOpenTelemetry(String otlpEndpoint) {
        Resource resource =
                Resource.getDefault()
                        .merge(
                                Resource.create(
                                        Attributes.of(
                                                AttributeKey.stringKey("service.name"),
                                                SERVICE_NAME)));
        SpanExporter otlpExporter =
                OtlpHttpSpanExporter.builder().setEndpoint(otlpEndpoint).build();
        SdkTracerProvider tracerProvider =
                SdkTracerProvider.builder()
                        .addSpanProcessor(SimpleSpanProcessor.create(otlpExporter))
                        .setResource(resource)
                        .build();
        return OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .buildAndRegisterGlobal();
    }
}
