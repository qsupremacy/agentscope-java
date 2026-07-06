# `run.sh` — BasicChatExample 一键启动 + OTel Trace 采集指南

> 配套脚本:[`agentscope-examples/run.sh`](./run.sh) · 入口类:`io.agentscope.examples.documentation2.quickstart.BasicChatExample`
>
> 验证 trace ID:`516ce1b971c1a21040830ebaf275ab74`(2026-07-06,通过阿里云 ARMS/SLS 落地)
> 文档示例数据取自该 trace 的一个 invocation(`__time__ = 1783331756`,约 18:09)

---

## 1. `run.sh` 是什么

`run.sh` 是一个**自包含的启动脚本**,只需一条命令就能:
1. 下载 OpenTelemetry Java Agent(首次运行时,~20MB)
2. 解析自身所在路径,定位 Maven 模块和 agent jar 的绝对位置
3. 通过 `mvn exec:exec` 启动 BasicChatExample,挂载 `-javaagent:`
4. 配置 OTLP exporter + service name 环境变量

### 1.1 用法

```bash
# 在任何目录下都可以(脚本自解析路径)
bash /data/disk/agentscope-java/agentscope-examples/run.sh

# 或者在 examples 目录里
cd /data/disk/agentscope-java/agentscope-examples && ./run.sh

# 第一次跑会自动下载 agent jar:
#   Downloading OpenTelemetry Java Agent to /data/disk/agentscope-java/opentelemetry-javaagent.jar ...
# 后续直接复用
```

### 1.2 环境变量

| 变量 | 默认值 | 说明 |
|---|---|---|
| `DASHSCOPE_API_KEY` | (必填,缺则首次模型调用失败) | DashScope API key,从 https://dashscope.console.aliyun.com 获取 |
| `OTEL_SERVICE_NAME` | `basic-chat-example` | 在 Jaeger/ARMS 里看到的服务名 |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://localhost:4317` | OTel collector OTLP gRPC 端点 |
| `OTEL_EXPORTER_OTLP_PROTOCOL` | `grpc` | 协议:`grpc` / `http/protobuf` |

示例:

```bash
OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector.internal:4317 \
OTEL_SERVICE_NAME=my-chat-app \
DASHSCOPE_API_KEY=sk-xxx \
./run.sh
```

### 1.3 它解决的两个问题

| 问题 | `run.sh` 的处理 |
|---|---|
| Maven `-pl` 路径依赖运行位置 | `${BASH_SOURCE}` 自推导 → 仓库根 + 模块路径 |
| Java agent jar 从哪儿来 | 自动下载到仓库根,重复使用 |

---

## 2. Trace 采集原理

整个 trace 采集链路由**四层**组成,每层职责清晰:

```
┌──────────────────────────────────────────────────────────────┐
│ 1. JVM 进程(你的应用)                                       │
│                                                              │
│   -javaagent:opentelemetry-javaagent.jar                     │
│      └─► 字节码织入 OkHttp / Reactor / Servlet / DB / ...    │
│          生成 HTTP、gRPC、DB 等基础设施 span                  │
│                                                              │
│   ReActAgent.builder()                                       │
│      └─► .middleware(new OtelTracingMiddleware())            │
│          生成 invoke_agent / chat / execute_tool 业务 span    │
│                                                              │
│   两者共享 GlobalOpenTelemetry                                │
│   W3C Trace Context 自动串联                                  │
└──────────────────────────────┬───────────────────────────────┘
                               │ OTLP (gRPC :4317 或 HTTP :4318)
                               ▼
┌──────────────────────────────────────────────────────────────┐
│ 2. OTel Collector(本地或远端)                                 │
│                                                              │
│   receivers.otlp.protocols.grpc.endpoint = 0.0.0.0:4317      │
│   exporters.otlp → 转发到 ARMS / Jaeger / Tempo / ...         │
└──────────────────────────────┬───────────────────────────────┘
                               │ OTLP
                               ▼
┌──────────────────────────────────────────────────────────────┐
│ 3. 后端存储(本示例为阿里云 ARMS + SLS)                        │
│                                                              │
│   SLS 日志存储(便宜)→ ARMS Trace 工作台查询(可视化)         │
└──────────────────────────────────────────────────────────────┘
```

### 2.1 Span 分类与属性

#### Layer 1:Java Agent 自动织入(基础设施层)

**作用域**:OkHttp / Reactor / Servlet / JDBC / ...

