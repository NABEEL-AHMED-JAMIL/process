package process.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import process.model.dto.ResponseDto;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The contract Core reads ai-service's step run by (MIG-242): what Core sends on /internal/ai/steps/run (modelProfile,
 * sourceTaskId), the answer's field names Core reads -- InternalAiRestApi.stepShape's -- and ai-service's refusal of a
 * model (HTTP 422, {status:"ERROR", message}), which Core reads as a refused step carrying that message. Against a real
 * HTTP server standing in for ai-service, speaking exactly its shapes.
 *
 * The names are pinned twice: here, by the answer this test serves, and -- when ai-service's source is checked out
 * beside process (../ai-service, or AI_SERVICE_SOURCE) -- against ai-service's own code, so a rename on either side
 * fails this build rather than silently reading null.
 */
class StepAnswerContractTest {

    /** Every field of the step answer Core reads, as ai-service's stepShape puts them. */
    static final List<String> STEP_ANSWER_FIELDS = Arrays.asList("status", "output", "error", "promptId", "promptName",
        "promptVersion", "latencyMs", "tokensIn", "tokensOut", "reused", "model", "connectionId", "modelOptionId", "modelChoice");

    /** The fields Core adds to each verify-callback worker step, as ai-service's RunVerdict reads them. */
    static final List<String> WORKER_STEP_FIELDS = Arrays.asList("stepTag", "promptId", "modelProfile", "sourceTaskId");

    private static final String NOT_ALLOWED = "That model is not one this step may run on. Pick one from the step's allowed models.";

    private final ObjectMapper json = new ObjectMapper();
    private HttpServer server;
    private final List<String> paths = Collections.synchronizedList(new ArrayList<>());
    private final List<JsonNode> bodies = Collections.synchronizedList(new ArrayList<>());
    private final List<String> authorizations = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Integer> statusByPath = new HashMap<>();
    private final Map<String, String> answerByPath = new HashMap<>();

