package process.pipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import process.ai.AiPort;
import process.pipeline.backing.IntegrationNames;
import process.pipeline.backing.ReportTemplates;
import process.pipeline.registry.TaskSpec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.io.IOException;

/**
 * AI hard-workflow test (2026-10-13): what a definition's steps name in other services is there, is the workspace's, and is
 * of the kind the setting wants -- checked when a pipeline is saved (and when a draft is validated), so it never reaches a
 * run. Read from the Task Registry (a setting's schema format: {@link StepReferences#PROMPT}, {@link
 * StepReferences#API_REQUEST}, {@link StepReferences#REPORT_TEMPLATE}), never from task names:
 *
 * <ul>
 *   <li>a prompt (ai-service) of the workspace or the platform, active;</li>
 *   <li>a report template (media-service) of the workspace -- a prompt's id as a templateId is refused, naming the prompt;</li>
 *   <li>a saved API request (integration-service) of the workspace, and -- when the step runs it with the collection's
 *       default environment -- every {{variable}} that environment leaves open set in the step's variables. A run of a step
 *       without one failed three times with "Unresolved variable(s): loinc".</li>
 * </ul>
 *
 * A service that cannot answer is not a refusal: the save stands, logged, and the run checks again.
 */
@Component
public class StepResourceCheck {

    private final Logger logger = LoggerFactory.getLogger(StepResourceCheck.class);

    private final StepTasks tasks;
    private AiPort ai;
    private IntegrationNames integration;
    private ReportTemplates templates;

    public StepResourceCheck(StepTasks tasks) {
        this.tasks = tasks;
    }

    @Autowired(required = false)
    public void useAi(AiPort ai) {
        this.ai = ai;
    }

    @Autowired(required = false)
    public void useIntegration(IntegrationNames integration) {
        this.integration = integration;
    }

    @Autowired(required = false)
    public void useTemplates(ReportTemplates templates) {
        this.templates = templates;
    }

    /** One setting naming a resource: the step's index, the setting, the id. */
    private static final class Named {
        final int step;
        final String property;
        final long id;
        final Map<String, Object> config;

        Named(int step, String property, long id, Map<String, Object> config) {
            this.step = step;
            this.property = property;
            this.id = id;
            this.config = config;
        }

        String at() {
            return "steps[" + this.step + "].config." + this.property;
        }
    }