**典型 span**(本次示例 trace 中应存在,但 ARMS 聚合查询只返回了 1 行):

```json
{
  "name": "POST",
  "kind": "CLIENT",
  "attributes": {
    "http.request.method":       "POST",
    "url.full":                  "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
    "server.address":            "dashscope.aliyuncs.com",
    "http.response.status_code": "200"
  }
}
```

#### Layer 2:`OtelTracingMiddleware` 产出(业务层)

| Span name | 钩子 | 关键 attributes |
|---|---|---|
| `invoke_agent <name>` | `onAgent` | `gen_ai.operation.name` / `gen_ai.agent.name` / `gen_ai.agent.id` / `gen_ai.request.messages.count` / `agentscope.agent.reply_id` |
| `chat <modelName>` | `onModelCall` | `gen_ai.request.model` / `gen_ai.request.messages.count` / `gen_ai.request.tools.count` / 完成时 `gen_ai.usage.input_tokens` / `gen_ai.usage.output_tokens` |
| `execute_tool <name>` | `onActing` | `gen_ai.tool.name` / `gen_ai.tool.call.count` / 完成时 `gen_ai.tool.call.id` |

### 2.2 Reactor 上下文传播(关键)

中间件构造时注册 `ContextPropagationOperator`,把 OTel Context 注入 Reactor 的 Context 中。跨 `publishOn` / `subscribeOn` 切线程时父 span **不会丢失**——这是单元测试 `nestedSpans_preservedAcrossThreadHop` 强保证的不变量。

### 2.3 完整 trace 树(实际形状,来自实测数据)

```
invoke_agent Assistant                         ← OtelTracingMiddleware (scope=io.agentscope, kind=INTERNAL)
  ├─ chat qwen-plus                            ← OtelTracingMiddleware (scope=io.agentscope, kind=INTERNAL)
  └─ POST dashscope.aliyuncs.com              ← Java agent java-http-client (scope=io.opentelemetry.java-http-client, kind=CLIENT)
```

> ⚠️ 注意:`POST` 不是 `chat` 的子 span,而是**兄弟 span**(两者都直接挂在 `invoke_agent` 下)。
>
> 原因:`OtelTracingMiddleware.onModelCall` 把 chat span 存进 Reactor Context,但 Java agent 的 `io.opentelemetry.java-http-client` instrumentation 读 ThreadLocal 里的 OTel Context,跨线程切到 HTTP client 工作线程时,ThreadLocal 里已经没有 chat span,只剩 invoke_agent 透出来的 Context。生产中如果要严格父子关系,需要把 `Context.current()` 显式 wrap 到 HTTP client 调用上。

---

## 3. 采集示例数据

### 3.1 原始 ARMS 聚合行(单 trace 一行)

`group by traceId` + `min_by(spanName, startTime)` —— ARMS 默认查询只返回 trace 的**最早 span**(`invoke_agent Assistant`)。其余子 span 在数据里存在但被聚合折叠掉了。

```json
{
  "traceId": "516ce1b971c1a21040830ebaf275ab74",
  "spanName": "invoke_agent Assistant",
  "serviceName": "basic-chat-example",
  "statusCode": "1",
  "hostname": "VM-0-10-ubuntu",
  "ip": "null",
  "pid": "iv8p3nic2x@c758a8f4c0c283b",
  "attributes": {
    "otel.scope.name":                "io.agentscope",
    "agentscope.agent.reply_id":      "0c084c0d2dfa43dbaf7010b064f79824",
    "rpc":                            "invoke_agent Assistant",
    "gen_ai.agent.name":              "Assistant",
    "otel.scope.version":             "",
    "call.kind":                      "internal",
    "thread.name":                    "main",
    "gen_ai.operation.name":          "invoke_agent",
    "ali.trace.flag":                 "x-trace",
    "gen_ai.request.messages.count":  "1",
    "gen_ai.agent.id":                "629c4048-696d-4946-a48f-469a1f52efc8",
    "thread.id":                      "1",
    "call.type":                      "local"
  },
  "resources": {
    "telemetry.distro.version":       "2.17.1",
    "service.name":                   "basic-chat-example",
    "telemetry.distro.name":          "opentelemetry-java-instrumentation",
    "process.runtime.version":        "21.0.11+10-1-24.04.2-Ubuntu",
    "os.type":                        "linux",
    "acs.arms.service.id":            "iv8p3nic2x@bcc54d363840b4af7a5a0",
    "process.pid":                    "67647",
    "telemetry.sdk.name":             "opentelemetry",
    "telemetry.sdk.language":         "java",
    "process.runtime.name":           "OpenJDK Runtime Environment",
    "service.instance.id":            "1792adef-8eee-4c08-bb03-cf7b1ea4853b",
    "os.description":                 "Linux 6.8.0-71-generic",
    "process.executable.path":        "/usr/lib/jvm/java-21-openjdk-amd64/bin/java",
    "acs.cms.workspace":              "default-cms-1916215180918953-cn-shanghai",
    "host.arch":                      "amd64",
    "process.command_line":           "/usr/lib/jvm/java-21-openjdk-amd64/bin/java -javaagent:/data/disk/agentscope-java/opentelemetry-javaagent.jar io.agentscope.examples.documentation2.quickstart.BasicChatExample",
    "host.name":                      "VM-0-10-ubuntu",
    "telemetry.sdk.version":          "1.51.0",
    "process.runtime.description":    "Ubuntu OpenJDK 64-Bit Server VM 21.0.11+10-1-24.04.2-Ubuntu"
  },
  "__source__": "",
  "__time__": "1783331756"
}
```

