package process.pipeline.backing;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What transcribe_audio and convert_file ask of media-service (its /internal/media/pipelineFiles), with the service token
 * and the run's workspace: a file the step read from the workspace's own bucket goes over, the transcript or the converted
 * file comes back. media bills the work to that workspace under the run's key (its step, attempt and row), as the
 * console's Transcript and Converter tools bill theirs.
 */
public interface MediaFiles {

    /** Why the steps cannot run here (media takes no pipeline files from this Core); empty when they can. */
    Optional<String> unavailable();

    /** The recording at {@code file} (named {@code fileName}) as text; a refusal is a {@link Refused}. */
    Transcript transcribe(long tenantId, String usageKey, Path file, String fileName, boolean timestamps) throws IOException;

    /** The file at {@code file} converted to {@code outputFormat}, written to {@code out}; a refusal is a {@link Refused}. */
    Converted convert(long tenantId, String usageKey, Path file, String fileName, String outputFormat, Path out) throws IOException;

    /** media's answer: transcript, durationSeconds, language, model, and the segments when asked. */
    final class Transcript {
        public String text;
        public Double durationSeconds;
        public String language;
        public String model;
        /** Each stretch of speech: startMs, endMs, text; null when not asked. */
        public List<Map<String, Object>> segments;
    }

    /** The converted file's name, its pages (a PDF) and its size. */
    final class Converted {
        public final String fileName;
        public final Integer pages;
        public final long bytes;

        public Converted(String fileName, Integer pages, long bytes) {
            this.fileName = fileName;
            this.pages = pages;
            this.bytes = bytes;
        }
    }

    /**
     * media said no, in its words, with the HTTP status: 400 (a type or a pair it does not take), 413 (too big), 422 (silent,
     * no speech, unreadable), or -- {@link #busy()} -- 502/503, worth asking again after {@link #retryAfterSeconds}.
     */
    final class Refused extends IOException {
        private final int status;
        private final int retryAfterSeconds;

        public Refused(int status, String message, int retryAfterSeconds) {
            super(message);
            this.status = status;
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public int status() {
            return this.status;
        }

        public int retryAfterSeconds() {
            return this.retryAfterSeconds;
        }

        /** Busy or unreachable for now (429, 502, 503, 504): the file itself is fine. */
        public boolean busy() {
            return this.status == 429 || this.status == 502 || this.status == 503 || this.status == 504;
        }
    }
}
