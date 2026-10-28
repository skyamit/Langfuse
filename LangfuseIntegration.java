import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Properties;
import java.util.UUID;

public final class LangfuseIntegration {

    private static final Logger LOGGER = LoggerFactory.getLogger(LangfuseIntegration.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private static volatile Config config;
    private static final ThreadLocal<TraceContext> CURRENT_TRACE = new ThreadLocal<>();

    /** Utility class: all entry points are static. */
    private LangfuseIntegration() {
    }

    /**
     * Opens a trace on the current thread. The trace id is a 32-character hex UUID
     * and the root span id is 16 hex characters. A blank name becomes "workflow".
     * Later {@link #record} and {@link #completeTrace} calls on this same thread
     * pick the trace up automatically. If a generation shows up under a different
     * trace, the work crossed threads and the returned context was not passed along.
     * Look for "Langfuse unique trace id=" to confirm this ran.
     */
    public static TraceContext startTrace(String name) {
        TraceContext traceContext = new TraceContext(
                uniqueTraceId(),
                uniqueSpanId(),
                name == null || name.isBlank() ? "workflow" : name,
                Instant.now());
        CURRENT_TRACE.set(traceContext);
        LOGGER.info("Langfuse unique trace id={} name={}", traceContext.traceId(), traceContext.name());
        return traceContext;
    }

    /**
     * Returns the trace bound to this thread, or null when {@link #startTrace} was
     * never called here or {@link #completeTrace} already cleared it.
     */
    public static TraceContext currentTrace() {
        return CURRENT_TRACE.get();
    }

    /**
     * Sends the root span (observation type "chain") with the workflow input,
     * output, and optional error, then always clears the thread-local trace.
     * Does nothing when the context is null or both Langfuse keys are blank.
     * A failed send is logged as "Langfuse trace completion failed" and does not
     * throw. Status in the info log is "error" when {@code error} is non-null.
     */
    public static void completeTrace(TraceContext traceContext, String input, String output, String error) {
        if (traceContext == null) {
            return;
        }
        try {
            Config current = config();
            if (!current.enabled()) {
                return;
            }
            Instant end = Instant.now();
            LOGGER.info("Langfuse unique trace id={} name={} status={}",
                    traceContext.traceId(), traceContext.name(), error == null ? "completed" : "error");
            post(current, traceContext.traceId(), MAPPER.writeValueAsString(otlpPayload(
                    traceContext.traceId(),
                    traceContext.rootSpanId(),
                    null,
                    traceContext.name(),
                    "chain",
                    null,
                    input,
                    output,
                    0,
                    0,
                    0,
                    null,
                    0,
                    error,
                    traceContext.startedAt(),
                    end)));
        } catch (Exception exception) {
            LOGGER.warn("Langfuse trace completion failed: {}", exception.getMessage());
        } finally {
            CURRENT_TRACE.remove();
        }
    }

    /**
     * Records one LLM generation on the current thread's trace. If this thread
     * has no trace, the generation is sent as its own trace with no parent span.
     */
    public static void record(
            String name,
            String model,
            String input,
            String output,
            long promptTokens,
            long completionTokens,
            long totalTokens,
            long latencyMillis,
            String responseId,
            int polls,
            String error) {
        record(currentTrace(), name, model, input, output, promptTokens, completionTokens, totalTokens,
                latencyMillis, responseId, polls, error);
    }

    /**
     * Sends one generation span to Langfuse. A blank name becomes "llm".
     * Start time is {@code latencyMillis} before now. A null context creates a
     * new trace id and no parent; otherwise the span hangs off {@code rootSpanId}.
     * Skipped with a debug log when Langfuse is disabled. Send and JSON failures
     * are logged as "Langfuse record failed" and are not rethrown. Token and poll
     * attributes are omitted when {@code model} is blank, which is why a span can
     * appear in Langfuse without usage.
     */
    public static void record(
            TraceContext traceContext,
            String name,
            String model,
            String input,
            String output,
            long promptTokens,
            long completionTokens,
            long totalTokens,
            long latencyMillis,
            String responseId,
            int polls,
            String error) {
        try {
            Config current = config();
            if (!current.enabled()) {
                LOGGER.debug("Langfuse recording skipped because it is disabled");
                return;
            }
            Instant end = Instant.now();
            Instant start = end.minusMillis(Math.max(latencyMillis, 0L));
            String observationName = name == null || name.isBlank() ? "llm" : name;
            String traceId = traceContext == null ? uniqueTraceId() : traceContext.traceId();
            String spanId = uniqueSpanId();
            String parentSpanId = traceContext == null ? null : traceContext.rootSpanId();
            LOGGER.info("Langfuse unique trace id={} generation={} model={} parentSpanId={} latency={} ms tokens={}/{}/{} polls={} status={}",
                    traceId,
                    observationName,
                    model,
                    parentSpanId,
                    latencyMillis,
                    promptTokens,
                    completionTokens,
                    totalTokens,
                    polls,
                    error == null ? "completed" : "error");
            post(current, traceId, MAPPER.writeValueAsString(otlpPayload(
                    traceId,
                    spanId,
                    parentSpanId,
                    observationName,
                    "generation",
                    model,
                    input,
                    output,
                    promptTokens,
                    completionTokens,
                    totalTokens,
                    responseId,
                    polls,
                    error,
                    start,
                    end)));
        } catch (Exception exception) {
            LOGGER.warn("Langfuse record failed: {}", exception.getMessage());
        }
    }

    /**
     * Builds the OTLP/JSON body Langfuse ingests: one resource span for service
     * "transcript-question-extraction", scope "langfuse-integration", and one span.
     * Status is OK unless {@code error} is non-null; a non-blank error is also the
     * status message and replaces output on langfuse.observation.output.
     * Model, token, and poll attributes are added only when {@code model} is set.
     * If Langfuse rejects the body, the warn log from {@link #post} includes it.
     */
    private static ObjectNode otlpPayload(
            String traceId,
            String spanId,
            String parentSpanId,
            String name,
            String observationType,
            String model,
            String input,
            String output,
            long promptTokens,
            long completionTokens,
            long totalTokens,
            String responseId,
            int polls,
            String error,
            Instant start,
            Instant end) {
        ObjectNode payload = MAPPER.createObjectNode();
        ObjectNode resourceSpan = payload.putArray("resourceSpans").addObject();
        ObjectNode resource = resourceSpan.putObject("resource");
        resource.putArray("attributes").add(stringAttribute("service.name", "transcript-question-extraction"));
        ObjectNode scopeSpan = resourceSpan.putArray("scopeSpans").addObject();
        scopeSpan.putObject("scope").put("name", "langfuse-integration");
        ObjectNode span = scopeSpan.putArray("spans").addObject();
        span.put("traceId", traceId);
        span.put("spanId", spanId);
        if (parentSpanId != null) {
            span.put("parentSpanId", parentSpanId);
        }
        span.put("name", name);
        span.put("kind", "SPAN_KIND_INTERNAL");
        span.put("startTimeUnixNano", unixNanos(start));
        span.put("endTimeUnixNano", unixNanos(end));
        span.putObject("status").put("code", error == null ? "STATUS_CODE_OK" : "STATUS_CODE_ERROR");
        if (error != null && !error.isBlank()) {
            span.withObject("status").put("message", error);
        }
        var attributes = span.putArray("attributes");
        attributes.add(stringAttribute("langfuse.observation.type", observationType));
        attributes.add(stringAttribute("langfuse.trace.name", name));
        attributes.add(stringAttribute("langfuse.observation.input", input));
        attributes.add(stringAttribute("langfuse.observation.output", error == null ? output : error));
        if (model != null && !model.isBlank()) {
            attributes.add(stringAttribute("gen_ai.request.model", model));
            attributes.add(longAttribute("gen_ai.usage.input_tokens", promptTokens));
            attributes.add(longAttribute("gen_ai.usage.output_tokens", completionTokens));
            attributes.add(longAttribute("langfuse.observation.usage_details.total", totalTokens));
            attributes.add(longAttribute("langfuse.observation.metadata.polls", polls));
        }
        if (responseId != null && !responseId.isBlank()) {
            attributes.add(stringAttribute("langfuse.observation.metadata.responseId", responseId));
        }
        return payload;
    }

    /** OTLP string attribute. A null value is sent as an empty string so the key is still present. */
    private static ObjectNode stringAttribute(String key, String value) {
        ObjectNode attribute = MAPPER.createObjectNode();
        attribute.put("key", key);
        attribute.putObject("value").put("stringValue", value == null ? "" : value);
        return attribute;
    }

    /**
     * OTLP integer attribute. The value is a decimal string because the OTLP JSON
     * mapping encodes intValue that way. A schema rejection usually means this
     * field was sent as a JSON number instead.
     */
    private static ObjectNode longAttribute(String key, long value) {
        ObjectNode attribute = MAPPER.createObjectNode();
        attribute.put("key", key);
        attribute.putObject("value").put("intValue", Long.toString(value));
        return attribute;
    }

    /**
     * Epoch nanoseconds as a decimal string for startTimeUnixNano and endTimeUnixNano.
     * Throws ArithmeticException if the instant cannot be represented; callers log that.
     */
    private static String unixNanos(Instant instant) {
        return Long.toString(Math.addExact(
                Math.multiplyExact(instant.getEpochSecond(), 1_000_000_000L),
                instant.getNano()));
    }

    /** 32-character hex trace id (UUID with dashes removed), the OTLP 16-byte trace id. */
    private static String uniqueTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** 16-character hex span id (first half of a new UUID), the OTLP 8-byte span id. */
    private static String uniqueSpanId() {
        return uniqueTraceId().substring(0, 16);
    }

    /**
     * POSTs the OTLP body to the ingestion URL with Basic auth, a 5-second timeout,
     * and header x-langfuse-ingestion-version: 4. A 2xx response is logged at info.
     * Any other status is logged at warn with the response body, which is the place
     * to read auth failures (401), a wrong path (404), and schema rejections.
     * Network and timeout errors propagate to the caller.
     */
    private static void post(Config current, String traceId, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(current.ingestionUrl()))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Basic " + current.basicAuth())
                .header("Content-Type", "application/json")
                .header("x-langfuse-ingestion-version", "4")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = current.httpClient().send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 == 2) {
            LOGGER.info("Langfuse unique trace id={} ingestion status={}", traceId, response.statusCode());
        } else {
            LOGGER.warn("Langfuse unique trace id={} ingestion status={} body={}", traceId, response.statusCode(), response.body());
        }
    }

    /**
     * Returns the process-wide config, loading it once on first use.
     * Keys and host are not reread after that; a change needs a restart.
     * If the first load throws, every later call throws the same way until one succeeds.
     */
    private static Config config() {
        Config current = config;
        if (current == null) {
            synchronized (LangfuseIntegration.class) {
                current = config;
                if (current == null) {
                    current = loadConfig();
                    config = current;
                }
            }
        }
        return current;
    }

    /**
     * Reads the public key, secret key, and host. Recording is enabled only when
     * both keys are non-blank. Host defaults to https://cloud.langfuse.com.
     * Startup logs either "recording enabled" (with host and HTTP version) or
     * "recording disabled because langfuse.publicKey or langfuse.secretKey is blank".
     * Auth is Base64 of "publicKey:secretKey".
     */
    private static Config loadConfig() {
        Properties properties = applicationProperties();
        String publicKey = value(properties, "langfuse.publicKey", "LANGFUSE_PUBLIC_KEY", "");
        String secretKey = value(properties, "langfuse.secretKey", "LANGFUSE_SECRET_KEY", "");
        String host = value(properties, "langfuse.host", "LANGFUSE_HOST", "https://cloud.langfuse.com");
        boolean enabled = !publicKey.isBlank() && !secretKey.isBlank();
        HttpClient client = httpClient(host);
        if (enabled) {
            LOGGER.info("Langfuse recording enabled for {} using {}", host, client.version());
        } else {
            LOGGER.info("Langfuse recording disabled because langfuse.publicKey or langfuse.secretKey is blank");
        }
        String credentials = publicKey + ":" + secretKey;
        String basicAuth = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        return new Config(enabled, tracesUrl(host), basicAuth, client);
    }

    /**
     * Shared client with a 3-second connect timeout. An http:// host (local Langfuse)
     * is forced to HTTP/1.1; https keeps the platform default, usually HTTP/2.
     * Protocol errors against a local server usually come from this choice.
     */
    private static HttpClient httpClient(String host) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3));
        if (host != null && host.trim().regionMatches(true, 0, "http://", 0, "http://".length())) {
            builder.version(HttpClient.Version.HTTP_1_1);
        }
        return builder.build();
    }

    /**
     * Ingestion URL. Trailing slashes are stripped. A host that already ends with
     * /api/public/otel/v1/traces is kept; anything else gets that path appended.
     * A 404 on ingest usually means this URL does not match the running Langfuse.
     */
    private static String tracesUrl(String host) {
        String trimmed = host == null ? "" : host.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.endsWith("/api/public/otel/v1/traces")) {
            return trimmed;
        }
        return trimmed + "/api/public/otel/v1/traces";
    }

    /**
     * Loads classpath /application.properties. A missing file yields empty properties
     * so environment variables can still enable recording. A read error throws
     * IllegalStateException "Could not read application.properties".
     */
    private static Properties applicationProperties() {
        Properties properties = new Properties();
        try (InputStream input = LangfuseIntegration.class.getResourceAsStream("/application.properties")) {
            if (input != null) {
                properties.load(input);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read application.properties", exception);
        }
        return properties;
    }

    /**
     * Resolves one setting: a non-blank environment variable wins, then the
     * property, then {@code defaultValue}. Env and property values are trimmed.
     * If a key looks set but Langfuse stays disabled, check for a blank override.
     */
    private static String value(Properties properties, String propertyKey, String envKey, String defaultValue) {
        String fromEnv = System.getenv(envKey);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv.trim();
        }
        String fromProperty = properties.getProperty(propertyKey);
        if (fromProperty != null && !fromProperty.isBlank()) {
            return fromProperty.trim();
        }
        return defaultValue;
    }

    /** Cached settings from the first {@link #loadConfig()} call. */
    private record Config(boolean enabled, String ingestionUrl, String basicAuth, HttpClient httpClient) {
    }

    /**
     * Handle returned by {@link #startTrace}. Pass it into {@link #record} and
     * {@link #completeTrace} when work leaves the thread that started the trace,
     * because {@link #CURRENT_TRACE} is not shared across threads.
     */
    public record TraceContext(String traceId, String rootSpanId, String name, Instant startedAt) {
    }
}
