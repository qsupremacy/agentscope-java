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

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.opentelemetry.api.trace.Span;
import java.util.function.Function;
import reactor.core.publisher.Flux;

/**
 * Stamps {@code gen_ai.conversation.id} (the OpenTelemetry GenAI semantic-convention
 * attribute) onto the active span using {@link RuntimeContext#getSessionId()}.
 *
 * <p>Designed to run <b>inside</b> {@code OtelTracingMiddleware}: middlewares are
 * chained outermost-first (see {@code MiddlewareChain.build}), so when this
 * middleware's {@code onAgent} is invoked, {@link Span#current()} returns the
 * {@code invoke_agent} span that {@code OtelTracingMiddleware} just created inside
 * its {@code runWithContext}.
 *
 * <p>Why a separate middleware rather than editing {@code OtelTracingMiddleware}:
 * keeps the example self-contained and demonstrates the extension point without
 * modifying the core framework.
 */
public class ConversationIdMiddleware implements MiddlewareBase {

    private static final String GEN_AI_CONVERSATION_ID = "gen_ai.conversation.id";
    private static final String ENDUSER_ID = "gen_ai.user.id";
    private static final String GEN_AI_RESOURCE_ID = "gen_ai.resource.id";
    private static final String GEN_AI_RESOURCE_TYPE = "gen_ai.resource.type";
    private static final String RESOURCE_TYPE_AGENT = "agent";
    private static final String DEFAULT_RESOURCE_ID = "57e005b686cb405ea0c995d1b5961dac";

    private static String firstNonBlank(String preferred, String fallback) {
        return preferred != null && !preferred.isBlank() ? preferred : fallback;
    }

    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent,
            RuntimeContext ctx,
            AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        // IMPORTANT: OtelTracingMiddleware invokes next.apply(input) eagerly inside its
        // Flux.deferContextual body, BEFORE ContextPropagationOperator.runWithContext
        // activates the new invoke_agent span. If we set the attribute up here, Span.current()
        // returns the parent span (or NoOp) and the attribute lands on the wrong span.
        //
        // Flux.defer pushes the Span.current() call until subscribe time — by which point
        // OtelTracing's runWithContext has activated otelCtx, so Span.current() resolves to
        // the invoke_agent span.
        return Flux.defer(
                () -> {
                    Span span = Span.current();
                    // Resource identity (moved out of OtelTracingMiddleware.setCommonAttributes
                    // so per-tenant config lives in the application, not the framework). These
                    // will only appear on the invoke_agent span; chat and execute_tool spans
                    // intentionally no longer carry them.
                    span.setAttribute(
                            GEN_AI_RESOURCE_ID,
                            firstNonBlank(
                                    System.getenv("AGENTSCOPE_RESOURCE_ID"), DEFAULT_RESOURCE_ID));
                    span.setAttribute(GEN_AI_RESOURCE_TYPE, RESOURCE_TYPE_AGENT);
                    if (ctx != null) {
                        if (ctx.getSessionId() != null) {
                            span.setAttribute(GEN_AI_CONVERSATION_ID, ctx.getSessionId());
                        }
                        if (ctx.getUserId() != null) {
                            span.setAttribute(ENDUSER_ID, ctx.getUserId());
                        }
                    }
                    return next.apply(input);
                });
    }
}
