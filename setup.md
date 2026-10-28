## 1. Start Langfuse

From this directory:

```
docker compose up
```

Open http://localhost:3000 and sign up.

Create an organization, then a project.

In the project, create API keys. Copy the public key (`pk-lf-...`) and the secret key (`sk-lf-...`).

These keys belong to the Java app. They are not the passwords in `docker-compose.yml`.

## 2. Point the Java app at this Langfuse

In the other project's `application.properties`:

```properties
langfuse.publicKey=
langfuse.secretKey=
langfuse.host=http://localhost:3000
```

`langfuse.host` is the Langfuse base URL only. `LangfuseIntegration` appends `/api/public/otel/v1/traces`.

These environment variables override the file when they are set:

- `LANGFUSE_PUBLIC_KEY`
- `LANGFUSE_SECRET_KEY`
- `LANGFUSE_HOST`

A blank public key or secret key logs `Langfuse recording disabled` and sends nothing.

## 3. Add LangfuseIntegration

Copy `LangfuseIntegration.java` into the Java project and change its package. The class needs Jackson and SLF4J. A Spring Boot service already has both. No Langfuse SDK is required.

Langfuse v4 accepts this data at `/api/public/otel/v1/traces`. Do not post `trace-create` or `generation-create` to `/api/public/ingestion`.

## 4. Record one trace per request

Call `startTrace` once, at the API or workflow boundary, before any model call:

```java
LangfuseIntegration.TraceContext trace =
        LangfuseIntegration.startTrace("TranscriptQuestionWorkflow");
String requestId = trace.traceId();
```

`traceId()` is a UUID with the dashes removed. Log that value. The same id is printed by `LangfuseIntegration` and shown on the Langfuse trace.

If the LLM client runs on another thread, read the trace on the request thread and pass it into the call:

```java
LangfuseIntegration.TraceContext trace = LangfuseIntegration.currentTrace();
return Flowable.fromCallable(() -> complete(llmRequest, trace));
```

Call `record` once after each model call, including when it fails. Pass `null` for `error` on success. Put it in a `finally` block. A Langfuse error is logged and does not fail the model call.

```java
LangfuseIntegration.record(
        trace,
        agentName,
        model,
        input,
        output,
        promptTokens,
        completionTokens,
        totalTokens,
        latencyMillis,
        responseId,
        polls,
        error);
```

`input` and `output` are strings. Use `0` for token counts the provider does not return. Use `null` and `0` for `responseId` and `polls` on a one-shot request.

Call `completeTrace` once when the request ends, on both the success path and the failure path:

```java
LangfuseIntegration.completeTrace(trace, input, output, null);
```

On failure:

```java
LangfuseIntegration.completeTrace(trace, input, null, exception.getMessage());
```

`input` is the overall request text. `output` is the overall result. This call also removes the trace from the current thread.

### Example

The controller owns the trace. The LLM client only records the model call.

```java
// Controller
LangfuseIntegration.TraceContext trace =
        LangfuseIntegration.startTrace("TranscriptQuestionWorkflow");
try {
    String output = llmClient.complete(trace, requestText);
    LangfuseIntegration.completeTrace(trace, requestText, output, null);
    return output;
} catch (Exception exception) {
    LangfuseIntegration.completeTrace(trace, requestText, null, exception.getMessage());
    throw exception;
}
```

```java
// LLM client
String complete(LangfuseIntegration.TraceContext trace, String input) {
    long started = System.currentTimeMillis();
    String output = null;
    String error = null;
    try {
        output = callModel(input);
        return output;
    } catch (RuntimeException exception) {
        error = exception.getMessage();
        throw exception;
    } finally {
        LangfuseIntegration.record(
                trace, "DiscussionContextAgent", "gpt-4o",
                input, output, 0, 0, 0,
                System.currentTimeMillis() - started, null, 0, error);
    }
}
```

## 5. Confirm the trace

Run one request, then open the project in Langfuse and select Tracing. One request appears as one trace:

```text
TranscriptQuestionWorkflow          chain
  DiscussionContextAgent            generation
  TranscriptExtractionAgent         generation
  MissedQuestionAgent               generation
  ExtractionVerificationAgent       generation
```

Separate traces for each agent mean `startTrace` did not cover those model calls, or the trace was not passed into the thread that called `record`.

The application log for a successful send looks like this:

```text
Langfuse recording enabled for http://localhost:3000 using HTTP_1_1
Langfuse unique trace id=4f0c1c2a8b7d4e6f9a1b2c3d4e5f6071 name=TranscriptQuestionWorkflow
Langfuse unique trace id=4f0c1c2a8b7d4e6f9a1b2c3d4e5f6071 generation=DiscussionContextAgent ...
Langfuse unique trace id=4f0c1c2a8b7d4e6f9a1b2c3d4e5f6071 ingestion status=200
Langfuse unique trace id=4f0c1c2a8b7d4e6f9a1b2c3d4e5f6071 name=TranscriptQuestionWorkflow status=completed
```

If those lines show status `200` and the UI is still empty, check `langfuse-worker`. The UI reads traces from ClickHouse, so a worker that failed ClickHouse authentication never displays them. The Langfuse web and worker password must be the same value, and ClickHouse applies that password only when its data volume is first created.

## 6. Playground with an Azure Cognitive Services key

This is separate from the Java keys. No compose change is required.

In Project Settings, open LLM Connections and add a key:

- Provider: Azure OpenAI
- API key: the Cognitive Services key
- API Base URL: `https://<your-resource>.openai.azure.com/openai/deployments`
- Custom models: the Azure deployment name

If the portal shows a `cognitiveservices.azure.com` endpoint, use `https://<your-resource>.cognitiveservices.azure.com/openai/deployments`.

In Playground, select that connection and deployment. Add a prompt variable such as `{{input}}`, change its value, and run. The deployment name is the name in Azure, which can differ from the model name.
