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
import process.model.dto.ResponseDto;
import process.model.service.ImageTextService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import static process.util.ProcessUtil.ERROR;
import static process.util.ProcessUtil.SUCCESS;
import static process.util.ProcessUtil.isNull;

@Service
public class ImageTextServiceImpl implements ImageTextService {

    private static final String[] IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png", ".webp", ".bmp", ".gif", ".tiff"};

    @Value("${audio.extract.service.base.url:http://host.docker.internal:8100}")
    private String baseUrl;

    private final Gson gson = new Gson();

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(15))
        .readTimeout(2, TimeUnit.MINUTES)
        .build();

    @Override
    public ResponseDto extractFromImage(MultipartFile file, Integer x, Integer y, Integer width, Integer height) throws Exception {
        if (file == null || file.isEmpty()) {
            return new ResponseDto(ERROR, "No file supplied.");
        }
        String fileName = file.getOriginalFilename();
        if (!this.hasImageExtension(fileName)) {
            return new ResponseDto(ERROR, "Unsupported file type -- expected jpg, jpeg, png, webp, bmp, gif, or tiff.");
        }
        Path tempInput = null;
        try {
            String extension = fileName.substring(fileName.lastIndexOf('.'));
            tempInput = Files.createTempFile("image-upload-", extension);
            file.transferTo(tempInput);
            RequestBody fileBody = RequestBody.create(Files.readAllBytes(tempInput), MediaType.get("application/octet-stream"));
            MultipartBody.Builder multipartBuilder = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", fileName, fileBody);
            if (x != null && y != null && width != null && height != null) {
                multipartBuilder.addFormDataPart("x", String.valueOf(x));
                multipartBuilder.addFormDataPart("y", String.valueOf(y));
                multipartBuilder.addFormDataPart("width", String.valueOf(width));
                multipartBuilder.addFormDataPart("height", String.valueOf(height));
            }
            Request request = new Request.Builder()
                .url(this.baseUrl + "/extract/image")
                .post(multipartBuilder.build())
                .build();
            String text = this.executeForTranscript(request);
            return new ResponseDto(SUCCESS, "Text extracted.", text);
        } finally {
            if (tempInput != null) {
                Files.deleteIfExists(tempInput);
            }
        }
    }

    private boolean hasImageExtension(String fileName) {
        if (isNull(fileName)) {
            return false;
        }
        String lower = fileName.toLowerCase();
        for (String extension : IMAGE_EXTENSIONS) {
            if (lower.endsWith(extension)) {
                return true;
            }
        }
        return false;
    }

    private String executeForTranscript(Request request) throws Exception {
        try (Response response = this.httpClient.newCall(request).execute()) {
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
