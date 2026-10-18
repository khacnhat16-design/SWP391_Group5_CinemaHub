package com.cinema.upload;

import com.cinema.model.auth.User;
import com.cinema.service.upload.UploadThingService;

import com.cinema.infrastructure.database.DBContext;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One-shot migration utility that:
 * <ol>
 *   <li>Reads every {@code poster_url} from {@code dbo.movie}</li>
 *   <li>For Unsplash/external URLs: proxies the image through this JVM (no API key needed)</li>
 *   <li>Uploads each to UploadThing via {@link UploadThingService}</li>
 *   <li>Updates the DB row with the new UploadThing CDN URL</li>
 *   <li>Also seeds the {@code home_banner_url} site_setting with the most recent upload</li>
 * </ol>
 *
 * <p>Run with (from the cinema-management directory):
 * <pre>
 *   mvn compile exec:java -Dexec.mainClass=com.cinema.upload.UploadThingMigration
 * </pre>
 *
 * <p>No arguments required. Configure {@code .env} with {@code UPLOADTHING_TOKEN}
 * before running.
 */
public final class UploadThingMigration {

    private static final Logger logger = Logger.getLogger(UploadThingMigration.class.getName());
    private static final Pattern URL_FIELD = Pattern.compile("\"url\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern KEY_FIELD = Pattern.compile("\"key\"\\s*:\\s*\"([^\"]+)\"");

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final String apiKey;
    private final String apiBase;

    public static void main(String[] args) throws Exception {
        new UploadThingMigration().run();
    }

