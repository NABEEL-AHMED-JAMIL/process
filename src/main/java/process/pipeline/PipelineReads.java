package process.pipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import process.pipeline.backing.DocumentDatasets;
import process.pipeline.backing.IntegrationNames;
import process.pipeline.registry.TaskKind;
import process.pipeline.registry.TaskSpec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * UX review 2026-10-13 (I1): what a workspace's pipelines actually read, for Connections › Sources -- which said "0
 * sources" while every pipeline read storage, API Collections, databases or documents straight from its steps.
 *
 * Read from the pipelines' latest definitions and the Task Registry, never from task names: a step reads a place when its
 * config holds a property whose schema {@code format} names one ({@link #PLACES}: a bucket, an API request, a database
 * connection, a document type) and its task does not send data out (an Output task naming a bucket writes to it). A Read
 * task naming no place still reads -- the file that started the run (a storage reader left empty), or rows written in the
 * step -- and is listed as such. So Enrich, which calls an API per row, counts, and a new reader counts the moment its
 * schema says what it reads.
 *
 * Grouped by place: one row per bucket and path, API request, connection or document type, with the pipelines (the
 * console's list: source tasks) and the step keys that read it. Names come from the services that own them
 * (integration-service for requests and connections, ai-service for document types); when one cannot answer, the row is
 * still listed, under its id. Legacy pipelines -- no engine steps -- are left out.
 */
@Component
public class PipelineReads {

    public static final String STORAGE = "storage";
    public static final String API = "api";
    public static final String DATABASE = "database";
    public static final String DOCUMENTS = "documents";
    public static final String OTHER = "other";

    /** Schema format -> the kind of place a property of that format names. */
    static final Map<String, String> PLACES;

    static {
        Map<String, String> places = new LinkedHashMap<>();
        places.put("bucket", STORAGE);
        places.put(StepReferences.API_REQUEST, API);
        places.put("db-connection", DATABASE);
        places.put("document-type", DOCUMENTS);
        PLACES = Collections.unmodifiableMap(places);
    }

    /** The config properties a storage reader keeps its path in, in the order they are looked for. */
    static final List<String> PATH_PROPERTIES = Arrays.asList("key", "prefix");

    /** At most this many document types are named per read (one ai-service call each). */
    static final int MAX_NAMED_TYPES = 50;

    private final Logger logger = LoggerFactory.getLogger(PipelineReads.class);

    private final PipelineDefinitionStore definitions;
    private final StepTasks tasks;
    private IntegrationNames integration;
    private DocumentDatasets documents;

    public PipelineReads(PipelineDefinitionStore definitions, StepTasks tasks) {
        this.definitions = definitions;
        this.tasks = tasks;
    }

    @Autowired(required = false)
    public void useIntegration(IntegrationNames integration) {
        this.integration = integration;
    }

    @Autowired(required = false)
    public void useDocuments(DocumentDatasets documents) {
        this.documents = documents;
    }

    /** One place read, while it is being gathered. */
    static final class Place {
        final String kind;
        final String task;
        final String taskName;
        final Map<String, Object> target = new LinkedHashMap<>();
        String label;
        String link;
        /** taskDetailId -> the pipeline's row: {taskDetailId, taskName, pipelineKey, steps}. */
        final Map<Long, Map<String, Object>> pipelines = new LinkedHashMap<>();

        Place(String kind, TaskSpec spec) {
            this.kind = kind;
            this.task = spec.code();
            this.taskName = spec.name();
        }

        @SuppressWarnings("unchecked")
        void readBy(PipelineDefinitionStore.TaskDefinition pipeline, String stepKey) {
            Map<String, Object> row = this.pipelines.computeIfAbsent(pipeline.taskDetailId, id -> {
                Map<String, Object> made = new LinkedHashMap<>();
                made.put("taskDetailId", pipeline.taskDetailId);
                made.put("taskName", pipeline.taskName);
                made.put("pipelineKey", pipeline.stored.pipelineKey);
                made.put("steps", new ArrayList<String>());
                return made;
            });
            List<String> steps = (List<String>) row.get("steps");
            if (!steps.contains(stepKey)) {
                steps.add(stepKey);
            }
        }

        Map<String, Object> toMap() {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("kind", this.kind);
            row.put("task", this.task);
            row.put("taskName", this.taskName);
            row.put("label", this.label);
            row.put("target", this.target);
            if (this.link != null) {
                row.put("link", this.link);
            }
            row.put("pipelines", new ArrayList<>(this.pipelines.values()));
            return row;
        }
    }

    /**
     * The workspace's reads: {@code {data: [place...], pipelines: n (live step-engine pipelines looked at), reading: n
     * (those that read something)}}, places ordered storage, API, database, documents, other, then by label.
     */
    public Map<String, Object> forWorkspace(long tenantId) {
        List<PipelineDefinitionStore.TaskDefinition> pipelines = this.definitions.latestForTasks(tenantId, null);
        Map<String, Place> places = new LinkedHashMap<>();
        Set<Long> reading = new LinkedHashSet<>();
        int engine = 0;
        for (PipelineDefinitionStore.TaskDefinition pipeline : pipelines) {
            Optional<PipelineDefinition> definition = this.read(pipeline.stored);
            if (!definition.isPresent() || definition.get().isLegacy()) {
                continue;
            }
            engine++;
            for (PipelineDefinition.Step step : definition.get().getSteps()) {
                for (Place found : this.placesOf(step)) {
                    String key = found.kind + "|" + found.target;
                    places.computeIfAbsent(key, k -> found).readBy(pipeline, step.getKey());
                    reading.add(pipeline.taskDetailId);
                }
            }
        }
        this.name(tenantId, places.values());
        List<Place> ordered = new ArrayList<>(places.values());
        List<String> kinds = Arrays.asList(STORAGE, API, DATABASE, DOCUMENTS, OTHER);
        ordered.sort((a, b) -> {
            int byKind = Integer.compare(kinds.indexOf(a.kind), kinds.indexOf(b.kind));
            return byKind != 0 ? byKind : String.CASE_INSENSITIVE_ORDER.compare(String.valueOf(a.label), String.valueOf(b.label));
        });
        List<Map<String, Object>> data = new ArrayList<>();
        ordered.forEach(place -> data.add(place.toMap()));
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("data", data);
        answer.put("pipelines", engine);
        answer.put("reading", reading.size());
        return answer;
    }

    /** The places one step reads; none for a step that only processes or sends what it is given. */
    List<Place> placesOf(PipelineDefinition.Step step) {
        List<Place> found = new ArrayList<>();
        Optional<StepTask> task = step == null ? Optional.empty() : this.tasks.find(step.getTask());
        if (!task.isPresent()) {
            return found;
        }
        TaskSpec spec = task.get().spec();
        if (spec.kind() == TaskKind.OUTPUT || spec.kind() == TaskKind.LEGACY) {
            return found;
        }
        Map<String, Object> config = step.effectiveConfig();
        String emptyKind = null;
        for (Map.Entry<String, String> format : PLACES.entrySet()) {
            for (String property : StepReferences.propertiesOfFormat(spec, format.getKey())) {
                Object value = config.get(property);
                Place place = this.place(format.getValue(), spec, value, config);
                if (place != null) {
                    found.add(place);
                } else if (emptyKind == null) {
                    emptyKind = format.getValue();
                }
            }
        }
        if (found.isEmpty() && spec.kind() == TaskKind.READ) {
            // A reader that names no place reads what started the run when it could name one (a file drop's file, the
            // approved documents), and otherwise makes its rows in the step (Sample rows).
            Place place;
            if (emptyKind != null) {
                place = new Place(emptyKind, spec);
                place.target.put("runInput", true);
                place.label = STORAGE.equals(emptyKind) ? "The file that started the run"
                    : DOCUMENTS.equals(emptyKind) ? "The documents that started the run" : "What started the run";
                place.link = STORAGE.equals(emptyKind) ? "/documents/file-drop" : DOCUMENTS.equals(emptyKind) ? "/documents/types" : null;
            } else {
                place = new Place(OTHER, spec);
                place.target.put("task", spec.code());
                place.label = spec.name() + " (written in the step)";
            }
            found.add(place);
        }
        return found;
    }

    private Place place(String kind, TaskSpec spec, Object value, Map<String, Object> config) {
        switch (kind) {
            case STORAGE: {
                String bucket = text(value);
                if (bucket == null) {
                    return null;
                }
                Place place = new Place(STORAGE, spec);
                place.target.put("bucket", bucket);
                String path = null;
                for (String property : PATH_PROPERTIES) {
                    path = text(config.get(property));
                    if (path != null) {
                        break;
                    }
                }
                if (path != null) {
                    place.target.put("path", path);
                }
                place.label = path == null ? bucket : bucket + " › " + path;
                place.link = "/connections/storage";
                return place;
            }
            case API: {
                Long id = StepReferences.idOf(value);
                if (id == null) {
                    return null;
                }
                Place place = new Place(API, spec);
                place.target.put("requestId", id);
                place.label = "API request " + id;
                place.link = "/connections/api-collections";
                return place;
            }
            case DATABASE: {
                Long id = StepReferences.idOf(value);
                if (id == null) {
                    return null;
                }
                Place place = new Place(DATABASE, spec);
                place.target.put("connectionId", id);
                place.label = "Database connection " + id;
                place.link = "/connections/databases";
                return place;
            }
            case DOCUMENTS: {
                Long id = StepReferences.idOf(value);
                if (id == null) {
                    return null;
                }
                Place place = new Place(DOCUMENTS, spec);
                place.target.put("documentTypeId", id);
                place.label = "Document type " + id;
                place.link = "/documents/types";
                return place;
            }
            default:
                return null;
        }
    }

    /** Names the places from the services that own them; a service that cannot answer leaves its ids. */
    private void name(long tenantId, Collection<Place> places) {
        Set<Long> requests = new LinkedHashSet<>();
        Set<Long> connections = new LinkedHashSet<>();
        Set<Long> types = new LinkedHashSet<>();
        for (Place place : places) {
            if (API.equals(place.kind)) {
                requests.add((Long) place.target.get("requestId"));
            } else if (DATABASE.equals(place.kind)) {
                connections.add((Long) place.target.get("connectionId"));
            } else if (DOCUMENTS.equals(place.kind)) {
                types.add((Long) place.target.get("documentTypeId"));
            }
        }
        if (this.integration != null && !(requests.isEmpty() && connections.isEmpty())) {
            try {
                IntegrationNames.Names names = this.integration.describe(tenantId, requests, connections);
                for (Place place : places) {
                    if (API.equals(place.kind)) {
                        IntegrationNames.RequestName name = names.requests.get((Long) place.target.get("requestId"));
                        if (name != null && name.name != null) {
                            place.label = name.collectionName == null ? name.name : name.collectionName + " › " + name.name;
                            if (name.collectionId != null) {
                                place.target.put("collectionId", name.collectionId);
                                place.link = "/connections/api-collections/" + name.collectionId;
                            }
                        }
                    } else if (DATABASE.equals(place.kind)) {
                        String name = names.connections.get((Long) place.target.get("connectionId"));
                        if (name != null) {
                            place.label = name;
                        }
                    }
                }
            } catch (Exception unnamed) {
                this.logger.warn("Pipeline reads of workspace {} keep their ids: integration-service did not name them ({}).", tenantId,
                    unnamed.getMessage());
            }
        }
        if (this.documents != null && !types.isEmpty() && !this.documents.unavailable().isPresent()) {
            Map<Long, String> named = new LinkedHashMap<>();
            for (Long type : types) {
                if (named.size() >= MAX_NAMED_TYPES) {
                    break;
                }
                try {
                    DocumentDatasets.Request look = new DocumentDatasets.Request();
                    look.tenantId = tenantId;
                    look.documentTypeId = type;
                    look.limit = 1;
                    look.names = false;
                    named.put(type, this.documents.read(look).name);
                } catch (Exception unnamed) {
                    this.logger.warn("Document type {} of workspace {} keeps its id: {}", type, tenantId, unnamed.getMessage());
                }
            }
            for (Place place : places) {
                String name = DOCUMENTS.equals(place.kind) ? named.get((Long) place.target.get("documentTypeId")) : null;
                if (name != null && !name.trim().isEmpty()) {
                    place.label = name.trim();
                }
            }
        }
    }

    private Optional<PipelineDefinition> read(PipelineDefinitionStore.Stored stored) {
        try {
            return Optional.ofNullable(stored.definition());
        } catch (RuntimeException unreadable) {
            this.logger.warn("Pipeline definition {} is skipped while listing what pipelines read: {}", stored.id, unreadable.getMessage());
            return Optional.empty();
        }
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }
}
