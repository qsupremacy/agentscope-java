# AgentScope 可观测性设计分析

> 扫描版本:`2.0.0-SNAPSHOT` · 范围:`agentscope-core` + `agentscope-harness` + `agentscope-extensions/studio` + `agentscope-spring-boot-starters/admin`

---

## 全景视图

```
┌──────────────────────────────────────────────────────────────────┐
│                     AgentScope 应用进程                            │
│                                                                   │
│  ┌──────────┐   ┌──────────┐   ┌──────────┐   ┌──────────┐       │
│  │  Agent   │   │  Hook    │   │Middleware│   │ Studio   │       │
│  │          │   │  System  │   │  Chain   │   │ Connector│       │
│  └─────┬────┘   └────┬─────┘   └────┬─────┘   └────┬─────┘       │
│        │              │             │              │              │
│        ▼              ▼             ▼              ▼              │
│  ┌─────────────────────────────────────────────────────────┐     │
│  │              AgentEvent (Flux<AgentEvent>)              │     │
│  │   25+ 事件类型:Start/End/Delta/Result/Error/Custom       │     │
│  └─────┬──────────────────┬──────────────────┬─────────────┘     │
│        │                  │                  │                    │
│        ▼                  ▼                  ▼                    │
│  ┌──────────┐      ┌──────────┐      ┌──────────┐                │
│  │ SLF4J    │      │OTel      │      │Jsonl     │                │
│  │ Logs     │      │Spans     │      │Files     │                │
│  │(42 files)│      │(trace)   │      │(debug)   │                │
│  └──────────┘      └────┬─────┘      └──────────┘                │
│                         │                                         │
│                         ▼                                         │
│                  ┌──────────────┐                                 │
│                  │ OTel Collector│ (OTLP HTTP/gRPC :4318/:4317)   │
│                  └──────┬───────┘                                 │
│                         │                                         │
│   ┌─────────────────────┼─────────────────────┐                   │
│   ▼                     ▼                     ▼                   │
│ Spring               Graceful               Permission             │
│ Actuator             Shutdown               Engine                │
│ endpoints            Manager                                       │
│                                                                  │
└──────────────────────────────────────────────────────────────────┘
```

---

## 一、Logs(日志)

| 项 | 现状 |
|---|---|
| API | **SLF4J** — 42 个文件使用 |
| 后端 | 未显式绑定(走依赖传递;consumer 自己带 logback / log4j) |
| 配置文件 | 4 个示例模块各自带 `logback-spring.xml` |
| 全局配置 | **无** — 框架不带默认 logback.xml |
| 结构化日志 | 未默认 JSON 化;遵循使用方的 `logback-spring.xml` |
| 敏感信息 | `*Credential.java` 的 `toString()` 都脱敏(`apiKey=***`) |

**评价**:符合"框架不强加日志格式"的原则,但缺一个**默认 logback.xml + JSON encoder 推荐配置**,开箱体验差一点。

---

## 二、Metrics(指标)

> ⚠️ 这是**最弱**的一环。

| 维度 | 现状 |
|---|---|
| OTel Meter API | **完全没用**(`Meter` / `Counter` / `Histogram` 引用 = 0) |
| Micrometer | **完全没用**(仅 `ConversationCompactor` 等少数代码提到,实际是 token 计数,非指标框架) |
| Spring Boot Actuator | ✅ `MetricsRecorder` + `MetricsHook` 跟踪 token 用量 |
| 暴露方式 | `/actuator/agentscope-usage` JSON,按 `byAgent` / `byModel` 切片 |
| 持久化 | ❌ JVM 内,重启清零;只能通过 `AdminAuditEvent` 转发 |

**当前能拿到的唯一指标**:`inputTokens` / `outputTokens` 累加值。

---

## 三、Traces(链路)

> 这是 AgentScope 可观测性**最完整**的一环。

| 组件 | 状态 | 说明 |
|---|---|---|
| `OtelTracingMiddleware` | ✅ **主推** | 3 类 span:`invoke_agent` / `chat` / `execute_tool`,GenAI 语义约定 |
| `Tracer` / `TracerRegistry` / `NoopTracer` | ⚠️ 已弃用 | `@Deprecated(forRemoval = true, since = "2.0.0")`,保留仅作兼容 |
| `JsonlTraceExporter` | ✅ 调试利器 | 基于 Hook 把每个事件写成 JSONL 文件,带 `failFast` 选项 |
| `TelemetryTracer` (studio) | ✅ Studio 专用 | GenAI incubating 属性 + 自己的属性抽取器 |
| Reactor Context 传播 | ✅ `ContextPropagationOperator` | 跨线程异步链自动串联父 span |

**端到端 trace 树**(典型):

```
HTTP POST  (OTel agent / OkHttp)
  └─ chat qwen-plus              (OtelTracingMiddleware)
       └─ invoke_agent Assistant (OtelTracingMiddleware)
            └─ execute_tool foo (OtelTracingMiddleware)
```

