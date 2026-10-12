package process.pipeline;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * MIG-345: the sha256 of each bucket object a step reads, taken on the way while the step reads it -- never a second
 * read -- so a resumed run can tell that a file a reused step read has changed since (run_input_file).
 *
 * The step engine opens a {@link Recorder} for each try, on the try's own thread ({@link #recording}); the bucket store
 * reports every object it reads on that thread ({@link #whole} for a read held whole, {@link #digesting} for a stream).
 * Outside a try -- a console download, the customer API's file read -- nothing is recorded and a stream is passed through
 * as it is. A stream is fingerprinted for what the step took: byte_count bytes, and whether it read to the end ("whole");
 * a step that stops early (maxRows) is held to the same prefix, not to the whole file.
 *
 * At most {@link #MAX_FILES} files are fingerprinted per try: a step that reads more (an AI step over thousands of
 * images) records that it did ({@link Read#overflow}), and a resume cannot vouch for its inputs.
 */
public final class InputDigests {

    /** The most files one try's reads are fingerprinted for. */
    public static final int MAX_FILES = 1000;
    /** The bucket of the mark a try leaves when it read more than {@link #MAX_FILES} files. */
    public static final String OVERFLOW = "*";

    private static final ThreadLocal<Recorder> CURRENT = new ThreadLocal<>();

    private InputDigests() {
    }

    /** What a step read of one object: its sha256 over {@code bytes} bytes; {@code whole} when it read to the end. */
    public static final class Read {
        public final String bucket;
        public final String key;
        public final long bytes;
        public final String sha256;
        public final boolean whole;

        public Read(String bucket, String key, long bytes, String sha256, boolean whole) {
            this.bucket = bucket;
            this.key = key;
            this.bytes = bytes;
            this.sha256 = sha256;
            this.whole = whole;
        }

        /** The mark of a try that read more files than are fingerprinted; {@code bytes} is how many it read. */
        public static Read overflow(long files) {
            return new Read(OVERFLOW, OVERFLOW, files, null, false);
        }

        public boolean overflow() {
            return OVERFLOW.equals(this.bucket);
        }

        @Override
        public String toString() {
            return this.overflow() ? "more than " + MAX_FILES + " files" : this.bucket + "/" + this.key;
        }
    }

    /** One try's reads, by object: a later read of the same object replaces an earlier one that took less. */
    public static final class Recorder {
        private final Map<String, Read> reads = new LinkedHashMap<>();
        private long overflowed;

        synchronized void add(Read read) {
            String id = read.bucket + "\u0000" + read.key;
            Read earlier = this.reads.get(id);
            if (earlier != null) {
                if (read.bytes >= earlier.bytes) {
                    this.reads.put(id, read);
                }
                return;
            }
            if (this.reads.size() >= MAX_FILES) {
                this.overflowed++;
                return;
            }
            this.reads.put(id, read);
        }

        /** The reads, and the overflow mark when there were more than {@link #MAX_FILES} objects. */
        public synchronized List<Read> reads() {
            List<Read> all = new ArrayList<>(this.reads.values());
            if (this.overflowed > 0) {
                all.add(Read.overflow(this.reads.size() + this.overflowed));
            }
            return all;
        }
    }

    /** Runs {@code work} on this thread with {@code recorder} taking what it reads. */
    public static <T> T recording(Recorder recorder, Callable<T> work) throws Exception {
        Recorder before = CURRENT.get();
        CURRENT.set(recorder);
        try {
            return work.call();
        } finally {
            if (before == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(before);
            }
        }
    }

    /** An object read whole into {@code content}: recorded when a try is reading. */
    public static void whole(String bucket, String key, byte[] content) {
        Recorder recorder = CURRENT.get();
        if (recorder == null || content == null) {
            return;
        }
        MessageDigest digest = Streams.sha256();
        digest.update(content);
        recorder.add(new Read(bucket, key, content.length, Streams.hex(digest.digest()), true));
    }

    /**
     * The object's stream, fingerprinted while it is read when a try is reading (recorded when it is closed); else the
     * stream itself.
     */
    public static InputStream digesting(String bucket, String key, InputStream in) {
        Recorder recorder = CURRENT.get();
        return recorder == null || in == null ? in : new Digesting(in, recorder, bucket, key);
    }

    /** A stream that hashes what passes through it and records it once, when closed. */
    private static final class Digesting extends FilterInputStream {
        private final Recorder recorder;
        private final String bucket;
        private final String key;
        private final MessageDigest digest = Streams.sha256();
        private long count;
        private boolean ended;
        private boolean recorded;

        Digesting(InputStream in, Recorder recorder, String bucket, String key) {
            super(in);
            this.recorder = recorder;
            this.bucket = bucket;
            this.key = key;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b < 0) {
                this.ended = true;
            } else {
                this.digest.update((byte) b);
                this.count++;
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n < 0) {
                this.ended = true;
            } else if (n > 0) {
                this.digest.update(b, off, n);
                this.count += n;
            }
            return n;
        }

        @Override
        public long skip(long n) throws IOException {
            // Skipped bytes are read (and hashed), so the fingerprint is of a prefix the step really took.
            byte[] chunk = new byte[(int) Math.min(64 * 1024, Math.max(1, n))];
            long skipped = 0;
            while (skipped < n) {
                int got = this.read(chunk, 0, (int) Math.min(chunk.length, n - skipped));
                if (got < 0) {
                    break;
                }
                skipped += got;
            }
            return skipped;
        }

        @Override
        public boolean markSupported() {
            return false;
        }

        @Override
        public void close() throws IOException {
            try {
                if (!this.recorded) {
                    this.recorded = true;
                    if (!this.ended) {
                        // A reader that stops at the last row without asking for more (a JSON array's close) read the whole
                        // file all the same: one byte more says whether there was anything after.
                        try {
                            this.ended = this.in.read() < 0;
                        } catch (IOException unreadable) {
                            // left as a prefix
                        }
                    }
                    this.recorder.add(new Read(this.bucket, this.key, this.count, Streams.hex(this.digest.digest()), this.ended));
                }
            } finally {
                super.close();
            }
        }
    }
}