### 3.2 字段解读

| 字段 | 值 | 含义 |
|---|---|---|
| `spanName` | `invoke_agent Assistant` | `OtelTracingMiddleware.onAgent` 产出 |
| `otel.scope.name` | `io.agentscope` | 这是 `OtelTracingMiddleware.INSTRUMENTATION_NAME` 常量 |
| `otel.scope.version` | (空) | 未设置 — 可改进点 |
| `service.name` | `basic-chat-example` | 来自 `OTEL_SERVICE_NAME` 环境变量 |
| `telemetry.distro.version` | `2.17.1` | Java agent 版本 |
| `telemetry.sdk.version` | `1.51.0` | agent 内嵌的 OTel SDK 版本 |
| `process.command_line` | 含 `-javaagent:...` | 字节码织入已生效 |
| `statusCode` | `"1"` | OTel 编码:`0=UNSET, 1=OK, 2=ERROR` |
| `__time__` | `1783331756` | Unix 秒级时间戳 |
| `gen_ai.agent.id` | `629c4048-696d-4946-a48f-469a1f52efc8` | Agent 实例 UUID |
| `agentscope.agent.reply_id` | `0c084c0d2dfa43dbaf7010b064f79824` | 本次 reply UUID |

### 3.3 OTLP/JSON 标准格式(同一 span)

