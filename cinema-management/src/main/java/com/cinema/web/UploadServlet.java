package com.cinema.web;

import com.cinema.auth.AccessScope;
import com.cinema.auth.Role;
import com.cinema.common.ErrorEnvelope;
import com.cinema.common.SerializationUtil;
import com.cinema.common.ServiceException;
import com.cinema.filter.AuthFilter;
import com.cinema.site.SiteSettingService;
import com.cinema.upload.UploadThingService;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.MultipartConfig;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Multipart image upload endpoint. Accepts a single {@code file} part, validates it
 * server-side, uploads to <strong>Cloudinary</strong> via {@link UploadThingService}, and
 * returns the public CDN URL as JSON.
 *
 * <p>Access: Admin / Branch Manager only (poster upload is a management action).
 * The returned URL is stored in {@code movie.poster_url} (or other entity columns
 * that may need images in the future).
 */
@MultipartConfig(
        fileSizeThreshold = 1024 * 1024,         // 1MB
        maxFileSize = UploadThingService.MAX_FILE_SIZE_BYTES,
        maxRequestSize = UploadThingService.MAX_FILE_SIZE_BYTES + 1024
)
public class UploadServlet extends HttpServlet {

    private static final Logger logger = Logger.getLogger(UploadServlet.class.getName());
    private static final Set<String> ALLOWED_MIME = UploadThingService.ALLOWED_MIME_TYPES;
    private static final Set<String> ALLOWED_EXT = UploadThingService.ALLOWED_EXTENSIONS;

    /** Lazily initialized to avoid requiring the table to exist during unit tests. */
    private SiteSettingService siteSettingService;

    private SiteSettingService getSiteSettingService() {
        if (siteSettingService == null) {
            siteSettingService = new SiteSettingService();
        }
        return siteSettingService;
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        try {
            AccessScope scope = (AccessScope) request.getAttribute(AuthFilter.SCOPE_ATTRIBUTE);
            if (scope == null || scope.isGuest()) {
                sendError(response, 401, "UNAUTHORIZED", "Cần đăng nhập");
                return;
            }
            if (scope.role() != Role.ADMIN && scope.role() != Role.BRANCH_MANAGER) {
                sendError(response, 403, "FORBIDDEN", "Chỉ Admin/Manager được upload ảnh");
                return;
            }

            Part filePart = request.getPart("file");
            if (filePart == null || filePart.getSize() == 0) {
                sendError(response, 400, "BAD_REQUEST", "Thiếu file upload");
                return;
            }
            if (filePart.getSize() > UploadThingService.MAX_FILE_SIZE_BYTES) {
                sendError(response, 400, "BAD_REQUEST",
                        "File vượt quá dung lượng tối đa "
                                + (UploadThingService.MAX_FILE_SIZE_BYTES / 1024 / 1024) + "MB");
                return;
            }

            String originalFilename = filePart.getSubmittedFileName();
            String mimeType = filePart.getContentType();
            String extension = extensionOf(originalFilename);

            // Defense in depth: client có thể gửi MIME giả trong header → kiểm
            // cả MIME type và extension khớp whitelist để chặn upload .php đội lốt .jpg.
            if (mimeType == null || !ALLOWED_MIME.contains(mimeType.toLowerCase(Locale.ROOT))) {
                sendError(response, 400, "BAD_REQUEST",
                        "Chỉ chấp nhận định dạng ảnh JPEG/JPG/PNG/WEBP (MIME không hợp lệ: " + mimeType + ")");
                return;
            }
            if (extension == null || !ALLOWED_EXT.contains(extension)) {
                sendError(response, 400, "BAD_REQUEST",
                        "Phần mở rộng file không hợp lệ (chỉ jpg/jpeg/png/webp)");
                return;
            }
            // Magic-byte sniffing — first bytes must match the claimed MIME family.
            try (InputStream in = filePart.getInputStream()) {
                byte[] head = in.readNBytes(12);
                if (!looksLikeImage(head, mimeType)) {
                    sendError(response, 400, "BAD_REQUEST",
                            "Nội dung file không phải ảnh hợp lệ");
                    return;
                }
            }

            // Lưu ra temp file trước để UploadThingService có thể đọc được multipart
            // stream hai lần (validate magic bytes đã xong ở trên rồi). Sau khi
            // upload xong, temp file được service tự dọn — không giữ trên server.
            String safeName = UUID.randomUUID() + "." + extension;
            Path temp = Files.createTempFile("upload-", "-" + safeName);
            try {
                try (InputStream in = filePart.getInputStream()) {
                    Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
                }

                UploadThingService service = new UploadThingService();
                UploadThingService.UploadResult result = service.upload(temp, originalFilename, mimeType);
                logger.info("Uploaded poster to Cloudinary: " + result.url());

                // Convenience: mỗi lần Admin upload ảnh mới, tự cập nhật home_banner_url
                // để ảnh mới nhất hiện luôn ở hero trang chủ — tiết kiệm bước thủ công.
                getSiteSettingService().setHomeBannerUrl(result.url());

                sendOk(response, java.util.Map.of(
                        "url", result.url(),
                        "fileKey", result.fileKey() != null ? result.fileKey() : "",
                        "filename", originalFilename != null ? originalFilename : safeName,
                        "size", filePart.getSize(),
                        "mimeType", mimeType
                ));
            } finally {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) { /* best-effort cleanup */ }
            }
        } catch (ServiceException service) {
            sendError(response, service.httpStatus(), service.code(), service.getMessage());
        } catch (Exception e) {
            logger.warning("Upload failed: " + e.getMessage());
            sendError(response, 500, "INTERNAL_ERROR", "Upload thất bại: " + e.getMessage());
        }
    }

    private static String extensionOf(String name) {
        if (name == null) return null;
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return null;
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** Minimal magic-byte validation. We don't need a full image library; checking the first bytes is enough. */
    private static boolean looksLikeImage(byte[] head, String mime) {
        if (head == null || head.length < 4) return false;
        String m = mime.toLowerCase(Locale.ROOT);
        if (m.contains("jpeg") || m.contains("jpg")) {
            // JPEG starts with FF D8 FF
            return (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8 && (head[2] & 0xFF) == 0xFF;
        }
        if (m.contains("png")) {
            // PNG signature: 89 50 4E 47 0D 0A 1A 0A
            return head.length >= 8
                    && (head[0] & 0xFF) == 0x89
                    && (head[1] & 0xFF) == 0x50
                    && (head[2] & 0xFF) == 0x4E
                    && (head[3] & 0xFF) == 0x47;
        }
        if (m.contains("webp")) {
            // WEBP: "RIFF".... "WEBP"
            return head.length >= 12
                    && head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F'
                    && head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P';
        }
        return false;
    }

    private void sendOk(HttpServletResponse response, Object data) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(SerializationUtil.toJson(data));
    }

    private void sendError(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(SerializationUtil.toJson(new ErrorEnvelope(code, message)));
    }
}
