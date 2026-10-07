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
        paths.put("DispatchOutboxPurge: in code #1",
            "the dispatch outbox purge deletes every workspace's hand-offs published or abandoned more than seven days ago; it never"
                + " touches a pending one, and nothing else");
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