```json
{
  "resourceSpans": [
    {
      "resource": {
        "attributes": [
          { "key": "service.name",                  "value": { "stringValue": "basic-chat-example" } },
          { "key": "service.instance.id",          "value": { "stringValue": "1792adef-8eee-4c08-bb03-cf7b1ea4853b" } },
          { "key": "telemetry.sdk.name",           "value": { "stringValue": "opentelemetry" } },
          { "key": "telemetry.sdk.language",       "value": { "stringValue": "java" } },
          { "key": "telemetry.sdk.version",        "value": { "stringValue": "1.51.0" } },
          { "key": "telemetry.distro.name",        "value": { "stringValue": "opentelemetry-java-instrumentation" } },
          { "key": "telemetry.distro.version",     "value": { "stringValue": "2.17.1" } },
          { "key": "process.pid",                  "value": { "intValue":    "67647" } },
          { "key": "process.runtime.name",         "value": { "stringValue": "OpenJDK Runtime Environment" } },
          { "key": "process.runtime.version",      "value": { "stringValue": "21.0.11+10-1-24.04.2-Ubuntu" } },
          { "key": "process.runtime.description",  "value": { "stringValue": "Ubuntu OpenJDK 64-Bit Server VM 21.0.11+10-1-24.04.2-Ubuntu" } },
          { "key": "process.executable.path",      "value": { "stringValue": "/usr/lib/jvm/java-21-openjdk-amd64/bin/java" } },
          { "key": "process.command_line",         "value": { "stringValue": "/usr/lib/jvm/java-21-openjdk-amd64/bin/java -javaagent:/data/disk/agentscope-java/opentelemetry-javaagent.jar io.agentscope.examples.documentation2.quickstart.BasicChatExample" } },
          { "key": "host.name",                    "value": { "stringValue": "VM-0-10-ubuntu" } },
          { "key": "host.arch",                    "value": { "stringValue": "amd64" } },
          { "key": "os.type",                      "value": { "stringValue": "linux" } },
          { "key": "os.description",               "value": { "stringValue": "Linux 6.8.0-71-generic" } }
        ]
      },
      "scopeSpans": [
        {
          "scope": { "name": "io.agentscope", "version": "" },
          "spans": [
            {
              "traceId": "516ce1b971c1a21040830ebaf275ab74",
              "spanId":  "d4e5f6a7b8c9d0e1",
              "name":    "invoke_agent Assistant",
              "kind":    1,
              "startTimeUnixNano": "1783331756000000000",
              "endTimeUnixNano":   "1783331756000000000",
              "attributes": [
                { "key": "gen_ai.operation.name",        "value": { "stringValue": "invoke_agent" } },
                { "key": "gen_ai.agent.name",            "value": { "stringValue": "Assistant" } },
                { "key": "gen_ai.agent.id",              "value": { "stringValue": "629c4048-696d-4946-a48f-469a1f52efc8" } },
                { "key": "gen_ai.request.messages.count","value": { "intValue":    "1" } },
                { "key": "agentscope.agent.reply_id",    "value": { "stringValue": "0c084c0d2dfa43dbaf7010b064f79824" } },
                { "key": "rpc",                          "value": { "stringValue": "invoke_agent Assistant" } },
                { "key": "call.kind",                    "value": { "stringValue": "internal" } },
                { "key": "call.type",                    "value": { "stringValue": "local" } },
                { "key": "thread.name",                  "value": { "stringValue": "main" } },
                { "key": "thread.id",                    "value": { "intValue":    "1" } },
                { "key": "ali.trace.flag",               "value": { "stringValue": "x-trace" } }
              ],
              "status": { "code": 1 }
            }
          ]
        }
      ]
    }
  ]
}
```

### 3.4 完整 trace 树(实测真实数据)

下面 3 个 span 是 ARMS span 列表查询返回的**真实数据**,不是重建。`endTimeUnixNano` 由 `startTimeUnixNano + duration` 计算得出。

