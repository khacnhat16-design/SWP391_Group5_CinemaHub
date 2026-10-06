package com.cinema.payment;

import com.cinema.util.HmacUtil;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tiện ích VNPay theo tài liệu <i>VNPay Integration Specification 2.1.0</i>:
 * <ul>
 *   <li>Sắp xếp tham số theo key tăng dần (a-z), không bao gồm {@code vnp_SecureHash} / {@code vnp_SecureHashType}.</li>
 *   <li>Khi tạo chữ ký: nối {@code key=value} với {@code &}, URL-encode theo {@code application/x-www-form-urlencoded}.</li>
 *   <li>Checksum bằng HmacSHA512 (VNPay yêu cầu từ 2022). HmacSHA256 cũ vẫn hoạt động qua {@link HmacUtil} nhưng sandbox ưu tiên SHA512.</li>
 *   <li>Verify: tính lại checksum từ parameter gửi về, so sánh an toàn (constant-time).</li>
 * </ul>
 * KHÔNG log secret ra log.
 */
public final class VnPayUtil {
    private static final String SECURE_HASH_TYPE = "HmacSHA512";
    private static final ZoneId VNPAY_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter VNP_DATE_FMT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private VnPayUtil() { }

    /** Build VNPay timestamps in its required GMT+7 timezone from one instant. */
    public static Map<String, String> paymentWindow(int expireMinutes) {
        return paymentWindow(expireMinutes, Clock.systemUTC());
    }

    static Map<String, String> paymentWindow(int expireMinutes, Clock clock) {
        if (expireMinutes <= 0) {
            throw new IllegalArgumentException("Thời hạn thanh toán VNPay phải lớn hơn 0 phút.");
        }
        Instant created = clock.instant();
        LocalDateTime createDate = LocalDateTime.ofInstant(created, VNPAY_ZONE);
        LocalDateTime expireDate = LocalDateTime.ofInstant(
                created.plusSeconds(expireMinutes * 60L), VNPAY_ZONE);
        Map<String, String> result = new LinkedHashMap<>();
        result.put("vnp_CreateDate", createDate.format(VNP_DATE_FMT));
        result.put("vnp_ExpireDate", expireDate.format(VNP_DATE_FMT));
        return result;
    }

    /** VNPay transaction timestamps (including callback pay dates) use GMT+7. */
    public static String currentDateTime() {
        return LocalDateTime.ofInstant(Instant.now(), VNPAY_ZONE).format(VNP_DATE_FMT);
    }

    /** Tạo query string đã URL-encode để redirect sang VNPay (theo spec). */
    public static String buildRedirectUrl(String baseUrl, Map<String, String> params, String hashSecret) {
        // VNPay yêu cầu các field phải sort alphabetical theo key TRƯỚC khi hash,
        // và vnp_SecureHash* phải LOẠI RA khỏi input hash (chỉ append SAU hash).
        List<String> fieldNames = new ArrayList<>(params.keySet());
        Collections.sort(fieldNames);

        StringBuilder hashData = new StringBuilder();
        StringBuilder query = new StringBuilder();
        try {
            for (int i = 0; i < fieldNames.size(); i++) {
                String key = fieldNames.get(i);
                String value = params.get(key);
                if (value == null || value.isEmpty()) continue;
                // hashData: dùng cho HMAC — phải encode value, key GIỮ NGUYÊN (không encode)
                // đúng theo spec VNPay (encoding không đối xứng giữa hashData và query).
                hashData.append(key).append('=').append(URLEncoder.encode(value, "UTF-8"));
                // query: dùng để gửi lên VNPay — encode CẢ key lẫn value; nối bằng '&'
                // và phân cách cặp key=value bằng dấu '&' đúng spec.
                query.append(URLEncoder.encode(key, "UTF-8"))
                     .append('=')
                     .append(URLEncoder.encode(value, "UTF-8"));
                if (i < fieldNames.size() - 1) {
                    query.append('&');
                    hashData.append('&');
                }
            }
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 not supported", e);
        }

        String secureHash = hmacSHA512(hashSecret, hashData.toString());
        query.append("&vnp_SecureHashType=").append(SECURE_HASH_TYPE)
             .append("&vnp_SecureHash=").append(secureHash);
        return baseUrl + "?" + query;
    }

    /**
     * Verify checksum từ phản hồi của VNPay.
     * Loại bỏ {@code vnp_SecureHash} và {@code vnp_SecureHashType} trước khi tính lại hash.
     *
     * @return true nếu checksum khớp.
     */
    public static boolean verify(Map<String, String> params, String hashSecret) {
        if (params == null) return false;
        String providedHash = params.get("vnp_SecureHash");
        if (providedHash == null || providedHash.isBlank()) return false;

        Map<String, String> sorted = new HashMap<>();
        for (Map.Entry<String, String> e : params.entrySet()) {
            String k = e.getKey();
            if (k == null) continue;
            if ("vnp_SecureHash".equals(k) || "vnp_SecureHashType".equals(k)) continue;
            if (e.getValue() == null || e.getValue().isEmpty()) continue;
            sorted.put(k, e.getValue());
        }
        List<String> fieldNames = new ArrayList<>(sorted.keySet());
        Collections.sort(fieldNames);

        StringBuilder hashData = new StringBuilder();
        try {
            for (int i = 0; i < fieldNames.size(); i++) {
                String key = fieldNames.get(i);
                hashData.append(key).append('=').append(URLEncoder.encode(sorted.get(key), "UTF-8"));
                if (i < fieldNames.size() - 1) hashData.append('&');
            }
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 not supported", e);
        }
        String expected = hmacSHA512(hashSecret, hashData.toString());
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                providedHash.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Parse tất cả tham số có tiền tố {@code vnp_} từ {@link jakarta.servlet.http.HttpServletRequest}.
     */
    public static Map<String, String> extractVnpParams(jakarta.servlet.http.HttpServletRequest request) {
        Map<String, String> result = new HashMap<>();
        java.util.Enumeration<String> names = request.getParameterNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            if (name == null || !name.startsWith("vnp_")) continue;
            result.put(name, request.getParameter(name));
        }
        return result;
    }

    /**
     * VNPay yêu cầu số tiền nhân 100 (đơn vị: đồng × 100 = xu). Nếu DB của ta đang giữ đơn vị đồng
     * thì phải {@code ×100}. Nếu giữ xu rồi thì giữ nguyên. Hiện tại DB lưu đồng (amount).
     */
    public static String toVnpAmount(long amountInVnd) {
        return Long.toString(amountInVnd * 100L);
    }

    /** Parse {@code vnp_Amount} (đơn vị xu) về đơn vị VND (DB). */
    public static long fromVnpAmount(String vnpAmount) {
        if (vnpAmount == null || vnpAmount.isBlank()) return 0L;
        long raw = Long.parseLong(vnpAmount);
        return raw / 100L;
    }

    /** HmacSHA512 hex (lowercase). */
    public static String hmacSHA512(String secret, String payload) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA512");
            mac.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),
                    "HmacSHA512"));
            byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA512 không khả dụng", e);
        }
    }
}