    /** The definition's problems with what it names in this workspace; empty when all is there (or nobody could say). */
    public List<DefinitionProblem> problems(PipelineDefinition definition, Long tenantId) {
        List<DefinitionProblem> problems = new ArrayList<>();
        if (definition == null || definition.isLegacy() || definition.getSteps() == null || tenantId == null || tenantId <= 0) {
            return problems;
        }
        Map<String, List<Named>> byFormat = new LinkedHashMap<>();
        List<PipelineDefinition.Step> steps = definition.getSteps();
        for (int i = 0; i < steps.size(); i++) {
            PipelineDefinition.Step step = steps.get(i);
            Optional<StepTask> task = step == null ? Optional.empty() : this.tasks.find(step.getTask());
            if (!task.isPresent()) {
                continue;
            }
            TaskSpec spec = task.get().spec();
            for (String format : new String[] {StepReferences.PROMPT, StepReferences.REPORT_TEMPLATE, StepReferences.API_REQUEST}) {
                for (String property : StepReferences.propertiesOfFormat(spec, format)) {
                    Long id = StepReferences.idOf(step.effectiveConfig().get(property));
                    if (id != null) {
                        byFormat.computeIfAbsent(format, f -> new ArrayList<>()).add(new Named(i, property, id, step.effectiveConfig()));
                    }
                }
            }
        }
        if (byFormat.isEmpty()) {
            return problems;
        }
        // Prompts are read for the template ids too, so a prompt's id where a template belongs is named as the prompt it is.
        Set<Long> promptIds = new LinkedHashSet<>();
        for (String format : new String[] {StepReferences.PROMPT, StepReferences.REPORT_TEMPLATE}) {
            byFormat.getOrDefault(format, Collections.emptyList()).forEach(n -> promptIds.add(n.id));
        }
        Map<Long, AiPort.PromptInfo> prompts = null;
        if (this.ai != null && !promptIds.isEmpty()) {
            try {
                prompts = this.ai.prompts(promptIds);
            } catch (AiPort.AiUnavailableException | RuntimeException unanswered) {
                this.logger.info("The prompts a pipeline names were not checked: {}", unanswered.getMessage());
            }
        }
        if (prompts != null) {
            for (Named n : byFormat.getOrDefault(StepReferences.PROMPT, Collections.emptyList())) {
                AiPort.PromptInfo prompt = prompts.get(n.id);
                boolean ours = prompt != null && (prompt.tenantId == null || prompt.tenantId.equals(tenantId))
                    && (prompt.status == null || "Active".equalsIgnoreCase(prompt.status));
                if (!ours) {
                    problems.add(new DefinitionProblem(n.at(), String.format("%d is not an active AI prompt of this workspace", n.id)));
                }
            }
        }
        List<Named> templateRefs = byFormat.getOrDefault(StepReferences.REPORT_TEMPLATE, Collections.emptyList());
        if (this.templates != null && !templateRefs.isEmpty()) {
            Set<Long> ids = new LinkedHashSet<>();
            templateRefs.forEach(n -> ids.add(n.id));
            try {
                Map<Long, String> found = this.templates.templatesOf(tenantId, ids);
                for (Named n : templateRefs) {
                    if (!found.containsKey(n.id)) {
                        AiPort.PromptInfo prompt = prompts == null ? null : prompts.get(n.id);
                        boolean aPrompt = prompt != null && (prompt.tenantId == null || prompt.tenantId.equals(tenantId));
                        problems.add(new DefinitionProblem(n.at(), String.format("%d is not a report template of this workspace%s; leave it "
                            + "empty for the default layout", n.id, aPrompt ? " (it is the AI prompt \"" + prompt.name + "\")" : "")));
                    }
                }
            } catch (IOException | RuntimeException unanswered) {
                this.logger.info("The report templates a pipeline names were not checked: {}", unanswered.getMessage());
            }
        }
        List<Named> requestRefs = byFormat.getOrDefault(StepReferences.API_REQUEST, Collections.emptyList());
        if (this.integration != null && !requestRefs.isEmpty()) {
            Set<Long> ids = new LinkedHashSet<>();
            requestRefs.forEach(n -> ids.add(n.id));
            try {
                IntegrationNames.Names names = this.integration.describe(tenantId, ids, Collections.<Long>emptyList());
                for (Named n : requestRefs) {
                    IntegrationNames.RequestName request = names.requests.get(n.id);
                    if (request == null) {
                        problems.add(new DefinitionProblem(n.at(), String.format("%d is not a saved API request of this workspace", n.id)));
                        continue;
                    }
                    if (n.config.get("environmentId") != null) {
                        // Another environment may define what the default one leaves open: the run says.
                        continue;
                    }
                    Object given = n.config.get("variables");
                    for (String needed : request.needsVariables) {
                        Object value = given instanceof Map ? ((Map<?, ?>) given).get(needed) : null;
                        if (value == null || String.valueOf(value).trim().isEmpty()) {
                            problems.add(new DefinitionProblem("steps[" + n.step + "].config.variables." + needed, String.format("the API request %d "
                                + "\"%s\" uses {{%s}}, which its environment does not set: give it a value here (or {{column}} from the rows)",
                                n.id, request.name, needed)));
                        }
                    }
                }
            } catch (IOException | RuntimeException unanswered) {
                this.logger.info("The API requests a pipeline names were not checked: {}", unanswered.getMessage());
            }
        }
        return problems;
    }
}