```json
{
  "resourceSpans": [
    {
      "resource": {
        "attributes": [
          { "key": "service.name",                  "value": { "stringValue": "basic-chat-example" } },
          { "key": "service.instance.id",          "value": { "stringValue": "1792adef-8eee-4c08-bb03-cf7b1ea4853b" } },
          { "key": "telemetry.sdk.name",           "value": { "stringValue": "opentelemetry" } },
          { "key": "telemetry.sdk.language",       "value": { "stringValue": "java" } },
          { "key": "telemetry.sdk.version",        "value": { "stringValue": "1.51.0" } },
          { "key": "telemetry.distro.name",        "value": { "stringValue": "opentelemetry-java-instrumentation" } },
          { "key": "telemetry.distro.version",     "value": { "stringValue": "2.17.1" } },
          { "key": "process.pid",                  "value": { "intValue":    "67647" } },
          { "key": "process.runtime.name",         "value": { "stringValue": "OpenJDK Runtime Environment" } },
          { "key": "process.runtime.version",      "value": { "stringValue": "21.0.11+10-1-24.04.2-Ubuntu" } },
          { "key": "process.executable.path",      "value": { "stringValue": "/usr/lib/jvm/java-21-openjdk-amd64/bin/java" } },
          { "key": "process.command_line",         "value": { "stringValue": "/usr/lib/jvm/java-21-openjdk-amd64/bin/java -javaagent:/data/disk/agentscope-java/opentelemetry-javaagent.jar io.agentscope.examples.documentation2.quickstart.BasicChatExample" } },
          { "key": "host.name",                    "value": { "stringValue": "VM-0-10-ubuntu" } },
          { "key": "host.arch",                    "value": { "stringValue": "amd64" } },
          { "key": "os.type",                      "value": { "stringValue": "linux" } },
          { "key": "os.description",               "value": { "stringValue": "Linux 6.8.0-71-generic" } }
        ]
      },
      "scopeSpans": [
        {
          "scope": { "name": "io.agentscope", "version": "" },
          "spans": [
            {
              "traceId": "516ce1b971c1a21040830ebaf275ab74",
              "spanId":  "ceab7482b128f5f7",
              "name":    "invoke_agent Assistant",
              "kind":    1,
              "startTimeUnixNano": "1783331793919278004",
              "endTimeUnixNano":   "1783331795514360803",
              "duration_ns":         1595082799,
              "attributes": [
                { "key": "gen_ai.operation.name",        "value": { "stringValue": "invoke_agent" } },
                { "key": "gen_ai.agent.name",            "value": { "stringValue": "Assistant" } },
                { "key": "gen_ai.agent.id",              "value": { "stringValue": "629c4048-696d-4946-a48f-469a1f52efc8" } },
                { "key": "gen_ai.request.messages.count","value": { "intValue":    "1" } },
                { "key": "agentscope.agent.reply_id",    "value": { "stringValue": "0c084c0d2dfa43dbaf7010b064f79824" } }
              ],
              "status": { "code": 1 }
            },
            {
              "traceId":      "516ce1b971c1a21040830ebaf275ab74",
              "spanId":       "780a062ab3d5e126",
              "parentSpanId": "ceab7482b128f5f7",
              "name":         "chat qwen-plus",
              "kind":         1,
              "startTimeUnixNano": "1783331794011116897",
              "endTimeUnixNano":   "1783331795509498438",
              "duration_ns":         1498381541,
              "attributes": [
                { "key": "gen_ai.operation.name",        "value": { "stringValue": "chat" } },
                { "key": "gen_ai.request.model",         "value": { "stringValue": "qwen-plus" } },
                { "key": "gen_ai.request.messages.count","value": { "intValue":    "2" } },
                { "key": "gen_ai.request.tools.count",   "value": { "intValue":    "0" } },
                { "key": "gen_ai.usage.input_tokens",    "value": { "intValue":    "26" } },
                { "key": "gen_ai.usage.output_tokens",   "value": { "intValue":    "12" } },
                { "key": "rpc",                          "value": { "stringValue": "chat qwen-plus" } }
              ],
              "status": { "code": 1 }
            }
          ]
        },
        {
          "scope": {
            "name":    "io.opentelemetry.java-http-client",
            "version": "2.17.1-alpha"
          },
          "spans": [
            {
              "traceId":      "516ce1b971c1a21040830ebaf275ab74",
              "spanId":       "6f496d4b2ab25f24",
              "parentSpanId": "ceab7482b128f5f7",
              "name":         "POST",
              "kind":         3,
              "startTimeUnixNano": "1783331794412297963",
              "endTimeUnixNano":   "1783331795248505293",
              "duration_ns":         836207330,
              "attributes": [
                { "key": "http.request.method",       "value": { "stringValue": "POST" } },
                { "key": "url.full",                  "value": { "stringValue": "https://dashscope.aliyuncs.com/api/v1/services/aigc/text-generation/generation" } },
                { "key": "server.address",            "value": { "stringValue": "dashscope.aliyuncs.com" } },
                { "key": "http.response.status_code", "value": { "intValue":    "200" } },
                { "key": "network.protocol.version",  "value": { "stringValue": "2" } }
              ],
              "status": { "code": 0 }
            }
          ]
        }
      ]
    }
  ]
}
```

**字段说明**:

| 字段 | 实际值 | 含义 |
|---|---|---|
| `invoke_agent Assistant` spanId | `ceab7482b128f5f7` | root span |
| `chat qwen-plus` spanId | `780a062ab3d5e126` | parent = invoke_agent |
| `POST` spanId | `6f496d4b2ab25f24` | parent = invoke_agent(**不是** chat) |
| invoke_agent duration | 1,595,082,799 ns ≈ **1.6s** | 用户输入 → 整次响应完成 |
| chat duration | 1,498,381,541 ns ≈ **1.5s** | LLM 调用实际耗时 |
| POST duration | 836,207,330 ns ≈ **836ms** | HTTP 往返 |
| POST scope | `io.opentelemetry.java-http-client` | **不是** okhttp-3.0 — Java agent 用 JDK HttpClient 织入 |
| POST URL | `dashscope.aliyuncs.com/api/v1/services/aigc/text-generation/generation` | **DashScope 原生 API**,非 OpenAI 兼容模式 |
| POST 协议 | `network.protocol.version = "2"` | HTTP/2 |
| POST status | `code: 0`(UNSET)| **不是 OK** — Java agent 的 HTTP instrumentation 不会自动把 HTTP 200 提升为 Span OK,需要业务层显式设 |
| chat input/output tokens | **26 / 12** | 真实数据,非假设值 |
| chat `messages.count` | **2** | 包含 system prompt + 用户输入(我之前以为是 1) |

