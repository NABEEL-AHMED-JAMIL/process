package process.pipeline.image;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import process.model.dto.ResponseDto;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Image review's door into the measuring code: the service token only, and a photo without a ruler answers no size. */
class InternalImageMeasureRestApiTest {

    private final InternalImageMeasureRestApi api = new InternalImageMeasureRestApi("secret-token");

    private static byte[] photo(String name) throws IOException {
        try (InputStream in = InternalImageMeasureRestApiTest.class.getResourceAsStream("/image-review/" + name)) {
            return in.readAllBytes();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ResponseEntity<?> answer) {
        return (Map<String, Object>) ((ResponseDto) answer.getBody()).getData();
    }

    @Test
    void onlyTheServiceTokenIsAnswered() throws IOException {
        assertThat(this.api.measure(null, 1006L, "red_region", photo("wound-SV-000117-v1.jpg")).getStatusCode())
            .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(this.api.measure("wrong", 1006L, "red_region", photo("wound-SV-000117-v1.jpg")).getStatusCode())
            .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(new InternalImageMeasureRestApi("").measure("", 1006L, null, new byte[] {1}).getStatusCode())
            .as("no token configured: nothing is answered").isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(this.api.outline(null, new InternalImageMeasureRestApi.OutlineRequest()).getStatusCode())
            .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void aPhotoIsMeasuredWithItsGeometry() throws IOException {
        ResponseEntity<?> answer = this.api.measure("secret-token", 1006L, "red_region", photo("wound-SV-000117-v2.jpg"));
        assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> m = data(answer);
        assertThat(m.get("scaleFound")).isEqualTo(true);
        assertThat((Double) m.get("areaCm2")).isBetween(6.2, 6.8);
        @SuppressWarnings("unchecked")
        Map<String, Object> g = (Map<String, Object>) m.get("geometry");
        assertThat(g.get("width")).isEqualTo(1000);
        assertThat((int[]) g.get("outline")).isNotEmpty();
    }

    @Test
    void noRulerNoSize() throws IOException {
        Map<String, Object> m = data(this.api.measure("secret-token", 1006L, "red_region", photo("wound-noruler.jpg")));
        assertThat(m.get("scaleFound")).isEqualTo(false);
        assertThat(m.get("areaCm2")).isNull();
        assertThat(m.get("lengthCm")).isNull();
    }

    @Test
    void whatIsNotAPhotoIsRefusedPlainly() {
        ResponseEntity<?> answer = this.api.measure("secret-token", 1006L, null, "%PDF-1.7 not a photo".getBytes());
        assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(((ResponseDto) answer.getBody()).getMessage()).isEqualTo("The file is not a png or jpg image.");
        assertThat(this.api.measure("secret-token", 1006L, "nonsense", new byte[] {1}).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(this.api.measure("secret-token", null, null, new byte[] {1}).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void aDrawnOutlineIsReMeasured() {
        InternalImageMeasureRestApi.OutlineRequest request = new InternalImageMeasureRestApi.OutlineRequest();
        request.setTenantId(1006L);
        request.setWidth(1000);
        request.setHeight(760);
        request.setOutline(Arrays.asList(100.0, 100.0, 300.0, 100.0, 300.0, 200.0, 100.0, 200.0));
        Map<String, Object> pixels = data(this.api.outline("secret-token", request));
        assertThat(pixels.get("areaCm2")).as("no scale: pixels only").isNull();
        request.setPxPerCm(50.0);
        Map<String, Object> cm = data(this.api.outline("secret-token", request));
        assertThat((Double) cm.get("areaCm2")).isBetween(7.8, 8.2);
        request.setOutline(Arrays.asList(1.0, 2.0));
        assertThat(this.api.outline("secret-token", request).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
