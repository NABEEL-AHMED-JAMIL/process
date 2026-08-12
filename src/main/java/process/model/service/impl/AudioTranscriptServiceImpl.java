package process.model.service.impl;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import process.model.dto.AudioExtractBucketRequestDto;
import process.model.dto.BucketSummaryDto;
import process.model.dto.ResponseDto;
import process.model.dto.YoutubeExtractRequestDto;
import process.model.service.AudioTranscriptService;
import process.model.service.StorageBrowserService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import static process.util.ProcessUtil.ERROR;
import static process.util.ProcessUtil.SUCCESS;
import static process.util.ProcessUtil.isNull;

/**
 * Service use to proxy ad-hoc audio-to-text extraction requests to the standalone Python
 * "Audio Extract Service" (job-search/etl/service/audio_extract_service.py) -- that service
 * runs the same noise-reduction + Whisper pipeline as the F768927 batch job, but synchronously
 * against a single file supplied here (upload or an existing bucket object).
 * @author Nabeel Ahmed
 */
@Service
public class AudioTranscriptServiceImpl implements AudioTranscriptService {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private static final String[] AUDIO_EXTENSIONS = {".mp3", ".m4a"};

    private static final String[] VIDEO_EXTENSIONS = {".mp4", ".mov", ".mkv", ".webm", ".avi"};

    @Value("${audio.extract.service.base.url:http://host.docker.internal:8100}")
    private String baseUrl;

    private final StorageBrowserService storageBrowserService;

    public AudioTranscriptServiceImpl(StorageBrowserService storageBrowserService) {
        this.storageBrowserService = storageBrowserService;
    }

    private final Gson gson = new Gson();