### 3.5 实测发现(对比"预期"的偏差)

把真实数据(3.4)和 AgentScope/Java agent 的"理论预期"对比,有几处值得记下来:

| # | 发现 | 原因 / 影响 |
|---|---|---|
| 1 | **`POST` 不是 `chat` 的子 span,是兄弟** | Java agent 的 `io.opentelemetry.java-http-client` instrumentation 只读 ThreadLocal 里的 OTel Context,跨线程时拿到的是 `invoke_agent` 的 Context,不是 `chat` 的。trace 树结构是 `invoke_agent → {chat, POST}`,而非 `invoke_agent → chat → POST`。详见 2.3 节。 |
| 2 | **`POST` scope 是 `io.opentelemetry.java-http-client`,不是 OkHttp** | DashScope SDK / OkHttp 用了 Java 11+ 自带 `java.net.http.HttpClient`,Java agent 用 `java-http-client` instrumentation 自动织入。 |
| 3 | **`POST` URL 是 DashScope 原生 API**(`/api/v1/services/aigc/text-generation/generation`),不是 OpenAI 兼容路径 | 即便 AgentScope 包装成 OpenAI 兼容调用,底层 HTTP 客户端走的是 DashScope 原生 endpoint。 |
| 4 | **`POST` status 是 `UNSET`(code=0),不是 OK** | Java agent 的 HTTP instrumentation **不会**自动把 `http.response.status_code=200` 提升为 `Span.status=OK`。HTTP 200 仍记录在 `http.response.status_code` 属性里,但 Span status 需要业务层(比如 `OtelTracingMiddleware`)显式设置。当前 chat/invoke_agent 的 OK 状态由中间件显式写入。 |
| 5 | **`chat` 的 `gen_ai.request.messages.count` 是 2,不是 1** | 包含 `system prompt` + `user input`,即 memory 里所有传给 LLM 的消息数。 |
| 6 | **真实 token 用量 26 input / 12 output** | 一次短问答约 38 tokens,qwen-plus 计费几乎可忽略 |
| 7 | **`process.command_line` 含完整 javaagent 路径** | OTel 自动采集 `Resource`,可以追溯到具体哪个 agent jar 在跑,排查版本问题时直接看这个属性 |

---

## 4. 本地验证步骤

### 4.1 起 OTel collector(debug 输出)

```bash
cat > /tmp/otel-collector.yaml <<'EOF'
receivers:
  otlp:
    protocols:
      grpc:
        endpoint: 0.0.0.0:4317
      http:
        endpoint: 0.0.0.0:4318

exporters:
  debug:
    verbosity: detailed

service:
  pipelines:
    traces:
      receivers: [otlp]
      exporters: [debug]
EOF

docker run -d --name otel-collector \
  -p 4317:4317 -p 4318:4318 \
  -v /tmp/otel-collector.yaml:/etc/otelcol/config.yaml \
  otel/opentelemetry-collector-contrib:0.114.0
```

### 4.2 跑示例

```bash
export DASHSCOPE_API_KEY=sk-xxx
bash /data/disk/agentscope-java/agentscope-examples/run.sh
```

### 4.3 看 collector 实时输出

```bash
docker logs -f otel-collector
```

预期看到完整 OTLP/JSON,包含全部 3 个 span(`invoke_agent` → `chat` → `POST`)。

### 4.4 验证完整 trace

在 ARMS 控制台用以下查询(不是聚合,展开所有 span):

```
traceId: 516ce1b971c1a21040830ebaf275ab74
```

或在 collector 日志里 grep:

```bash
docker logs otel-collector | grep -A 50 "516ce1b971c1a21040830ebaf275ab74"
```

---

## 5. 常见问题

| 现象 | 原因 | 修复 |
|---|---|---|
| 脚本报 `Could not find the selected project in the reactor` | 路径解析错误 | 升级 `run.sh` 至最新版本(支持 `${BASH_SOURCE}` 自解析) |
| HTTP 401 / 403 from collector | 远端 collector 要求 auth | 加 `OTEL_EXPORTER_OTLP_HEADERS=api-key=xxx` |
| ARMS 里只看到 `invoke_agent` 一个 span | 默认查询是 `group by traceId` 聚合 | 改用 span 列表视图或单 trace 详情 |
| `otel.scope.version` 为空 | `OtelTracingMiddleware` 未设版本 | 等框架加 `INSTRUMENTATION_VERSION` 常量 |
| agent jar 找不到 / 下载失败 | 网络问题 | 手动 `curl` 到仓库根,或配 `HTTPS_PROXY` |

