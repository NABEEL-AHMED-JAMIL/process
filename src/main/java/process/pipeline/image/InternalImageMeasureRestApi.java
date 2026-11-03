package process.pipeline.image;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import process.model.dto.ResponseDto;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Image review (the third Tools page, owner-approved 2026-11-03) measures its photos here, with the code the
 * {@code measure_image} and {@code measure_mask} steps use -- one code path, never a copy (owner decision 2026-09-29: a
 * size is measured in code against the ruler in the photo, never estimated):
 *
 * <ul>
 *   <li>{@code POST /internal/image/measure?tenantId=&target=} with the photo's bytes (png or jpg, already stripped of its
 *       metadata by media) answers {@link RulerMeasure#measure} with its geometry: a photo without a ruler has no size.</li>
 *   <li>{@code POST /internal/image/outline} {width, height, outline: [x, y, ...], pxPerCm} re-measures a region a person drew
 *       ({@link RulerMeasure#drawn}): in cm only with the photo's ruler scale, otherwise in pixels only.</li>
 * </ul>
 *
 * Service token only (media-service); the gateway never exposes /internal. Nothing is stored or logged but the sizes: the photo
 * is read in memory and dropped.
 */
@RestController
@RequestMapping("/internal/image")
public class InternalImageMeasureRestApi {

    /** The most pixels a photo measured here may have (8,000 x 6,000), read from its header before it is decoded. */
    static final long MAX_PIXELS = 48_000_000L;

    /** The largest photo's bytes (Image review takes 25 MB a photo). */
    static final int MAX_BYTES = 26 * 1024 * 1024;

    private final Logger logger = LoggerFactory.getLogger(InternalImageMeasureRestApi.class);
    private final byte[] token;

    public InternalImageMeasureRestApi(@Value("${internal.service-token:}") String token) {
        this.token = token == null ? new byte[0] : token.trim().getBytes(StandardCharsets.UTF_8);
    }

    /** A drawn outline to re-measure. */
    public static class OutlineRequest {
        private Long tenantId;
        private Integer width;
        private Integer height;
        private List<Double> outline;
        private Double pxPerCm;

        public Long getTenantId() {
            return this.tenantId;
        }

        public void setTenantId(Long tenantId) {
            this.tenantId = tenantId;
        }

        public Integer getWidth() {
            return this.width;
        }

        public void setWidth(Integer width) {
            this.width = width;
        }

        public Integer getHeight() {
            return this.height;
        }

        public void setHeight(Integer height) {
            this.height = height;
        }

        public List<Double> getOutline() {
            return this.outline;
        }

        public void setOutline(List<Double> outline) {
            this.outline = outline;
        }

        public Double getPxPerCm() {
            return this.pxPerCm;
        }

        public void setPxPerCm(Double pxPerCm) {
            this.pxPerCm = pxPerCm;
        }
    }

    @PostMapping(value = "/measure", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> measure(@RequestHeader(value = "X-Internal-Token", required = false) String presented,
                                     @RequestParam(value = "tenantId", required = false) Long tenantId,
                                     @RequestParam(value = "target", required = false) String target,
                                     @RequestBody(required = false) byte[] photo) {
        if (!this.admits(presented)) {
            return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);
        }
        if (tenantId == null || tenantId < 0) {
            return ResponseEntity.badRequest().body(new ResponseDto("ERROR", "Name the workspace (tenantId)."));
        }
        RulerMeasure.Target chosen = RulerMeasure.Target.of(target == null ? RulerMeasure.Target.CONTRAST.code() : target).orElse(null);
        if (chosen == null) {
            return ResponseEntity.badRequest().body(new ResponseDto("ERROR", "target is contrast or red_region."));
        }
        if (photo == null || photo.length == 0) {
            return ResponseEntity.badRequest().body(new ResponseDto("ERROR", "Send the photo's bytes."));
        }
        if (photo.length > MAX_BYTES) {
            return ResponseEntity.badRequest().body(new ResponseDto("ERROR", "The photo is larger than 25 MB."));
        }
        BufferedImage image;
        try {
            image = read(photo);
        } catch (IOException unreadable) {
            return ResponseEntity.badRequest().body(new ResponseDto("ERROR", unreadable.getMessage()));
        }
        long started = System.nanoTime();
        RulerMeasure.Measurement m = RulerMeasure.measure(image, chosen);
        this.logger.info("Image review: workspace {} photo {} x {} measured ({}) in {} ms: {}", tenantId, image.getWidth(), image.getHeight(),
            chosen.code(), (System.nanoTime() - started) / 1_000_000, m.note);
        return ResponseEntity.ok(new ResponseDto("SUCCESS", "Measured.", shape(m)));
    }

    @PostMapping(value = "/outline", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> outline(@RequestHeader(value = "X-Internal-Token", required = false) String presented,
                                     @RequestBody(required = false) OutlineRequest request) {
        if (!this.admits(presented)) {
            return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);
        }
        if (request == null || request.getTenantId() == null || request.getTenantId() < 0) {
            return ResponseEntity.badRequest().body(new ResponseDto("ERROR", "Name the workspace (tenantId)."));
        }
        if (request.getWidth() == null || request.getHeight() == null || request.getOutline() == null) {
            return ResponseEntity.badRequest().body(new ResponseDto("ERROR", "Send the photo's width and height and the outline."));
        }
        double[] points = new double[request.getOutline().size()];
        for (int i = 0; i < points.length; i++) {
            Double value = request.getOutline().get(i);
            if (value == null || value.isNaN() || value.isInfinite()) {
                return ResponseEntity.badRequest().body(new ResponseDto("ERROR", "Every outline point is a number."));
            }
            points[i] = value;
        }
        try {
            RulerMeasure.Measurement m = RulerMeasure.drawn(request.getWidth(), request.getHeight(), points, request.getPxPerCm());
            this.logger.info("Image review: workspace {} drawn outline re-measured: {}", request.getTenantId(), m.note);
            return ResponseEntity.ok(new ResponseDto("SUCCESS", "Measured.", shape(m)));
        } catch (IllegalArgumentException refused) {
            return ResponseEntity.badRequest().body(new ResponseDto("ERROR", refused.getMessage()));
        }
    }

    /** The photo, its size read from the header first: more than {@value #MAX_PIXELS} pixels is refused before decoding. */
    static BufferedImage read(byte[] photo) throws IOException {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(photo))) {
            Iterator<ImageReader> readers = in == null ? null : ImageIO.getImageReaders(in);
            if (readers == null || !readers.hasNext()) {
                throw new IOException("The file is not a png or jpg image.");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
                if (pixels > MAX_PIXELS) {
                    throw new IOException(String.format(Locale.ROOT, "The photo is %d x %d pixels; at most %,d pixels are measured.",
                        reader.getWidth(0), reader.getHeight(0), MAX_PIXELS));
                }
                BufferedImage image = reader.read(0);
                if (image == null) {
                    throw new IOException("The file is not a png or jpg image.");
                }
                return image;
            } catch (RuntimeException unreadable) {
                throw new IOException("The file is not a png or jpg image.");
            } finally {
                reader.dispose();
            }
        }
    }

    static Map<String, Object> shape(RulerMeasure.Measurement m) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scaleFound", m.scaleFound);
        out.put("pxPerCm", m.pxPerCm);
        out.put("lengthCm", m.lengthCm);
        out.put("widthCm", m.widthCm);
        out.put("areaCm2", m.areaCm2);
        out.put("note", m.note);
        RulerMeasure.Geometry g = m.geometry;
        if (g != null) {
            Map<String, Object> geometry = new LinkedHashMap<>();
            geometry.put("width", g.width);
            geometry.put("height", g.height);
            geometry.put("meanLuminance", g.meanLuminance);
            geometry.put("rulerBox", g.rulerBox);
            geometry.put("rulerTicks", g.rulerTicks);
            geometry.put("outline", g.outline);
            geometry.put("lengthLine", g.lengthLine);
            geometry.put("widthLine", g.widthLine);
            geometry.put("areaPx", g.areaPx);
            geometry.put("lengthPx", g.lengthPx);
            geometry.put("widthPx", g.widthPx);
            out.put("geometry", geometry);
        }
        return out;
    }

    private boolean admits(String presented) {
        return this.token.length > 0 && presented != null
            && MessageDigest.isEqual(this.token, presented.trim().getBytes(StandardCharsets.UTF_8));
    }
}
