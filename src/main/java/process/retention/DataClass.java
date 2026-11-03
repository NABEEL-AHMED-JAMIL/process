package process.retention;

import java.util.Locale;
import java.util.Optional;

/**
 * MIG-354: the classes of a workspace's data a retention policy keeps for a number of days (or forever). Each says who
 * holds it -- Core, or the service that owns it, asked over its internal retention endpoint -- what it covers, in the
 * console's words, and which legal holds reach it: a workspace hold reaches every class; a pipeline or run hold reaches
 * the classes that know their run ({@link #knowsRuns}).
 *
 * <p>{@link #ANALYTICS} is listed and never removed by a pass: the analytics service already bounds its Parquet copies
 * (4 GB a workspace, least recently read first) and its result cache (10 minutes, 32 MB), and those copies are rebuilt
 * from the workspace's own sources on the next read.
 */
public enum DataClass {

    RUN_OUTPUTS(Owner.CORE, true, true, "Run outputs and datasets",
        "The datasets and files each run made, its result records and its AI answers. The run itself and its manifest (names, rows, "
            + "bytes) stay."),
    RUN_LOGS(Owner.CORE, true, true, "Run logs",
        "Each run's log lines and its steps' logs. The run, its steps and their counts stay."),
    FILES(Owner.STORAGE, true, true, "Uploaded files",
        "Files sent in through the API (POST /v1/files) and their stored copy in the workspace's inbox."),
    DOCUMENTS(Owner.AI, true, false, "Documents",
        "Document Intelligence: each document's extraction (fields, corrections, decisions and its approved row), its page images and "
            + "the original file."),
    FORM_SUBMISSIONS(Owner.CORE, true, true, "Form submissions",
        "Each submission's answers, the files sent with it, and its copy in the workspace's inbox."),
    AI_CONVERSATIONS(Owner.AI, true, false, "AI conversations",
        "AI Assistant conversations and their messages, its tool runs, and prompt test runs. Pinned conversations are included."),
    NOTIFICATIONS(Owner.NOTIFICATIONS, true, false, "Notifications",
        "Bell notices, read or not."),
    /*
     * Report Studio phase 7 (ADR-028 section 17): media holds it. A pipeline or run hold keeps the reports its runs made.
     * Never removed: a report's current version, any approved (signed) version, templates, assets and brand profiles.
     */
    REPORTS(Owner.MEDIA, true, true, "Reports",
        "Report Studio: the files of report versions a newer version replaced, and the frozen data no kept version uses (a failed "
            + "generation's after 7 days). Each report's current version, every approved or signed version, templates, assets and "
            + "brand profiles stay; a removed version keeps its record, marked expired."),
    /*
     * The tools redesign (owner-approved 2026-11-02): media holds the three. A workspace hold keeps them; they know no run.
     * Recordings and unkept conversions are never kept more than 30 days ({@link #maxDays}): media removes them at 30 days
     * by itself (unless a workspace hold is on), and a policy may only shorten that.
     */
    TRANSCRIPTS(Owner.MEDIA, true, false, "Transcripts",
        "Audio Transcript: each transcript (its text, times, speakers and corrections). The recording itself is the next class."),
    RECORDINGS(Owner.MEDIA, true, false, "Audio recordings",
        "Audio Transcript: the recordings kept for playback beside their transcripts -- never more than 30 days. The transcript "
            + "stays."),
    CONVERSIONS(Owner.MEDIA, true, false, "Converted files",
        "Document Converter: the originals and results of conversions not kept in a bucket -- never more than 30 days -- and "
            + "each conversion's line in Recent conversions. Files kept in a bucket are the bucket's."),
    /*
     * Image review (the third Tools page, owner-approved 2026-11-03): media holds it. A workspace hold keeps it; it knows no
     * run. Never kept more than 30 days ({@link #maxDays}): media removes a photo at 30 days by itself (unless a workspace
     * hold is on), and a policy may only shorten that. A photo sent to Documents or a report is that copy's from then on.
     */
    PHOTOS(Owner.MEDIA, true, false, "Image review photos",
        "Image review: the photos (blurred where a region was blurred; the unblurred original is never kept), their measurements "
            + "and draft descriptions -- never more than 30 days. A copy sent to Documents or a report is kept there."),
    ANALYTICS(Owner.NONE, false, false, "Analytics copies and cache",
        "Bounded by the platform: Parquet copies are kept to 4 GB a workspace (least recently read go first) and query results 10 "
            + "minutes. Both are rebuilt from your sources on the next read."),
    AUDIT(Owner.CORE, true, false, "Audit records",
        "File downloads and previews, what retention removed, and this policy's own history. Never kept for less than the "
            + "platform's minimum.");

    /** Who removes it: Core itself, or the owning service through its internal retention endpoint. */
    public enum Owner { CORE, AI, STORAGE, NOTIFICATIONS, MEDIA, NONE }

    private final Owner owner;
    private final boolean configurable;
    private final boolean knowsRuns;
    private final String label;
    private final String covers;

    DataClass(Owner owner, boolean configurable, boolean knowsRuns, String label, String covers) {
        this.owner = owner;
        this.configurable = configurable;
        this.knowsRuns = knowsRuns;
        this.label = label;
        this.covers = covers;
    }

    public Owner owner() {
        return this.owner;
    }

    /** False for a class the platform bounds by itself (ANALYTICS): it has no days and no pass touches it. */
    public boolean configurable() {
        return this.configurable;
    }

    /** True when a pipeline or run hold reaches it (its items name their run or pipeline). */
    public boolean knowsRuns() {
        return this.knowsRuns;
    }

    public String label() {
        return this.label;
    }

    public String covers() {
        return this.covers;
    }

    /** The most days the class is ever kept (the platform removes it then, policy or not); null when there is no such cap. */
    public Integer maxDays() {
        return this == RECORDINGS || this == CONVERSIONS || this == PHOTOS ? 30 : null;
    }

    public static Optional<DataClass> of(String name) {
        if (name == null) {
            return Optional.empty();
        }
        String upper = name.trim().toUpperCase(Locale.ROOT);
        for (DataClass value : values()) {
            if (value.name().equals(upper)) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }
}