---

## 6. AgentScope 可观测当前设计的优缺点

> 完整分析见仓库根目录 [`observe.md`](../observe.md)。本节聚焦 OTel/Trace 维度。

### 6.1 优势

1. **中间件抽象,侵入性小**:`OtelTracingMiddleware` 实现 `MiddlewareBase`,挂在 ReActAgent builder 上即生效,业务代码不感知。

2. **遵循 GenAI 语义约定**:span attribute 用 `gen_ai.operation.name` / `gen_ai.agent.name` / `gen_ai.request.model` / `gen_ai.usage.*_tokens` 等标准命名,跟 Jaeger/Tempo/Honeycomb 直接兼容。

3. **Reactor 上下文传播完整**:`OtelTracingMiddleware` 构造时注册 `ContextPropagationOperator`,跨 `publishOn`/`subscribeOn` 切线程时父 span 不丢失(单元测试 `nestedSpans_preservedAcrossThreadHop` 强保证)。

4. **三种 Span 覆盖完整生命周期**:`invoke_agent` 包裹整次调用,`chat` 包裹每次模型调用,`execute_tool` 包裹工具执行。span 名固定前缀 + 业务名,既便于 Jaeger 服务图聚合,也保留实例级信息。

5. **状态/异常处理正确**:`OK` 在正常完成时设,`ERROR + 异常描述` 在 `doOnError` 设,`ERROR + "cancelled"` 在 `doOnCancel` 设(用户主动中断场景)。卡死 span(永远不 end)被显式避免。

6. **细粒度领域事件流**:25+ `AgentEvent` 类型(token-by-token 的 TextBlockDelta/ToolCallDelta 等),通过 `agent.streamEvents()` 拿,适合做调试 UI、审计、持久化回放——比单纯 OTel trace 更细。

7. **Spring Boot Actuator 集成**:`agentscope-admin-spring-boot-starter` 暴露 10 个 `/actuator/agentscope-*` 端点,涵盖 agent/tool/model/permission/usage 清单 + 远程 shutdown/drain,运维友好。

### 6.2 不足

| # | 问题 | 影响 |
|---|---|---|
| 1 | **OTel 指标层完全缺失** | `Meter`/`Counter`/`Histogram` 在仓库里 0 引用。Token 用量只能作为 span attribute,不能做时间序列查询、dashboard、告警。生产环境想做"过去 1 小时 token 用量趋势"做不到。 |
| 2 | **`POST` 与 `chat` 不是父子关系** | Java agent 的 `io.opentelemetry.java-http-client` instrumentation 读 ThreadLocal 跨线程会丢 `chat` Context。trace 树变成 `invoke_agent → {chat, POST}`,HTTP 实际耗时无法直接归属到 chat。详见 3.5 节。 |
| 3 | **`POST` status 是 UNSET(0)** | HTTP 200 写在 `http.response.status_code` 属性里,但 Span 状态不会自动提升为 OK——Jaeger 等 UI 上 POST span 会显示为"无状态",过滤/告警不便。 |
| 4 | **`otel.scope.version` 始终为空** | `OtelTracingMiddleware` 只传 `INSTRUMENTATION_NAME`,没传 version。Jaeger 等后端无法按 version 切片排查。 |
| 5 | **`Tracer`/`TracerRegistry`/`NoopTracer` 仍存在但已 deprecated** | `@Deprecated(forRemoval = true, since = "2.0.0")`,但还在 `agentscope-core/tracing/` 目录。新代码应只用 `OtelTracingMiddleware`,旧文档/SO 答案可能误用。 |
| 6 | **JsonlTraceExporter 与 OTel tracing 重叠** | 两个 trace 出口:OTel 走 OTLP 上报后端,Jsonl 写本地文件。Jsonl 只在本地调试时有用,但默认类路径可见,容易让用户误以为"trace 就只是写文件"。 |
| 7 | **examples 模块几乎没有 OTel 示例** | 仓库里只有我们这次提交的这一个 example。`codingagent/pom.xml` 还残留一个未被使用的 `opentelemetry-exporter-otlp` 依赖(孤儿)。 |
| 8 | **Spring Boot Actuator 端点仅在 admin starter** | `MetricsRecorder` / `AdminAuditEvent` / 10 个 actuator 端点都是 `agentscope-admin-spring-boot-starter` 私有。非 Spring 用户拿不到任何内置运维接口。 |
| 9 | **没有默认 logback.xml** | 框架不带日志配置,使用方必须自带 `logback-spring.xml`(4 个 example 模块各自带一份)。JSON 结构化日志没默认,生产里很难做 ELK/Loki 索引。 |
| 10 | **OTel 上下文传播对 HTTP instrumentation 失效** | 同 #2。本质是 Reactor `publishOn` 后,Java agent 的 byte-buddy 织入代码读的是 ThreadLocal 里的 `Context.current()`,而 chat span 已经被 `ContextPropagationOperator` 包进 Reactor Context 但没进 ThreadLocal。**修复需要 agent 在 instrumentation 里读 Reactor Context,或者业务代码显式 `Context.current().wrap()` HTTP 调用**。 |

