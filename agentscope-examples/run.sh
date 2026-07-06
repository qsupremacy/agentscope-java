#!/usr/bin/env bash
# Run the BasicChatExample with the OpenTelemetry Java Agent attached.
# Safe to invoke from anywhere — paths are resolved relative to this script.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
MODULE_DIR="${SCRIPT_DIR}/documentation"
AGENT_JAR="${REPO_ROOT}/opentelemetry-javaagent.jar"
MAIN_CLASS="io.agentscope.examples.documentation2.quickstart.BasicChatExample"

if [ ! -d "${MODULE_DIR}" ]; then
    echo "Error: cannot find module directory: ${MODULE_DIR}" >&2
    exit 1
fi

if [ ! -f "${AGENT_JAR}" ]; then
    echo "Downloading OpenTelemetry Java Agent to ${AGENT_JAR} ..."
    curl -L -o "${AGENT_JAR}" \
        https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/latest/download/opentelemetry-javaagent.jar
fi

if [ -z "${DASHSCOPE_API_KEY:-}" ]; then
    echo "Warning: DASHSCOPE_API_KEY not set; the agent will fail at first model call." >&2
fi

export OTEL_SERVICE_NAME="${OTEL_SERVICE_NAME:-basic-chat-example}"
export OTEL_EXPORTER_OTLP_ENDPOINT="${OTEL_EXPORTER_OTLP_ENDPOINT:-http://localhost:4317}"
export OTEL_EXPORTER_OTLP_PROTOCOL="${OTEL_EXPORTER_OTLP_PROTOCOL:-grpc}"

cd "${REPO_ROOT}"
MODULE_REL="${MODULE_DIR#${REPO_ROOT}/}"
mvn -pl "${MODULE_REL}" exec:exec \
    -Dexec.executable=java \
    -Dexec.args="-javaagent:${AGENT_JAR} -classpath %classpath ${MAIN_CLASS}"
