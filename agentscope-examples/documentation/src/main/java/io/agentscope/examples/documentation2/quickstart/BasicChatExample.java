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
 *   <li>Wiring OpenTelemetry tracing via {@link OtelTracingMiddleware} alongside the official
 *       OpenTelemetry Java Agent — the agent owns the SDK, OTLP exporter, and HTTP/gRPC/DB
 *       instrumentation; this class only adds the AgentScope business-layer middleware</li>
 * </ul>
 *
 * <p>Spans produced: {@code invoke_agent Assistant}, {@code chat <modelName>} (per model call),
 * {@code execute_tool <name>} (per tool call). The Java Agent contributes HTTP-client spans
 * (OkHttp → DashScope) underneath; both share the same {@code GlobalOpenTelemetry} so the
 * trace tree is joined automatically. Reactor context propagation is supplied by
 * {@code ContextPropagationOperator}, registered by the middleware on first use.
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
 *   # 3. Run via mvn (javaagent flag passed through exec:exec)
 *   mvn -pl agentscope-examples/documentation exec:exec \
 *       -Dexec.executable=java \
 *       -Dexec.args="-javaagent:$(pwd)/opentelemetry-javaagent.jar -classpath %classpath io.agentscope.examples.documentation2.quickstart.BasicChatExample"
 *
 *   # Equivalent direct java invocation:
 *   # java -javaagent:opentelemetry-javaagent.jar \
 *   #      -cp "$(mvn -q dependency:build-classpath -Dmdep.outputFile=/dev/stdout -pl agentscope-examples/documentation):agentscope-examples/documentation/target/classes" \
 *   #      io.agentscope.examples.documentation2.quickstart.BasicChatExample
 * </pre>
 */
public class BasicChatExample {

    public static void main(String[] args) throws Exception {
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("Error: DASHSCOPE_API_KEY environment variable not set.");
            System.err.println("Get your API key from: https://dashscope.aliyun.com");
            System.err.println("Then set it with: export DASHSCOPE_API_KEY=your_api_key");
            System.exit(1);
        }

        System.out.println("\n" + "=".repeat(60));
        System.out.println("Basic Chat Example (OpenTelemetry via Java Agent)");
        System.out.println("=".repeat(60));
        System.out.println(
                "OTel endpoint: "
                        + System.getenv()
                                .getOrDefault("OTEL_EXPORTER_OTLP_ENDPOINT", "(agent default)"));
        System.out.println(
                "OTel service:  "
                        + System.getenv().getOrDefault("OTEL_SERVICE_NAME", "(agent default)"));
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
}