**`OtelTracingMiddleware` 产出的 span 属性**(遵循 GenAI semconv):

| Span 类型 | 属性 |
|---|---|
| `invoke_agent <name>` | `gen_ai.operation.name` / `gen_ai.agent.name` / `gen_ai.agent.id` / `gen_ai.request.messages.count` / `agentscope.agent.reply_id` |
| `chat <model>` | `gen_ai.operation.name` / `gen_ai.request.model` / `gen_ai.request.messages.count` / `gen_ai.request.tools.count` |
| `chat <model>`(完成时)| `gen_ai.usage.input_tokens` / `gen_ai.usage.output_tokens` |
| `execute_tool <name>` | `gen_ai.operation.name` / `gen_ai.tool.name` / `gen_ai.tool.call.count` / `gen_ai.tool.call.id` |

---

## 四、Events(领域事件)

> 这是 AgentScope **最独特**的可观测性设计。

| 项 | 现状 |
|---|---|
| 抽象 | `AgentEvent`(抽象基类,带 `id` + `createdAt` + `metadata`) |
| 类型数 | **25+ 具体事件** |
| 分类 | Agent 生命周期(Start/End/Result/Error)、Model 调用(Start/End)、内容块(Text/Thinking/Data Block + Start/Delta/End)、Tool 调用(ToolCall Start/Delta/End + ToolResult Start/Delta/End)、人机交互(RequireUserConfirm + UserConfirmResult)、子 Agent、Hint、Custom |
| 类型机制 | Jackson 多态(`@JsonTypeInfo` + `@JsonSubTypes`),`type` 字段做 discriminator |
| 兼容 | 有 legacy alias(`RUN_STARTED` → `AGENT_START` 等) |
| 消费 | 反应式 `Flux<AgentEvent>`,通过 `agent.streamEvents()` 拿到 |
| 序列化 | 自带 Jackson 支持,可以直接 JSON 输出 |

**完整事件类型清单**:

| 类别 | 事件类型 |
|---|---|
| Agent 生命周期 | `AGENT_START` / `AGENT_END` / `AGENT_RESULT` / `EXCEED_MAX_ITERS` / `REQUEST_STOP` |
| Model 调用 | `MODEL_CALL_START` / `MODEL_CALL_END` |
| 内容块(Text)| `TEXT_BLOCK_START` / `TEXT_BLOCK_DELTA` / `TEXT_BLOCK_END` |
| 内容块(Thinking)| `THINKING_BLOCK_START` / `THINKING_BLOCK_DELTA` / `THINKING_BLOCK_END` |
| 内容块(Data)| `DATA_BLOCK_START` / `DATA_BLOCK_DELTA` / `DATA_BLOCK_END` |
| 工具调用 | `TOOL_CALL_START` / `TOOL_CALL_DELTA` / `TOOL_CALL_END` |
| 工具结果 | `TOOL_RESULT_START` / `TOOL_RESULT_TEXT_DELTA` / `TOOL_RESULT_DATA_DELTA` / `TOOL_RESULT_END` |
| 人机交互 | `REQUIRE_USER_CONFIRM` / `USER_CONFIRM_RESULT` |
| 外部执行 | `REQUIRE_EXTERNAL_EXECUTION` / `EXTERNAL_EXECUTION_RESULT` |
| 子 Agent | `SUBAGENT_EXPOSED` |
| 提示 | `HINT_BLOCK` |
| 自定义 | `CUSTOM` |

**评价**:这是 AgentScope **相比传统 LLM 框架的差异化优势**——事件粒度细到 token-by-token,设计天然适合做调试 UI / 审计 / 持久化回放。Studio extension 就是基于这套事件做出来的。

---

## 五、Hooks(横切拦截)

```java
Hook {
  <T extends HookEvent> Mono<T> onEvent(T event);  // 默认实现透传
  default int priority() { return 100; }         // 0-50 critical, 501-1000 logging
}
```

支持的事件类型:Pre/Post `Reasoning` / `Call` / `Acting` / `Summary`,加上 `*ChunkEvent` 流式切片。

**Hook 是其他所有可观测性能力的承载点**:`OtelTracingMiddleware` 继承 `MiddlewareBase`(同类机制),`MetricsHook`、`JsonlTraceExporter`、各种自定义审计 hook 都通过 Hook 注册。

---

## 六、Audit & Admin(Spring Boot Actuator)

只有用 `agentscope-admin-spring-boot-starter` 时才有,但**设计非常完整**:

