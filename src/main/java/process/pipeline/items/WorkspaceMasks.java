package process.pipeline.items;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import process.correlation.CorrelationInterceptor;
import process.pipeline.PipelineDefinition;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * MIG-424: a workspace's masks when no person reads -- a workflow request keeping a pipeline's answer in its history, the
 * customer API answering a run's result. analytics-service's GET /internal/analytics/catalog/sensitive?tenantId= (the
 * internal service token; read as that one workspace) lists every sensitive asset of the workspace, whoever may see it:
 * their tagged columns and pattern kinds are masked, as for a member with access to none of them. A pipeline marked
 * sensitive has its rows' person columns read too. Unreadable (no token, analytics away): fail closed.
 */
@Component
public class WorkspaceMasks {

    private static final Logger logger = LoggerFactory.getLogger(WorkspaceMasks.class);

    private final HttpUrl url;
    private final String serviceToken;
    private final ObjectMapper json = new ObjectMapper();
    private final OkHttpClient http = new OkHttpClient.Builder()
        .addInterceptor(new CorrelationInterceptor())
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build();

    public WorkspaceMasks(@Value("${analytics.url:http://analytics:9140}") String analyticsUrl,
        @Value("${internal.service-token:}") String serviceToken) {
        this.url = HttpUrl.parse(analyticsUrl.replaceAll("/+$", "") + "/api/v1/internal/analytics/catalog/sensitive");
        this.serviceToken = serviceToken == null ? "" : serviceToken.trim();
    }

    /** The masks for this workspace's data of a pipeline defined so (null: not known, so not marked sensitive). */
    public ItemMasks of(long tenantId, PipelineDefinition definition) {
        boolean sensitive = definition != null && "sensitive".equals(definition.effectiveSettings().effectiveSensitivity());
        return ItemMasks.forWorkspace(this.sensitiveAssets(tenantId), sensitive);
    }

    /** The data array of the service read; null when it cannot be read (the caller then fails closed). */
    JsonNode sensitiveAssets(long tenantId) {
        if (this.url == null || this.serviceToken.isEmpty()) {
            logger.warn("No internal service token or analytics address: a result of workspace {} is masked fail-closed.", tenantId);
            return null;
        }
        Request call = new Request.Builder().url(this.url.newBuilder().addQueryParameter("tenantId", String.valueOf(tenantId)).build())
            .header("X-Internal-Token", this.serviceToken).get().build();
        try (Response response = this.http.newCall(call).execute()) {
            String text = response.body() == null ? "" : response.body().string();
            JsonNode answer = text.isEmpty() ? null : this.json.readTree(text);
            if (response.code() != 200 || answer == null || !"SUCCESS".equals(answer.path("status").asText())) {
                logger.warn("Workspace {}'s sensitive assets could not be read (HTTP {}); its result is masked fail-closed.", tenantId,
                    response.code());
                return null;
            }
            return answer.path("data");
        } catch (IOException unreachable) {
            logger.warn("The Data Catalog could not be reached for workspace {}'s masks ({}); its result is masked fail-closed.", tenantId,
                unreachable.getMessage());
            return null;
        }
    }
}
