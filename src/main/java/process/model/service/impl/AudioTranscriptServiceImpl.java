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
import process.model.dto.ObjectContentDto;
import process.model.dto.ResponseDto;
import process.model.service.AudioTranscriptService;
import process.model.service.StorageBrowserService;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import static process.util.ProcessUtil.ERROR;
import static process.util.ProcessUtil.SUCCESS;
import static process.util.ProcessUtil.isNull;

/**
 * @author Nabeel Ahmed
 * */
@Service
public class AudioTranscriptServiceImpl implements AudioTranscriptService {

    private static final String[] AUDIO_EXTENSIONS = {".mp3", ".m4a"};

    // A bucket object is now read on this side rather than fetched by the worker, so its size is
    // ours to bound: 500MB, the worker's own limit, past which it refuses the file regardless.
    // Deliberately far above the upload ceiling below -- transcribing from a bucket is what that
    // limit's message tells people to do with a file too big to upload.
    private static final long MAX_BUCKET_FILE_BYTES = 500L * 1024L * 1024L;

    @Value("${audio.extract.service.base.url:http://host.docker.internal:8100}")
    private String baseUrl;

    // A transcription holds its upload for as long as the transcode runs, which is minutes
    // rather than seconds -- the read timeout below is half an hour. The global multipart
    // limit is 500MB, so a handful of concurrent uploads at that size would fill the disk
    // the temp file lives on; this is the endpoint's own, much lower ceiling.
    @Value("${audio.extract.max-file-size-mb:250}")
    private int maxFileSizeMb;

    private final StorageBrowserService storageBrowserService;

    public AudioTranscriptServiceImpl(StorageBrowserService storageBrowserService) {
        this.storageBrowserService = storageBrowserService;
    }

    private final Gson gson = new Gson();

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(15))
        .readTimeout(30, TimeUnit.MINUTES)
        .build();

    @Override
    public ResponseDto extractFromUpload(MultipartFile file, boolean timestamps) throws Exception {
        if (file == null || file.isEmpty()) {
            return new ResponseDto(ERROR, "No file supplied.");
        }
        String fileName = file.getOriginalFilename();
        if (!this.hasAudioExtension(fileName)) {
            return new ResponseDto(ERROR, "Unsupported file type -- expected .mp3 or .m4a.");
        }
        long maxBytes = (long) this.maxFileSizeMb * 1024L * 1024L;
        if (file.getSize() > maxBytes) {
            return new ResponseDto(ERROR, String.format(
                "That file is %.1f MB, over the %d MB transcription limit. Split it, or upload it to a bucket and transcribe it from there.",
                file.getSize() / (1024d * 1024d), this.maxFileSizeMb));
        }
        Path tempInput = null;
        try {
            String extension = fileName.substring(fileName.lastIndexOf('.'));
            tempInput = Files.createTempFile("audio-upload-", extension);
            file.transferTo(tempInput);
            String transcript = this.transcribeFile(tempInput, fileName, timestamps);
            return new ResponseDto(SUCCESS, "Transcript extracted.", transcript);
        } finally {
            if (tempInput != null) {
                Files.deleteIfExists(tempInput);
            }
        }
    }

    /**
     * Transcribes an object the caller named by bucket and key.
     *
     * The worker has a bucket endpoint of its own, and it used to be handed this bucket and key
     * straight through. That put the object outside every rule this application enforces: the
     * worker fetches with the platform's own MinIO credentials, against the platform's own
     * MinIO, so it reads whatever pair it is given -- a bucket belonging to another tenant, a
     * key nobody could open through the browser, another user's avatar. Testing the bucket
     * against listBuckets first was not enough, because the guard has more to say about a key
     * than the bucket alone can answer, and the worker never sees any of it.
     *
     * So the object is read here instead, through the same download the object browser uses, and
     * only its bytes go to the worker -- on the upload endpoint, which has no idea where they
     * came from. Whatever a caller may not download, they may not transcribe either, and the two
     * answers cannot drift apart.
     */
    @Override
    public ResponseDto extractFromBucket(AudioExtractBucketRequestDto request) throws Exception {
        if (isNull(request.getBucket()) || request.getBucket().trim().isEmpty()) {
            return new ResponseDto(ERROR, "bucket missing.");
        }
        if (isNull(request.getKey()) || request.getKey().trim().isEmpty()) {
            return new ResponseDto(ERROR, "key missing.");
        }
        String bucket = request.getBucket().trim();
        String key = request.getKey().trim();
        if (!this.hasAudioExtension(key)) {
            return new ResponseDto(ERROR, "Unsupported file type -- expected .mp3 or .m4a.");
        }

        ObjectContentDto object;
        try {
            object = this.storageBrowserService.downloadObject(bucket, key, null, null);
        } catch (IllegalArgumentException refused) {
            // Both the unknown-bucket refusal and an unsafe key arrive this way, and both are the
            // caller's answer rather than a fault -- reported as this endpoint reports its own.
            return new ResponseDto(ERROR, refused.getMessage());
        }
        String fileName = this.fileNameOf(key);
        Path tempInput = null;
        // The stream is open from here whichever way this ends, including the refusal below.
        try (InputStream content = object.getContent()) {
            if (object.getTotalSize() > MAX_BUCKET_FILE_BYTES) {
                return new ResponseDto(ERROR, String.format(
                    "That file is %.1f MB, over the %d MB transcription limit.",
                    object.getTotalSize() / (1024d * 1024d), MAX_BUCKET_FILE_BYTES / (1024 * 1024)));
            }
            String extension = fileName.substring(fileName.lastIndexOf('.'));
            tempInput = Files.createTempFile("audio-bucket-", extension);
            Files.copy(content, tempInput, StandardCopyOption.REPLACE_EXISTING);
            String transcript = this.transcribeFile(
                tempInput, fileName, Boolean.TRUE.equals(request.getTimestamps()));
            return new ResponseDto(SUCCESS, "Transcript extracted.", transcript);
        } finally {
            if (tempInput != null) {
                Files.deleteIfExists(tempInput);
            }
        }
    }

    /**
     * Posts a file already on disk to the worker's upload endpoint.
     *
     * OkHttp streams it from there: reading it back into a byte[] kept the whole thing on the
     * heap for the length of the transcode, which runs in minutes.
     */
    private String transcribeFile(Path source, String fileName, boolean timestamps) throws Exception {
        RequestBody fileBody = RequestBody.create(source.toFile(), MediaType.get("application/octet-stream"));
        RequestBody multipartBody = new MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", fileName, fileBody)
            .addFormDataPart("timestamps", String.valueOf(timestamps))
            .build();
        Request request = new Request.Builder()
            .url(this.baseUrl + "/extract/upload")
            .post(multipartBody)
            .build();
        return this.executeForTranscript(request);
    }

    private String fileNameOf(String key) {
        int slash = key.lastIndexOf('/');
        return slash >= 0 ? key.substring(slash + 1) : key;
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

    private String extractDetail(String responseBody, int statusCode) {
        try {
            JsonObject json = this.gson.fromJson(responseBody, JsonObject.class);
            if (json != null && json.has("detail")) {
                return json.get("detail").getAsString();
            }
        } catch (Exception ignored) {

        }
        return String.format("HTTP %d: %s", statusCode, responseBody);
    }

}