| 端点 | 类型 | 作用 |
|---|---|---|
| `GET /actuator/agentscope-usage` | read | Token 用量(总/按 agent/按 model) |
| `GET /actuator/agentscope-agents` | read | Agent 清单 |
| `GET /actuator/agentscope-tools` | read | 工具清单 |
| `GET /actuator/agentscope-models` | read | 模型清单 |
| `GET /actuator/agentscope-subagents` | read | 子 agent 清单 |
| `GET /actuator/agentscope-commands` | read | 管理命令清单 |
| `GET /actuator/agentscope-permissions` | read | 权限规则 |
| `GET /actuator/agentscope-doctor` | read | 健康自检 |
| `POST /actuator/agentscope-drain` | write | 排空在途请求 |
| `POST /actuator/agentscope-shutdown` | write | 触发优雅关闭 |

**审计事件**:

```java
AdminAuditEvent(timestamp, commandId, operator, target, writes, outcome, attributes)
```

由 `AdminAuditLogger` 同时投递到 **SLF4J** 和 **Spring ApplicationEvent**(下游可接 Loki / RocketMQ / Nacos audit)。

---

## 七、Lifecycle(生命周期)

| 组件 | 职责 |
|---|---|
| `GracefulShutdownManager` | 单例状态机:`RUNNING` → `DRAINING` → `STOPPED`,跟踪活跃请求 |
| `GracefulShutdownConfig` | 超时 / 部分推理策略(`PartialReasoningPolicy`) |
| `GracefulShutdownMiddleware` | 拦截 agent 调用,优雅拒绝 |
| `AgentScopeJvmShutdownHook` | 注册 JVM shutdown hook |
| `ActiveRequestContext` | 跟踪每个 in-flight 请求 |
| `ShutdownStateSaver` | 持久化状态,支持恢复 |
| `InterruptControl` / `InterruptSource` / `InterruptContext` | 用户主动中断(支持多种来源) |

状态转换会打 INFO 日志,可通过 `/actuator/agentscope-shutdown` 远程触发。

---

## 八、Permission(权限审计)

`PermissionEngine` + `PermissionMode`(`ALLOW` / `DENY` / `ASK` / `ASK_EACH_TIME`),有完整的:

- **规则管理**:`PermissionRule` / `PermissionDecision`
- **状态**:`PermissionContextState` 跨会话跟踪
- **审批流程**:产生 `RequireUserConfirmEvent` → 用户确认 → `UserConfirmResultEvent`
- **可视化**:`/actuator/agentscope-permissions`

---

## 优势总结

1. **领域事件粒度极细** — 25+ 事件类型 + 反应式流,做调试 UI 和回放的最优基础
2. **OTel 集成规范** — 用 `MiddlewareBase`,而不是侵入业务代码
3. **GenAI 语义约定** — 遵循 OTel GenAI semconv,跟 Jaeger/Tempo/Honeycomb 直接兼容
4. **Hook 系统** — 横切能力(trace / metric / audit)的统一入口
5. **Spring Boot Actuator 集成** — 10 个端点覆盖运维需求
6. **优雅关闭** — 完整的状态机 + 请求跟踪 + 持久化
7. **Studio 集成** — 把事件流变成可视化调试 UI

---

## 不足总结

| 弱项 | 影响 | 建议 |
|---|---|---|
| **OTel 指标完全缺失** | 无法做 dashboard / 告警 / 长期趋势分析 | 加 `OtelMetricsMiddleware`(参照 tracing 版),输出 `agent.calls` / `chat.tokens` / `tool.calls` 三个 counter |
| **没有默认 logback.xml** | 开箱即用体验差,日志格式不可控 | 在 `agentscope-core` 里加一个 `logback-default.xml`,JSON encoder |
| **`JsonlTraceExporter` 还在** | 与 OTel tracing 并存,有功能重叠 | 文档明确"用于本地调试,生产用 OTel" |
| **`MetricsRecorder` 仅在 admin starter** | 非 Spring Boot 用户拿不到 token 指标 | 把它下沉到 core,作为可选 `MetricsHook` |
| **`AdminAuditEvent` 仅在 admin starter** | 同样问题 | 考虑下沉核心 + AdminAudit 适配 |
| **Micrometer 桥接缺失** | 用户已有的 Micrometer/Prometheus 栈接不上 | 加 `MicrometerTracingBridge` 或者直接用 `OtelMetricsMiddleware` + Micrometer Tracing |
| **Span 上的指标只有 attribute 不是 metric** | 难以做趋势分析(token 用量不能 SUM/rate) | 必须补 `OtelMetricsMiddleware` |
| **没有 Health Indicator 的标准定义** | K8s liveness/readiness probe 不知怎么接 | 用 admin starter 的 `/actuator/agentscope-doctor`,文档化 |

---

## 一句话定性

AgentScope 的可观测性设计是**"领域事件驱动 + 标准 OTel 集成"** 的混合模型,事件流这块做得很超前(胜过 LangChain4j / Spring AI),但 **OTel 指标层缺失 + Spring Actuator 限制在 admin starter** 是当前最大的两个缺口,适合给生产部署用的项目重点补齐。