    @BeforeEach
    void listen() throws IOException {
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            this.paths.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            this.authorizations.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            byte[] in = readAll(exchange.getRequestBody());
            this.bodies.add(in.length == 0 ? null : this.json.readTree(in));
            byte[] out = this.answerByPath.getOrDefault(path, "{\"status\":404,\"error\":\"Not Found\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(this.statusByPath.getOrDefault(path, 404), out.length);
            try (OutputStream o = exchange.getResponseBody()) {
                o.write(out);
            }
        });
        this.server.start();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer user-token");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void stop() {
        this.server.stop(0);
        RequestContextHolder.resetRequestAttributes();
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int n;
        while ((n = in.read(chunk)) > 0) {
            out.write(chunk, 0, n);
        }
        return out.toByteArray();
    }

    private void answers(String path, int status, String body) {
        this.statusByPath.put(path, status);
        this.answerByPath.put(path, body);
    }

    private HttpAi ai() {
        return new HttpAi("http://127.0.0.1:" + this.server.getAddress().getPort(), "service-token");
    }

    /** ai-service's stepShape for a step that ran on an override, field for field. */
    private static final String ANSWER = "{\"status\":\"ok\",\"output\":\"a summary\",\"error\":null,\"promptId\":1049,"
        + "\"promptName\":\"Summarise\",\"promptVersion\":7,\"latencyMs\":812,\"tokensIn\":120,\"tokensOut\":33,\"reused\":false,"
        + "\"model\":\"llama3.1:8b\",\"connectionId\":1003,\"modelOptionId\":1204,\"modelChoice\":\"override\"}";

    @Test
    void theStepAsksForTheRunsModelAndNamesItsPipeline() {
        this.answers("/api/v1/internal/ai/steps/run", 200, ANSWER);

        this.ai().runStep(2905L, 5073L, "summary", 1049L, Collections.singletonMap("text", "notes"), "1204", 8801L);

        JsonNode sent = this.bodies.get(0);
        assertThat(this.paths.get(0)).isEqualTo("POST /api/v1/internal/ai/steps/run");
        assertThat(sent.get("modelProfile").asText()).isEqualTo("1204");
        assertThat(sent.get("sourceTaskId").asLong()).isEqualTo(8801L);
        assertThat(sent.get("tenantId").asLong()).isEqualTo(2905L);
        assertThat(sent.get("stepTag").asText()).isEqualTo("summary");
    }

    @Test
    void aStepOnItsDefaultNamesNoModel() {
        this.answers("/api/v1/internal/ai/steps/run", 200, ANSWER);

        this.ai().runStep(2905L, 5073L, "summary", 1049L, Collections.emptyMap(), null, null);

        assertThat(this.bodies.get(0).has("modelProfile")).isFalse();
        assertThat(this.bodies.get(0).has("sourceTaskId")).isFalse();
    }

    @Test
    void everyFieldOfTheAnswerIsRead() throws Exception {
        this.answers("/api/v1/internal/ai/steps/run", 200, ANSWER);

        AiPort.StepResult r = this.ai().runStep(2905L, 5073L, "summary", 1049L, Collections.emptyMap(), "1204", 8801L);

        assertThat(r.ok()).isTrue();
        assertThat(r.refused).isFalse();
        assertThat(r.output).isEqualTo("a summary");
        assertThat(r.promptId).isEqualTo(1049L);
        assertThat(r.promptName).isEqualTo("Summarise");
        assertThat(r.promptVersion).isEqualTo(7);
        assertThat(r.latencyMs).isEqualTo(812);
        assertThat(r.tokensIn).isEqualTo(120);
        assertThat(r.tokensOut).isEqualTo(33);
        assertThat(r.model).isEqualTo("llama3.1:8b");
        assertThat(r.connectionId).isEqualTo(1003L);
        assertThat(r.modelOptionId).isEqualTo(1204L);
        assertThat(r.modelChoice).isEqualTo("override");
        // And the answer served here is exactly the pinned field list: nothing Core reads is missing from it.
        List<String> served = new ArrayList<>();
        this.json.readTree(ANSWER).fieldNames().forEachRemaining(served::add);
        assertThat(served).containsExactlyInAnyOrderElementsOf(STEP_ANSWER_FIELDS);
    }

    @Test
    void aStepFromBeforeModelChoiceReadsAsNoModel() {
        this.answers("/api/v1/internal/ai/steps/run", 200, "{\"status\":\"ok\",\"output\":\"x\",\"promptVersion\":2,\"reused\":true}");

        AiPort.StepResult r = this.ai().runStep(2905L, 5073L, "summary", 1049L, Collections.emptyMap(), null, null);

        assertThat(r.ok()).isTrue();
        assertThat(r.reused).isTrue();
        assertThat(r.model).isNull();
        assertThat(r.modelOptionId).isNull();
        assertThat(r.modelChoice).isNull();
    }

    /** 422 is a refusal of the model: a failed step marked refused, in ai-service's own words, and not "unreachable". */
    @Test
    void aRefusedModelIsARefusedStepWithAiServicesMessage() {
        this.answers("/api/v1/internal/ai/steps/run", 422, "{\"status\":\"ERROR\",\"message\":\"" + NOT_ALLOWED + "\"}");

        AiPort.StepResult r = this.ai().runStep(2905L, 5073L, "summary", 1049L, Collections.emptyMap(), "9999", 8801L);

        assertThat(r.ok()).isFalse();
        assertThat(r.refused).isTrue();
        assertThat(r.error).isEqualTo(NOT_ALLOWED);
        assertThat(this.paths).as("asked once").hasSize(1);
    }

    @Test
    void anyOtherErrorIsAFailedStepNotARefusal() {
        this.answers("/api/v1/internal/ai/steps/run", 500, "{\"status\":\"ERROR\",\"message\":\"boom\"}");

        AiPort.StepResult r = this.ai().runStep(2905L, 5073L, "summary", 1049L, Collections.emptyMap(), null, null);

        assertThat(r.ok()).isFalse();
        assertThat(r.refused).isFalse();
        assertThat(r.error).isEqualTo("The AI service refused the step: boom");
    }

    // ---- a step's allowed list ----------------------------------------------------------------------------------

    private static final String OPTIONS = "{\"status\":\"SUCCESS\",\"message\":\"2 allowed model(s).\",\"data\":["
        + "{\"modelOptionId\":1204,\"connectionId\":1003,\"connectionName\":\"Local Ollama\",\"provider\":\"ollama\",\"model\":\"llama3.1:8b\","
        + "\"effectiveModel\":\"llama3.1:8b\",\"isDefault\":true,\"connectionActive\":true},"
        + "{\"modelOptionId\":1205,\"connectionId\":1004,\"connectionName\":\"Hosted\",\"provider\":\"openai\","
        + "\"effectiveModel\":\"gpt-4o-mini\",\"isDefault\":false,\"connectionActive\":false}]}";

    @Test
    void theStepsListIsAskedForInTheJobsWorkspaceWithTheTokenAlone() throws Exception {
        this.answers("/api/v1/internal/ai/steps/modelOptions", 200, OPTIONS);

        List<AiPort.ModelOption> options = this.ai().stepModelOptions(2905L, 8801L, "summary", 1049L);

        assertThat(this.paths).containsExactly("POST /api/v1/internal/ai/steps/modelOptions");
        JsonNode sent = this.bodies.get(0);
        assertThat(sent.get("tenantId").asLong()).isEqualTo(2905L);
        assertThat(sent.get("sourceTaskId").asLong()).isEqualTo(8801L);
        assertThat(sent.get("stepKey").asText()).isEqualTo("summary");
        assertThat(sent.get("promptId").asLong()).isEqualTo(1049L);
        assertThat(this.authorizations.get(0)).as("no caller token on a token-only call").isEqualTo("null");
        assertThat(options).extracting(o -> o.modelOptionId).containsExactly(1204L, 1205L);
        assertThat(options.get(0).isDefault).isTrue();
        assertThat(options.get(1).connectionActive).isFalse();
        assertThat(options.get(1).effectiveModel).isEqualTo("gpt-4o-mini");
    }

    /**
     * Until ai-service has the internal read, the prompt's own list as the signed-in caller reads it -- exactly the list
     * a run chooses from, since no step can have its own list before ai-service can keep one.
     */
    @Test
    void withoutAiServicesStepReadThePromptsListIsReadAsTheCaller() throws Exception {
        this.answers("/api/v1/aiPrompt.json/modelOptions", 200, OPTIONS);

        List<AiPort.ModelOption> options = this.ai().stepModelOptions(2905L, 8801L, "summary", 1049L);

        assertThat(this.paths).containsExactly("POST /api/v1/internal/ai/steps/modelOptions",
            "GET /api/v1/aiPrompt.json/modelOptions?promptId=1049");
        assertThat(this.authorizations.get(1)).isEqualTo("Bearer user-token");
        assertThat(options).extracting(o -> o.modelOptionId).containsExactly(1204L, 1205L);
    }

    @Test
    void aPromptAiServiceDoesNotFindForTheCallerHasNoModels() throws Exception {
        this.answers("/api/v1/aiPrompt.json/modelOptions", 200, "{\"status\":\"ERROR\",\"message\":\"Prompt not found with 1049.\"}");

        assertThat(this.ai().stepModelOptions(2905L, 8801L, "summary", 1049L)).isEmpty();
    }

    @Test
    void savingAStepsListSendsItAndRelaysTheAnswer() throws Exception {
        this.answers("/api/v1/internal/ai/steps/modelOptions/save", 200, OPTIONS);
        AiPort.ModelOption local = new AiPort.ModelOption();
        local.connectionId = 1003L; local.model = "llama3.1:8b"; local.isDefault = true;

        ResponseDto answer = this.ai().saveStepModelOptions(2905L, 8801L, "summary", 1049L, Collections.singletonList(local), 7602L);

        JsonNode sent = this.bodies.get(0);
        assertThat(sent.get("tenantId").asLong()).isEqualTo(2905L);
        assertThat(sent.get("updatedBy").asLong()).isEqualTo(7602L);
        assertThat(sent.get("options").get(0).get("connectionId").asLong()).isEqualTo(1003L);
        assertThat(sent.get("options").get(0).get("model").asText()).isEqualTo("llama3.1:8b");
        assertThat(sent.get("options").get(0).get("isDefault").asBoolean()).isTrue();
        assertThat(answer.getStatus()).isEqualTo("SUCCESS");
        assertThat((List<?>) answer.getData()).hasSize(2);
    }

    @Test
    void withoutAiServicesStepWriteTheSaveIsRefusedPlainly() throws Exception {
        ResponseDto answer = this.ai().saveStepModelOptions(2905L, 8801L, "summary", 1049L, Collections.emptyList(), null);

        assertThat(answer.getStatus()).isEqualTo("ERROR");
        assertThat(answer.getMessage()).contains("cannot keep a pipeline step's own model list yet");
    }

    // ---- against ai-service's own code, when it is checked out beside process -----------------------------------

    private static Path aiServiceSource() {
        String configured = System.getenv("AI_SERVICE_SOURCE");
        Path root = Paths.get(configured != null ? configured : "../ai-service").resolve("src/main/java/org/barco/ai/service");
        return Files.isDirectory(root) ? root : null;
    }

    @Test
    void aiServicesStepAnswerPutsEveryFieldCoreReads() throws Exception {
        Path root = aiServiceSource();
        assumeTrue(root != null, "ai-service's source is not beside process");
        String api = new String(Files.readAllBytes(root.resolve("api/InternalAiRestApi.java")), StandardCharsets.UTF_8);
        for (String field : STEP_ANSWER_FIELDS) {
            assertThat(api).as("stepShape puts %s", field).contains("shape.put(\"" + field + "\"");
        }
        assertThat(api).as("the request fields Core sends").contains("public String modelProfile;").contains("public Long sourceTaskId;");
        String advice = new String(Files.readAllBytes(root.resolve("config/AiExceptionAdvice.java")), StandardCharsets.UTF_8);
        assertThat(advice).as("a refused model is 422").contains("HttpStatus.UNPROCESSABLE_ENTITY");
    }

    @Test
    void aiServicesRunVerdictReadsTheWorkerStepFieldsCoreSends() throws Exception {
        Path root = aiServiceSource();
        assumeTrue(root != null, "ai-service's source is not beside process");
        String verdict = new String(Files.readAllBytes(root.resolve("core/RunVerdict.java")), StandardCharsets.UTF_8);
        for (String field : WORKER_STEP_FIELDS) {
            assertThat(verdict).as("RunVerdict reads %s", field).contains("step.get(\"" + field + "\")");
        }
    }
}
