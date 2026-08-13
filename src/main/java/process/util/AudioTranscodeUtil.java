package process.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public final class AudioTranscodeUtil {

    private static final Logger logger = LoggerFactory.getLogger(AudioTranscodeUtil.class);

    private AudioTranscodeUtil() {}

    private static final Set<String> AUDIO_EXTENSIONS = new HashSet<>(Arrays.asList(
        "m4a", "mp3", "wav", "aac", "flac"));

    private static final Set<String> BROWSER_SAFE_CODECS = new HashSet<>(Arrays.asList(
        "aac", "mp3"));

    private static final long PROBE_TIMEOUT_SECONDS = 30;
    private static final long TRANSCODE_TIMEOUT_MINUTES = 15;

    public static boolean isAudioExtension(String extension) {
        return AUDIO_EXTENSIONS.contains(extension);
    }

    public static Path transcodeToAacIfNeeded(Path inputFile) {
        String codec;
        try {
            codec = probeAudioCodec(inputFile);
        } catch (Exception ex) {
            logger.warn("Could not probe audio codec for {}, uploading original file: {}", inputFile, ex.getMessage());
            return null;
        }
        if (codec == null || BROWSER_SAFE_CODECS.contains(codec.toLowerCase())) {
            return null;
        }
        logger.info("Transcoding {} (codec={}) to AAC for browser playback", inputFile, codec);
        Path output;
        try {
            output = Files.createTempFile("upload-transcoded-", ".m4a");
        } catch (IOException ex) {
            logger.warn("Could not create temp file for transcode, uploading original file: {}", ex.getMessage());
            return null;
        }
        try {
            Process process = new ProcessBuilder(
                "ffmpeg", "-y", "-i", inputFile.toString(),
                "-vn", "-c:a", "aac", "-b:a", "192k", output.toString())
                .redirectErrorStream(true)
                .start();
            drain(process.getInputStream());
            boolean finished = process.waitFor(TRANSCODE_TIMEOUT_MINUTES, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                throw new IOException("ffmpeg transcode timed out after " + TRANSCODE_TIMEOUT_MINUTES + " minutes");
            }
            if (process.exitValue() != 0) {
                throw new IOException("ffmpeg exited with code " + process.exitValue());
            }
            return output;
        } catch (Exception ex) {
            logger.warn("Audio transcode failed for {}, uploading original file instead: {}", inputFile, ex.getMessage());
            deleteQuietly(output);
            return null;
        }
    }

    public static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ex) {
            logger.warn("Could not delete temp file {}: {}", path, ex.getMessage());
        }
    }

    private static String probeAudioCodec(Path inputFile) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(
            "ffprobe", "-v", "error", "-select_streams", "a:0",
            "-show_entries", "stream=codec_name", "-of", "csv=p=0", inputFile.toString())
            .start();
        String output;
        try (InputStream stream = process.getInputStream()) {
            output = new String(stream.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
        boolean finished = process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            return null;
        }
        return output.isEmpty() ? null : output;
    }

    private static void drain(InputStream stream) throws IOException {
        try (InputStream in = stream) {
            in.readAllBytes();
        }
    }

}
