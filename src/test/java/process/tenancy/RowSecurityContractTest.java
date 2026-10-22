package process.tenancy;

import org.barco.platform.contract.RowSecurityContract;
import process.ModelApplication;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Row-level security's structural half for Core (MIG-258): the hooks are taken (config/RowSecurityConfig), and every
 * path that works across workspaces -- with no caller, or beyond the caller's workspace on purpose -- is listed here
 * with its reason. A new one is a reviewed change to this list.
 */
class RowSecurityContractTest extends RowSecurityContract {

    @Override
    protected Class<?> application() {
        return ModelApplication.class;
    }

    @Override
    protected Map<String, String> acrossTenants() {
        Map<String, String> paths = new LinkedHashMap<>();
        // System paths: crons, relays, startup -- no caller, every workspace (the dispatcher, crons and relays of acceptance 2)
        paths.put("ProducerBulkEngine.addJobInQueue",
            "the enqueuer claims every workspace's due schedule slots");
        paths.put("ProducerBulkEngine.startJobInCurrentTimeSlot",
            "the dispatcher hands every workspace's prepared runs to their workers");
        paths.put("ProducerBulkEngine.reconcileStalledRuns",
            "the stall sweep closes runs of every workspace that stopped reporting");
        paths.put("PreDispatchPhase.runPass",
            "pre-dispatch claims and prepares every workspace's queued runs");
        paths.put("DispatchRelay: in code #1",
            "the dispatch relay publishes every workspace's hand-offs");
        paths.put("CustomerEventRelay: in code #1",
            "the customer event relay (MIG-333) reads every workspace's unpublished api_event_out rows (ids and kinds, in order); each is"
                + " built, published and stamped as its own workspace");
        paths.put("InboxQueueSweep: in code #1",
            "the inbox queue sweep (MIG-360) reads which jobs of every workspace have inbox files waiting and no run in flight (ids"
                + " only); each job's next run is started as its own workspace");
        paths.put("DocumentQueueSweep: in code #1",
            "the document queue sweep (MIG-364) reads which jobs of every workspace have approved documents waiting and no run in"
                + " flight (ids only); each job's next run is started as its own workspace");
        paths.put("RunApprovals: in code #1",
            "the approval sweep (MIG-346) reads every workspace's approval requests past their time; each is expired as its own"
                + " workspace");
        paths.put("RunApprovals: in code #2",
            "the approval sweep (MIG-346) reads every workspace's approval requests whose run no longer waits on them; each is"
                + " cancelled as its own workspace");
        // 2026-10-21: decisions a restart lost on their way to the step engine.
        paths.put("RunApprovals: in code #3",
            "the approval sweep reads every workspace's decided approval requests whose run still waits; each is handed to the engine"
                + " as its own workspace");
        // MIG-347: the backfill dispatcher settles every workspace's ended backfill runs on their periods and reads the open backfills
        // and the platform's backfill runs in flight; each backfill is then worked as its own workspace.
        paths.put("BackfillDispatcher: in code #1",
            "the backfill dispatcher (MIG-347) records on every workspace's backfill periods the outcome of their runs that ended");
        paths.put("BackfillDispatcher: in code #2",
            "the backfill dispatcher (MIG-347) reads which backfills of every workspace are open (ids only); each is then worked as its"
                + " own workspace");
        paths.put("BackfillDispatcher: in code #3",
            "the backfill dispatcher (MIG-347) counts the backfill runs in flight on the platform (a number), the cap every workspace's"
                + " backfills share");
        // Owner #24: Platform › Engine settings -- a platform administrator's screen over every workspace's own engine values,
        // and the engine's cache of them.
        paths.put("EngineDials: in code #1",
            "the engine settings (owner #24) read every workspace's own engine values (engine_setting_override: a key and a number each), cached for the step engine and the platform screen");
        paths.put("EngineSettingStore: in code #1",
            "the engine settings screen (owner #24, platform administrators) reads one workspace's own value of an engine setting before it changes it");
        paths.put("EngineSettingStore: in code #2",
            "the engine settings screen (owner #24, platform administrators) sets one workspace's own value of an engine setting");
        paths.put("EngineSettingStore: in code #3",
            "the engine settings screen (owner #24, platform administrators) removes one workspace's own value of an engine setting (reset to the platform's)");
        paths.put("EngineSettingStore: in code #4",
            "the engine settings screen (owner #24, platform administrators) lists every workspace's own engine values with who set them");
        paths.put("EngineSettingStore: in code #5",
            "the engine settings screen (owner #24, platform administrators) records a change to an engine setting, the platform's or one workspace's");
        paths.put("EngineSettingStore: in code #6",
            "the engine settings screen (owner #24, platform administrators) reads the history of engine setting changes, the platform's and every workspace's");
        paths.put("DispatchOutboxPurge: in code #1",
            "the dispatch outbox purge deletes every workspace's hand-offs published or abandoned more than seven days ago; it never"
                + " touches a pending one, and nothing else");
        // MIG-355: monitoring that alerts -- the evaluator reads across workspaces (rules, the signals, open alerts) and writes each
        // workspace's alerts as that workspace; the platform's alerts have no workspace.
        // MIG-354: the retention scheduler reads which workspaces turned retention on; each pass then runs as its workspace.
        paths.put("RetentionScheduler: in code #1",
            "the retention scheduler (MIG-354) reads which workspaces turned retention on (ids only); each workspace's pass then runs as"
                + " that workspace");
        paths.put("AlertRules: in code #1",
            "the alert evaluator (MIG-355) reads every workspace's enabled alert rules and the platform's; each workspace's alerts are then"
                + " written as that workspace");
        paths.put("AlertEngine: in code #1",
            "the alert evaluator (MIG-355) reads the open alerts of every enabled rule; each rule's are then written as its own workspace");
        paths.put("AlertEngine: in code #2",
            "the alert evaluator (MIG-355) writes the platform's own alerts (tenant_id NULL)");
        paths.put("AlertEngine: in code #3",
            "the alert evaluator (MIG-355) resolves the open alerts of every workspace's rules that were turned off");
        paths.put("WorkspaceSignals: in code #1",
            "the alert evaluator (MIG-355) reads when the failing pipelines of every workspace with a failed-run rule last completed a run"
                + " (ids and times)");
        paths.put("WorkspaceSignals: in code #2",
            "the alert evaluator (MIG-355) reads every workspace with a rule of this kind (ids, times and names); each workspace's alerts are"
                + " then written as that workspace");
        paths.put("MonitoringCycle: in code #1",
            "the alert retention (MIG-355) removes every workspace's alerts resolved more than 30 days ago");
        paths.put("RunStartMarkerMonitor.measure",
            "a platform gauge over every workspace's runs");
        paths.put("AuditLogSyncCron.syncAuditLogsFromOpenSearch",
            "copies OpenSearch audit lines back for every workspace's runs");
        paths.put("RunSloReport.measure",
            "the pipeline-execution SLO is measured over every workspace's runs");
        paths.put("RunSloReport.measureDaily",
            "the pipeline-execution SLO's daily series is measured over every workspace's runs");
        paths.put("DatasetSweep.sweep",
            "expired run datasets of every workspace are removed");
        paths.put("TenantOrphanAudit.run",
            "the orphan audit counts every workspace's rows against Identity's workspaces");
        paths.put("UserDirectoryReconciliation.run",
            "the nightly reconciliation checks every person in the directory, platform administrators included");
        paths.put("EncryptionReseal.run",
            "re-seals every workspace's secrets, and the platform's, under the current key");
        paths.put("KafkaTopicProvisioner: in code #1",
            "startup provisions the topics of every workspace's task types");
        // Identity's feeds and the people directory
        paths.put("IdentityEventsListener",
            "Identity's people and workspace feeds span every workspace: a person moves between them, a platform administrator has none, and a deleted workspace's jobs are retired");
        paths.put("UserDirectory: in code #1",
            "caches Identity's answer for any person, platform administrators (no workspace) included, on the write-back thread");
        paths.put("LocalIdentity",
            "identity.mode=local: Identity's own tables, read in-process -- sign-in and the token check run before any workspace is known, and people are looked up by id wherever they are");
        // A worker's callback: only the lookup of its run's workspace; the rest runs as that workspace (RowSecurity.forTenant)
        paths.put("RunWorkspace.of",
            "a worker's callback names only its run: the run's workspace is read by its id, before anything else");
        // /internal: service-token endpoints another service calls for any workspace
        paths.put("InternalNotificationRelayRestApi.notice",
            "a service's notice names its recipient by id; a platform administrator has no workspace (service token)");
        paths.put("InternalNotificationRelayRestApi.mail",
            "a service's mail names its recipient by id; a platform administrator has no workspace (service token)");
        paths.put("InternalRunVerificationRestApi.countUsingPrompt",
            "AI asks whether any workspace's pipeline still uses a prompt before it deletes it (service token)");
        paths.put("InternalStorageDirectoryRestApi.kafkaReferences",
            "storage asks which Kafka profiles of any workspace name an object before it deletes it (service token)");
        paths.put("FormShareLinks: in code #1",
            "a share link's visitor names no workspace: the link is found by its token's hash alone");
        paths.put("InternalLineageRestApi.runs",
            "the Data Catalog's lineage reads every workspace's finished runs into that workspace's lineage (service token)");
        paths.put("InternalTenantDirectoryRestApi.resolve",
            "services resolve workspaces by id or code, any workspace (service token)");
        paths.put("InternalTenantFactsRestApi.tenantFacts",
            "Identity asks for the facts of the workspaces it names before a delete (service token)");
        paths.put("InternalUserDirectoryRestApi.resolve",
            "services resolve people by id, whichever workspace they are in (service token)");
        return paths;
    }
}
