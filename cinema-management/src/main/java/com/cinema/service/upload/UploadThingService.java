package com.cinema.service.upload;

import com.cinema.common.ServiceException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class UploadThingService {

    private static final Logger logger = Logger.getLogger(UploadThingService.class.getName());
    private static final Pattern SECURE_URL = Pattern.compile("\"secure_url\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern PUBLIC_ID = Pattern.compile("\"public_id\"\\s*:\\s*\"([^\"]+)\"");

    public static final Set<String> ALLOWED_MIME_TYPES = Set.of(
            "image/jpeg", "image/jpg", "image/png", "image/webp");
    public static final Set<String> ALLOWED_EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp");
    public static final long MAX_FILE_SIZE_BYTES = 5L * 1024 * 1024;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final String cloudName;
    private final String apiKey;
    private final String apiSecret;

    public UploadThingService() {
        String cloudinaryUrl = firstConfigured("CLOUDINARY_URL");
        String[] parsed = parseCloudinaryUrl(cloudinaryUrl);
        this.cloudName = firstConfigured("CLOUDINARY_CLOUD_NAME") != null
                ? firstConfigured("CLOUDINARY_CLOUD_NAME") : parsed[0];
        this.apiKey = firstConfigured("CLOUDINARY_API_KEY") != null
                ? firstConfigured("CLOUDINARY_API_KEY") : parsed[1];
        this.apiSecret = firstConfigured("CLOUDINARY_API_SECRET") != null
                ? firstConfigured("CLOUDINARY_API_SECRET") : parsed[2];
        if (!configured()) {
            logger.warning("Cloudinary is not configured. Set CLOUDINARY_URL or CLOUDINARY_CLOUD_NAME/API_KEY/API_SECRET.");
        }
    }

    public UploadResult upload(Path filePath, String originalFilename, String mimeType) throws Exception {
        if (!configured()) {
            throw new ServiceException.Validation(
                    "Cloudinary chưa được cấu hình (CLOUDINARY_URL hoặc CLOUDINARY_CLOUD_NAME/API_KEY/API_SECRET).");
        }
        if (filePath == null || !Files.exists(filePath)) {
            throw new ServiceException.Validation("File không tồn tại");
        }
        long size = Files.size(filePath);
        if (size > MAX_FILE_SIZE_BYTES) {
            throw new ServiceException.Validation("File vượt quá dung lượng tối đa 5MB");
        }

        String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
        String signature = sha1("timestamp=" + timestamp + apiSecret);
        String boundary = "----CinemaHubCloudinary" + System.currentTimeMillis();
        byte[] body = multipart(filePath, originalFilename, mimeType, boundary,
                timestamp, signature, apiKey);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.cloudinary.com/v1_1/" + cloudName + "/image/upload"))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            logger.warning("Cloudinary upload failed: HTTP " + response.statusCode()
                    + " body=" + response.body());
            throw new ServiceException.BusinessRule("UPLOAD_FAILED",
                    "Cloudinary trả về lỗi HTTP " + response.statusCode());
        }

        String url = field(response.body(), SECURE_URL);
        String publicId = field(response.body(), PUBLIC_ID);
        if (url == null || url.isBlank()) {
            throw new ServiceException.BusinessRule("UPLOAD_FAILED",
                    "Cloudinary không trả về secure_url hợp lệ");
        }
        return new UploadResult(url, publicId);
    }

    public void tryDelete(String publicId) {
    }

    public static String extractKeyFromUrl(String url) {
        if (url == null || url.isBlank()) return null;
        int slash = url.lastIndexOf('/');
        return slash >= 0 && slash < url.length() - 1 ? url.substring(slash + 1) : null;
    }

    private boolean configured() {
        return notBlank(cloudName) && notBlank(apiKey) && notBlank(apiSecret);
    }

    private static byte[] multipart(Path file, String originalFilename, String mimeType,
                                    String boundary, String timestamp, String signature,
                                    String configuredApiKey)
            throws IOException {
        String filename = originalFilename == null || originalFilename.isBlank()
                ? file.getFileName().toString() : originalFilename;
        String type = mimeType == null ? "application/octet-stream" : mimeType;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        addField(out, boundary, "api_key", configuredApiKey);
        addField(out, boundary, "timestamp", timestamp);
        addField(out, boundary, "signature", signature);
        out.write(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: " + type + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(Files.readAllBytes(file));
        out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    private static void addField(ByteArrayOutputStream out, String boundary,
                                 String name, String value) throws IOException {
        out.write(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
                + value + "\r\n").getBytes(StandardCharsets.UTF_8));
    }

    private static String[] parseCloudinaryUrl(String value) {
        if (value == null || value.isBlank() || !value.startsWith("cloudinary://")) {
            return new String[] {null, null, null};
        }
        try {
            URI uri = URI.create(value);
            String userInfo = uri.getUserInfo();
            String[] credentials = userInfo == null ? new String[0] : userInfo.split(":", 2);
            return new String[] {uri.getHost(), credentials.length > 0 ? credentials[0] : null,
                    credentials.length > 1 ? credentials[1] : null};
        } catch (IllegalArgumentException e) {
            return new String[] {null, null, null};
        }
    }

    private static String firstConfigured(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) value = System.getProperty(key);
        if (value == null || value.isBlank()) return null;
        value = value.trim();
        if (value.length() >= 2 && ((value.startsWith("'") && value.endsWith("'"))
                || (value.startsWith("\"") && value.endsWith("\"")))) {
            value = value.substring(1, value.length() - 1).trim();
        }
        return value.isBlank() ? null : value;
    }

    private static String field(String json, Pattern pattern) {
        Matcher matcher = pattern.matcher(json == null ? "" : json);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String sha1(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-1")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte item : digest) hex.append(String.format("%02x", item));
        return hex.toString();
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    public record UploadResult(String url, String fileKey) { }
}
