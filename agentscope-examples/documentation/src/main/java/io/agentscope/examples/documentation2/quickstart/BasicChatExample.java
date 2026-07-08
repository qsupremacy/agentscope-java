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
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.ClasspathSkillRepository;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
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
 * BasicChatExample - A navigation-capable Agent exposed as a Spring Web server.
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
 *   <li>AMap (高德地图) MCP tools via Streamable HTTP transport — registered once at startup
 *       on a shared {@link Toolkit} (all ~15 AMap-exposed tools; the system prompt steers the
 *       LLM toward the navigation-specific ones)
 *   <li>OpenTelemetry tracing via {@link OtelTracingMiddleware} (one per request) plus the
 *       official OpenTelemetry Java Agent — the agent supplies the SDK and OTLP exporter; the
 *       middleware produces the AgentScope business-layer spans</li>
 * </ul>
 *
 * <p>Spans produced: {@code invoke_agent NavigationAssistant}, {@code chat qwen-plus} (per model
 * call), {@code execute_tool maps_direction_driving} (per tool call), and HTTP client spans from
 * the Java Agent (including one for the MCP call to {@code mcp.amap.com}). All share the same
 * trace id via {@code GlobalOpenTelemetry}.
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
 *   # 2. Set the API keys and exporter endpoint (gRPC recommended)
 *   export DASHSCOPE_API_KEY=your_key
 *   export AMAP_API_KEY=your_amap_mcp_key      # from https://lbs.amap.com/ → MCP
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
 *        -d '{"message":"从北京西直门地铁站到北京首都机场怎么去","userId":"u1","sessionId":"s1"}'
 *   curl -X POST localhost:8080/chat \
 *        -H 'Content-Type: application/json' \
 *        -d '{"message":"从 121.4737,31.2304 到 121.4756,31.2244 驾车怎么走"}'
 *   curl -N -X POST localhost:8080/chat/stream \
 *        -H 'Content-Type: application/json' \
 *        -d '{"message":"从人民广场步行去陆家嘴要多久?"}'
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
        private Toolkit toolkit;
        private AgentSkillRepository skillRepository;

        @Override
        public void afterPropertiesSet() {
            String apiKey = System.getenv("DASHSCOPE_API_KEY");
            if (apiKey == null || apiKey.isBlank()) {
                throw new IllegalStateException(
                        "DASHSCOPE_API_KEY environment variable is required");
            }
            this.model =
                    DashScopeChatModel.builder().apiKey(apiKey).modelName("qwen-max").stream(true)
                            .build();

            // AMap MCP server via Streamable HTTP (optional — app still works without it).
            // API key is sent as a query parameter; the MCP SDK's streamableHttpTransport uses the
            // URL path+query verbatim and does not append its own endpoint, so no path duplication.
            String amapKey = System.getenv("AMAP_API_KEY");
            this.toolkit = new Toolkit();
            if (amapKey != null && !amapKey.isBlank()) {
                System.out.println("Connecting to AMap MCP server via Streamable HTTP...");
                McpClientWrapper mcpClient =
                        McpClientBuilder.create("amap-maps")
                                .streamableHttpTransport("https://mcp.amap.com/mcp")
                                .queryParam("key", amapKey)
                                .buildAsync()
                                .block();
                this.toolkit.registerMcpClient(mcpClient).block();
                System.out.println("AMap MCP tools registered: " + this.toolkit.getToolNames());
            } else {
                System.out.println("AMAP_API_KEY not set — running without MCP tools (chat only).");
            }

            // Skills via ClasspathSkillRepository (optional — app still works without them).
            // Loads SKILL.md entries from src/main/resources/skills/ on the classpath.
            // Bundled skills include `summarizer` and `data-analysis`.
            try {
                ClasspathSkillRepository repo = new ClasspathSkillRepository("skills");
                if (!repo.getAllSkillNames().isEmpty()) {
                    this.skillRepository = repo;
                    System.out.println(
                            "Loaded skills: " + String.join(", ", repo.getAllSkillNames()));
                } else {
                    System.out.println("No skills found in classpath:skills (skipping).");
                }
            } catch (Exception e) {
                System.out.println("Skill repository unavailable (skipping): " + e.getMessage());
            }

            System.out.println("\n=== BasicChatExample (Spring Web) Started ===");
            System.out.println("Server running at: http://localhost:8080");
            System.out.println("\nTry:");
            System.out.println("  curl localhost:8080/health");
            System.out.println(
                    "  curl -X POST localhost:8080/chat"
                            + " -H 'Content-Type: application/json'"
                            + " -d '{\"message\":\"Hello\"}'");
            System.out.println(
                    "  curl -X POST localhost:8080/chat"
                            + " -H 'Content-Type: application/json'"
                            + " -d '{\"message\":\"从北京西直门地铁站到北京首都机场怎么去\"}'");
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
         *
         * <p>Uses {@code streamEvents(...)} under the hood and accumulates {@link
         * TextBlockDeltaEvent} deltas into a single string. This is more robust than
         * {@code agent.call(...)} + {@code getTextContent()}, which returns "" when the LLM
         * produces tool calls or thinking without an accompanying final text block (the
         * returned Msg may then have only {@code ToolUseBlock}/{@code ThinkingBlock} content
         * and no {@code TextBlock}). Streaming collects whatever the model emits.
         *
         * <p>Falls back to any text inside {@code ToolResultBlock}s if no deltas were emitted.
         */
        @PostMapping(value = "/chat", produces = MediaType.APPLICATION_JSON_VALUE)
        public Mono<ChatResponse> chat(@RequestBody ChatRequest req) {
            ReActAgent agent = newRequestAgent();
            RuntimeContext ctx = buildRuntimeContext(req);

            StringBuilder text = new StringBuilder();
            StringBuilder diag = new StringBuilder();
            final boolean[] toolCalled = {false};

            return agent.streamEvents(new UserMessage(req.message()), ctx)
                    .subscribeOn(Schedulers.boundedElastic())
                    .doOnNext(
                            event -> {
                                diag.append(event.getClass().getSimpleName()).append(" | ");
                                if (event instanceof TextBlockDeltaEvent delta) {
                                    text.append(delta.getDelta());
                                } else if (event
                                        instanceof io.agentscope.core.event.ToolCallStartEvent) {
                                    toolCalled[0] = true;
                                }
                            })
                    .collectList()
                    .map(
                            events -> {
                                String reply = text.toString();
                                System.err.println(
                                        "[DEBUG chat] toolCalled="
                                                + toolCalled[0]
                                                + " deltaLen="
                                                + reply.length()
                                                + " events="
                                                + diag);
                                return new ChatResponse(reply, null);
                            });
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
         * and {@code StreamingWebExample}). The shared {@link Model} bean and MCP-loaded
         * {@link Toolkit} are safe to reuse across agents; the Toolkit is shallow-copied at
         * {@code build()} time so per-request mutation (if any) doesn't leak across requests.
         */
        private ReActAgent newRequestAgent() {
            String toolsList =
                    toolkit != null ? String.join(", ", toolkit.getToolNames()) : "(none)";
            System.err.println("[DEBUG agent-build] toolkit=[" + toolsList + "]");
            // BYPASS permission mode so MCP tools (which default to ASK on every non-read-only
            // call — see McpTool#checkPermissions) execute without pausing for HITL confirmation.
            // Acceptable here because this example only registers read-only navigation tools.
            return ReActAgent.builder()
                    .name("NavigationAssistant")
                    .sysPrompt(DEFAULT_SYSTEM_PROMPT)
                    .model(model)
                    .toolkit(toolkit != null ? toolkit : new Toolkit())
                    .permissionContext(
                            PermissionContextState.builder().mode(PermissionMode.BYPASS).build())
                    .skillRepository(skillRepository) // may be null if no skills loaded
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

    /**
     * Chinese navigation assistant prompt ported from the Python reference
     * {@code /data/disk/agent-demo/navagent/agent.py:58-64}. It steers the LLM toward the
     * navigation tools exposed by the AMap MCP server (registered at startup).
     */
    private static final String DEFAULT_SYSTEM_PROMPT =
            "你是一个专业的出行助手,可以调用高德地图 MCP 工具为用户提供驾车/步行/骑行路线规划服务。\n"
                    + "\n"
                    + "重要规则：\n"
                    + "1. 用户给的是地名(如\"西直门地铁站\"、\"人民广场\"、\"北京首都机场\")时,"
                    + "先用 maps_geo 把每个地名解析成经纬度。maps_geo 接收一个地址列表,"
                    + "每个返回的 location 字段就是 \"经度,纬度\" 格式的字符串。\n"
                    + "2. 拿到坐标后再调用 maps_direction_driving / maps_direction_walking /"
                    + " maps_direction_bicycling(origin=\"起点经纬度\", destination=\"终点经纬度\")。"
                    + "参数格式必须是 \"经度,纬度\"(逗号分隔,经度在前)。例如:121.4737,31.2304。\n"
                    + "3. 如果用户直接给了经纬度,跳过 maps_geo,直接调路径规划工具。\n"
                    + "4. 如果 maps_geo 解析失败(返回空 location 或报错),告诉用户没找到,"
                    + "请他/她换个写法或直接给经纬度,不要瞎猜坐标。\n"
                    + "5. 拿到路径规划结果后,必须用人话总结给用户:包括距离、预计时间、途经主要道路等,"
                    + "不能只贴原始 JSON。\n"
                    + "6. 如果用户没指定出行方式,默认使用驾车(maps_direction_driving),"
                    + "完成后可以问用户是否也想看步行或骑行方案。\n"
                    + "7. 对于非导航类问题(闲聊、知识问答),正常回答,不要调用任何工具。\n"
                    + "8. 看到 \"可用 skill\" 时(如 summarizer / data-analysis),"
                    + "把用户的辅助诉求(摘要、统计分析等)交给对应 skill 处理,"
                    + "不要绕开 skill 自己凑。";
}