    public UploadThingMigration() {
        String configuredKey = System.getProperty("UPLOADTHING_TOKEN",
                System.getenv("UPLOADTHING_TOKEN"));
        if (configuredKey == null || configuredKey.isBlank()) {
            configuredKey = System.getProperty("UPLOADTHING_SECRET",
                    System.getenv("UPLOADTHING_SECRET"));
        }
        if (configuredKey == null || configuredKey.isBlank()) {
            configuredKey = System.getProperty("UPLOADTHING_API_KEY",
                    System.getenv("UPLOADTHING_API_KEY"));
        }
        this.apiKey = extractApiKey(configuredKey);
        this.apiBase = System.getProperty("UPLOADTHING_API_URL",
                System.getenv("UPLOADTHING_API_URL"));
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "UPLOADTHING_TOKEN/UPLOADTHING_SECRET not set. Please configure .env or set the environment variable.");
        }
    }

    private static String extractApiKey(String configuredValue) {
        if (configuredValue == null) return null;
        String value = configuredValue.trim();
        if (value.length() >= 2
                && ((value.startsWith("'") && value.endsWith("'"))
                || (value.startsWith("\"") && value.endsWith("\"")))) {
            value = value.substring(1, value.length() - 1).trim();
        }
        if (value.startsWith("sk_")) return value;
        try {
            String payload = new String(java.util.Base64.getDecoder().decode(value),
                    java.nio.charset.StandardCharsets.UTF_8);
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("\"apiKey\"\\s*:\\s*\"([^\"]+)\"").matcher(payload);
            return matcher.find() ? matcher.group(1) : value;
        } catch (IllegalArgumentException e) {
            return value;
        }
    }

    private void run() throws Exception {
        System.out.println("=== UploadThing Migration ===");
        System.out.println("API base: " + apiBase);

        List<MovieRow> movies = fetchMovies();
        System.out.println("Found " + movies.size() + " movie(s) in DB");

        Path tempDir = Files.createTempDirectory("ut-migration-");
        String lastUrl = null;
        int success = 0;
        int skipped = 0;
        int failed = 0;

        for (MovieRow movie : movies) {
            String currentUrl = movie.posterUrl;
            System.out.printf("%n[%d] %s — current: %s%n", movie.id, movie.title, currentUrl);

            if (currentUrl == null || currentUrl.isBlank()) {
                System.out.println("  → SKIP (null/empty poster_url)");
                skipped++;
                continue;
            }

            if (isAlreadyUploadThing(currentUrl)) {
                System.out.println("  → SKIP (already UploadThing URL: " + currentUrl + ")");
                skipped++;
                lastUrl = currentUrl;
                continue;
            }

            // Tải ảnh gốc từ URL hiện tại xuống temp file → upload lên UploadThing.
            // Bước này dùng để migrate ảnh cũ (Unsplash, hosting khác) sang hosting nội bộ.
            Path tmpFile = null;
            try {
                tmpFile = downloadImage(currentUrl, tempDir, movie.id);
                String mimeType = probeMime(tmpFile);
                System.out.println("  → Downloaded (" + Files.size(tmpFile) + " bytes, MIME=" + mimeType + ")");

                UploadResult result = uploadToUploadThing(tmpFile, filenameOf(movie.title), mimeType);
                System.out.println("  → UploadThing: " + result.url());

                // Ghi URL mới xuống DB; không xoá ảnh cũ vì có thể hosting cũ vẫn đang serve.
                updatePosterUrl(movie.id, result.url());
                System.out.println("  → DB updated");
                updatePosterUrl(movie.id, result.url());
                System.out.println("  → DB updated");
                lastUrl = result.url();
                success++;
            } catch (Exception e) {
                System.err.println("  → FAILED: " + e.getMessage());
                logger.log(Level.WARNING, "Migration failed for movie " + movie.id, e);
                failed++;
            } finally {
                if (tmpFile != null) Files.deleteIfExists(tmpFile);
            }
        }

        // Sau khi migrate hết phim, banner trang chủ cũng set theo ảnh cuối
        // cùng thành công — giữ UX liền mạch (banner = poster mới nhất).
        if (lastUrl != null && !lastUrl.isBlank()) {
            upsertSiteSetting("home_banner_url", lastUrl);
            System.out.println("Updated home_banner_url → " + lastUrl);
        }

        // Cleanup
        Files.deleteIfExists(tempDir);

        System.out.printf("%n=== Done: %d migrated, %d skipped, %d failed ===%n",
                success, skipped, failed);
    }

    private List<MovieRow> fetchMovies() throws Exception {
        List<MovieRow> rows = new ArrayList<>();
        String sql = "SELECT id, title, poster_url FROM dbo.movie WHERE poster_url IS NOT NULL";
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                MovieRow row = new MovieRow(
                        rs.getLong("id"),
                        rs.getString("title"),
                        rs.getString("poster_url")
                );
                rows.add(row);
            }
        }
        return rows;
    }

    private Path downloadImage(String url, Path tempDir, long movieId) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "CinemaHub-Migration/1.0")
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() != 200) {
            throw new IOException("HTTP " + resp.statusCode() + " fetching " + url);
        }
        String ext = extensionFromContentType(resp.headers().firstValue("Content-Type").orElse("image/jpeg"));
        Path out = tempDir.resolve(movieId + ext);
        Files.write(out, resp.body());
        return out;
    }

    private String probeMime(Path file) {
        try {
            String t = Files.probeContentType(file);
            if (t != null && t.startsWith("image/")) return t;
        } catch (IOException ignored) { }
        return "image/jpeg";
    }

    private String extensionFromContentType(String ct) {
        if (ct == null) return ".jpg";
        return switch (ct.toLowerCase()) {
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            case "image/gif" -> ".gif";
            default -> ".jpg";
        };
    }

    private String filenameOf(String title) {
        return title.replaceAll("[^a-zA-Z0-9\\u00C0-\\u024F\\s]", "")
                .replaceAll("\\s+", "-")
                .toLowerCase()
                .substring(0, Math.min(60, title.length())) + ".jpg";
    }

    private boolean isAlreadyUploadThing(String url) {
        return url != null && (url.contains("utfs.io") || url.contains("uploadthing"));
    }

    private UploadResult uploadToUploadThing(Path file, String filename, String mimeType) throws Exception {
        String boundary = "----MigrationBoundary" + System.currentTimeMillis();
        byte[] body = buildMultipartBody(file, filename, mimeType, boundary);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiBase + "/v6/uploadFiles"))
                .timeout(Duration.ofSeconds(60))
                .header("x-uploadthing-api-key", apiKey)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("UploadThing HTTP " + response.statusCode() + ": " + response.body());
        }

        String url = extractField(response.body(), URL_FIELD);
        String key = extractField(response.body(), KEY_FIELD);
        if (url == null) throw new IOException("No URL in UploadThing response: " + response.body());
        return new UploadResult(url, key);
    }

    private byte[] buildMultipartBody(Path file, String filename, String mimeType, String boundary) throws IOException {
        StringBuilder head = new StringBuilder();
        head.append("--").append(boundary).append("\r\n");
        head.append("Content-Disposition: form-data; name=\"file\"; filename=\"").append(filename).append("\"\r\n");
        head.append("Content-Type: ").append(mimeType).append("\r\n");
        head.append("\r\n");

        byte[] fileBytes = Files.readAllBytes(file);
        byte[] headBytes = head.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] tailBytes = ("\r\n--" + boundary + "--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);

        byte[] result = new byte[headBytes.length + fileBytes.length + tailBytes.length];
        System.arraycopy(headBytes, 0, result, 0, headBytes.length);
        System.arraycopy(fileBytes, 0, result, headBytes.length, fileBytes.length);
        System.arraycopy(tailBytes, 0, result, headBytes.length + fileBytes.length, tailBytes.length);
        return result;
    }

    private String extractField(String json, Pattern pattern) {
        Matcher m = pattern.matcher(json);
        if (m.find()) return m.group(1);
        return null;
    }

    private void updatePosterUrl(long movieId, String newUrl) throws Exception {
        String sql = "UPDATE dbo.movie SET poster_url = ? WHERE id = ?";
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, newUrl);
            ps.setLong(2, movieId);
            ps.executeUpdate();
        }
    }

    private void upsertSiteSetting(String key, String value) throws Exception {
        String sql = """
            MERGE dbo.site_settings AS target
            USING (SELECT ? AS setting_key) AS source
            ON target.setting_key = source.setting_key
            WHEN MATCHED THEN UPDATE SET setting_value = ?, updated_at = SYSUTCDATETIME()
            WHEN NOT MATCHED THEN INSERT (setting_key, setting_value, updated_at)
                VALUES (?, ?, SYSUTCDATETIME());
            """;
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.setString(3, key);
            ps.setString(4, value);
            ps.executeUpdate();
        }
    }

    private record MovieRow(long id, String title, String posterUrl) { }
    private record UploadResult(String url, String key) { }
}