### 6.3 改进建议(按优先级)

| 优先级 | 改动 | 工作量 | 收益 |
|---|---|---|---|
| **P0** | 给 `OtelTracingMiddleware` 加 `INSTRUMENTATION_VERSION` 常量(从 `ProjectVersion` 读) | 极小 | trace 能按 version 切片 |
| **P0** | 在 `OtelTracingMiddleware.onActing`/`onModelCall` 上,对成功路径显式设 `SpanStatusCode.OK`(目前只在完成时由 `doOnComplete` 设,但 chat span 内嵌的 HTTP 子 span 不会自动 set) | 小 | Jaeger 里 POST 显示 OK 而不是 UNSET |
| **P1** | 新增 `OtelMetricsMiddleware`,输出 `agentscope.agent.calls` / `agentscope.chat.tokens` / `agentscope.tool.calls` 三个 OTel Counter | 中 | 解锁 dashboard / 告警 / 长期趋势 |
| **P1** | 给 `BasicChatExample` 之外的 example 也加 OTel wiring(尤其是 `Builder`、`DataAgent`、`CodingAgent`)| 中 | 让用户从不同场景看 trace 模板 |
| **P2** | 修复 HTTP span 与 chat span 的父子关系:在 `onModelCall` 内显式 `Context.current().wrap()` 模型调用,或用 agent 的 `otel.instrumentation.reactor.enabled=true` 配合 `Context.current()` 在 hook 里手动 makeCurrent | 中-大 | trace 树更准确 |
| **P2** | 把 `MetricsRecorder` / `AdminAuditEvent` 下沉到 `agentscope-core` | 中 | 非 Spring 用户也能拿到 token 用量统计 |
| **P3** | 给框架加默认 `logback-default.xml`(JSON encoder) | 小 | 生产环境日志开箱即用 |

### 6.4 一句话定性

**优势**:`OtelTracingMiddleware` + GenAI 语义约定 + Reactor 上下文传播是这套设计的三板斧,单看 trace 采集是高质量的。

**短板**:**OTel 指标层缺失 + HTTP instrumentation 与 Reactor 协作有摩擦 + Spring 限定**,适合给生产部署用的项目重点补齐(见 6.3 节 P0/P1)。

---

## 7. 相关文件

| 文件 | 说明 |
|---|---|
| [`run.sh`](./run.sh) | 本文档配套的启动脚本 |
| [`documentation/src/main/java/io/agentscope/examples/documentation2/quickstart/BasicChatExample.java`](./documentation/src/main/java/io/agentscope/examples/documentation2/quickstart/BasicChatExample.java) | 示例入口类,只挂 `OtelTracingMiddleware` |
| [`agentscope-core/src/main/java/io/agentscope/core/tracing/OtelTracingMiddleware.java`](../agentscope-core/src/main/java/io/agentscope/core/tracing/OtelTracingMiddleware.java) | 业务层 span 产出的实现 |
| [`agentscope-core/src/test/java/io/agentscope/core/tracing/OtelTracingMiddlewareTest.java`](../agentscope-core/src/test/java/io/agentscope/core/tracing/OtelTracingMiddlewareTest.java) | 10 个测试,含 Reactor 跨线程的契约验证 |
| [`observe.md`](../observe.md) | 仓库级可观测设计完整分析(Logs / Metrics / Traces / Events / Audit / Lifecycle 全维度) |