    /** Whisper transcription on CPU can take minutes for a real recording -- a much longer
     * read timeout than any other proxied call this app makes. */
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(15))
        .readTimeout(30, TimeUnit.MINUTES)
        .build();

    /** Video extraction and YouTube download add real time on top of the Whisper allowance
     * above (ffmpeg demux, or a full video/audio download) -- extra headroom for those two only. */
    private final OkHttpClient longRunningHttpClient = new OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(15))
        .readTimeout(45, TimeUnit.MINUTES)
        .build();

    /**
     * Method use to extract a transcript from an uploaded audio file
     * @param file
     * @return ResponseDto
     * */
    @Override
    public ResponseDto extractFromUpload(MultipartFile file, boolean timestamps) throws Exception {
        if (file == null || file.isEmpty()) {
            return new ResponseDto(ERROR, "No file supplied.");
        }
        String fileName = file.getOriginalFilename();
        if (!this.hasAudioExtension(fileName)) {
            return new ResponseDto(ERROR, "Unsupported file type -- expected .mp3 or .m4a.");
        }
        Path tempInput = null;
        try {
            String extension = fileName.substring(fileName.lastIndexOf('.'));
            tempInput = Files.createTempFile("audio-upload-", extension);
            file.transferTo(tempInput);
            RequestBody fileBody = RequestBody.create(Files.readAllBytes(tempInput), MediaType.get("application/octet-stream"));
            RequestBody multipartBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", fileName, fileBody)
                .addFormDataPart("timestamps", String.valueOf(timestamps))
                .build();
            Request request = new Request.Builder()
                .url(this.baseUrl + "/extract/upload")
                .post(multipartBody)
                .build();
            String transcript = this.executeForTranscript(request);
            return new ResponseDto(SUCCESS, "Transcript extracted.", transcript);
        } finally {
            if (tempInput != null) {
                Files.deleteIfExists(tempInput);
            }
        }
    }

    /**
     * Method use to extract a transcript from an audio file already sitting in a bucket
     * @param request
     * @return ResponseDto
     * */
    @Override
    public ResponseDto extractFromBucket(AudioExtractBucketRequestDto request) throws Exception {
        if (isNull(request.getBucket()) || request.getBucket().trim().isEmpty()) {
            return new ResponseDto(ERROR, "bucket missing.");
        }
        if (isNull(request.getKey()) || request.getKey().trim().isEmpty()) {
            return new ResponseDto(ERROR, "key missing.");
        }
        if (!this.hasAudioExtension(request.getKey())) {
            return new ResponseDto(ERROR, "Unsupported file type -- expected .mp3 or .m4a.");
        }
        // Unlike the Object Browser's own read/write endpoints (which all funnel through
        // StorageBrowserServiceImpl.resolveService, tenant-checked there), this proxies
        // straight to the external Python service with a caller-supplied bucket name -- without
        // this check a tenant user could transcribe (and get back the contents of) any other
        // tenant's audio files just by naming their bucket/key.
        boolean bucketOwnedByCaller = this.storageBrowserService.listBuckets().stream()
            .map(BucketSummaryDto::getBucket)
            .anyMatch(bucket -> bucket.equals(request.getBucket()));
        if (!bucketOwnedByCaller) {
            return new ResponseDto(ERROR, String.format("Unknown bucket: %s.", request.getBucket()));
        }
        JsonObject body = new JsonObject();
        body.addProperty("bucket", request.getBucket());
        body.addProperty("key", request.getKey());
        body.addProperty("timestamps", Boolean.TRUE.equals(request.getTimestamps()));
        Request httpRequest = new Request.Builder()
            .url(this.baseUrl + "/extract/bucket")
            .post(RequestBody.create(this.gson.toJson(body), JSON))
            .build();
        String transcript = this.executeForTranscript(httpRequest);
        return new ResponseDto(SUCCESS, "Transcript extracted.", transcript);
    }

    /**
     * Method use to extract a transcript from an uploaded video file (audio track only)
     * @param file
     * @return ResponseDto
     * */
    @Override
    public ResponseDto extractFromVideoUpload(MultipartFile file, boolean timestamps) throws Exception {
        if (file == null || file.isEmpty()) {
            return new ResponseDto(ERROR, "No file supplied.");
        }
        String fileName = file.getOriginalFilename();
        if (!this.hasExtension(fileName, VIDEO_EXTENSIONS)) {
            return new ResponseDto(ERROR, "Unsupported file type -- expected .mp4, .mov, .mkv, .webm, or .avi.");
        }
        Path tempInput = null;
        try {
            String extension = fileName.substring(fileName.lastIndexOf('.'));
            tempInput = Files.createTempFile("video-upload-", extension);
            file.transferTo(tempInput);
            RequestBody fileBody = RequestBody.create(Files.readAllBytes(tempInput), MediaType.get("application/octet-stream"));
            RequestBody multipartBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", fileName, fileBody)
                .addFormDataPart("timestamps", String.valueOf(timestamps))
                .build();
            Request request = new Request.Builder()
                .url(this.baseUrl + "/extract/video-upload")
                .post(multipartBody)
                .build();
            String transcript = this.executeForTranscript(request, this.longRunningHttpClient);
            return new ResponseDto(SUCCESS, "Transcript extracted.", transcript);
        } finally {
            if (tempInput != null) {
                Files.deleteIfExists(tempInput);
            }
        }
    }

    /**
     * Method use to extract a transcript from a YouTube link
     * @param request
     * @return ResponseDto
     * */
    @Override
    public ResponseDto extractFromYoutube(YoutubeExtractRequestDto request) throws Exception {
        if (isNull(request.getUrl()) || request.getUrl().trim().isEmpty()) {
            return new ResponseDto(ERROR, "url missing.");
        }
        JsonObject body = new JsonObject();
        body.addProperty("url", request.getUrl().trim());
        body.addProperty("timestamps", Boolean.TRUE.equals(request.getTimestamps()));
        Request httpRequest = new Request.Builder()
            .url(this.baseUrl + "/extract/youtube")
            .post(RequestBody.create(this.gson.toJson(body), JSON))
            .build();
        String transcript = this.executeForTranscript(httpRequest, this.longRunningHttpClient);
        return new ResponseDto(SUCCESS, "Transcript extracted.", transcript);
    }

    private boolean hasAudioExtension(String fileName) {
        return this.hasExtension(fileName, AUDIO_EXTENSIONS);
    }

    private boolean hasExtension(String fileName, String[] extensions) {
        if (isNull(fileName)) {
            return false;
        }
        String lower = fileName.toLowerCase();
        for (String extension : extensions) {
            if (lower.endsWith(extension)) {
                return true;
            }
        }
        return false;
    }

    private String executeForTranscript(Request request) throws Exception {
        return this.executeForTranscript(request, this.httpClient);
    }

    private String executeForTranscript(Request request, OkHttpClient client) throws Exception {
        try (Response response = client.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IllegalStateException(this.extractDetail(responseBody, response.code()));
            }
            JsonObject json = this.gson.fromJson(responseBody, JsonObject.class);
            return json.has("transcript") ? json.get("transcript").getAsString() : "";
        }
    }

    /** FastAPI error responses are {"detail": "..."} -- surface just the message where
     * possible instead of the raw JSON envelope. */
    private String extractDetail(String responseBody, int statusCode) {
        try {
            JsonObject json = this.gson.fromJson(responseBody, JsonObject.class);
            if (json != null && json.has("detail")) {
                return json.get("detail").getAsString();
            }
        } catch (Exception ignored) {
            // fall through to raw body below
        }
        return String.format("HTTP %d: %s", statusCode, responseBody);
    }

}
