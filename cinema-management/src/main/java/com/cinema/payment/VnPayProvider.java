package com.cinema.payment;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * VNPay payment provider (Req 9.2-9.4 - tích hợp VNPay Sandbox).
 *
 * <p>Provider tạo URL redirect chuẩn theo spec VNPay 2.1.0 và verify checksum
 * khi nhận Return URL/IPN. Trạng thái PENDING cho tới khi VNPay gọi về Return/IPN.
 */
public class VnPayProvider implements PaymentProvider {
    private static final Logger logger = Logger.getLogger(VnPayProvider.class.getName());
    public static final String METHOD = "VNPAY";

    private final VnPayConfig config;

    public VnPayProvider(VnPayConfig config) {
        this.config = config;
    }

    @Override
    public String method() {
        return METHOD;
    }

    @Override
    public PaymentResult charge(PaymentSession session) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("vnp_Version", "2.1.0");
        params.put("vnp_Command", "pay");
        params.put("vnp_TmnCode", config.tmnCode());
        params.put("vnp_Amount", VnPayUtil.toVnpAmount(session.amount()));
        params.put("vnp_CurrCode", "VND");
        // TxnRef tối đa 100 ký tự; DB idempotencyKey cũng nằm trong giới hạn này.
        params.put("vnp_TxnRef", session.idempotencyKey());
        params.put("vnp_OrderInfo", buildOrderInfo(session));
        params.put("vnp_OrderType", "other");
        params.put("vnp_Locale", "vn");
        params.put("vnp_ReturnUrl", config.returnUrl());
        params.put("vnp_IpAddr", "127.0.0.1");
        params.putAll(VnPayUtil.paymentWindow(config.expireMinutes()));

        String url = VnPayUtil.buildRedirectUrl(config.payUrl(), params, config.hashSecret());
        String providerRef = session.idempotencyKey();

        logger.info("[VNPAY] Create payment"
                + " TxnRef=" + providerRef
                + " Amount=" + session.amount()
                + " RedirectUrl=" + maskQueryString(url));
        return PaymentResult.pending(METHOD + "-" + providerRef, url);
    }

    /**
     * Verify callback/return params — true nếu checksum hợp lệ.
     */
    public boolean verify(Map<String, String> params) {
        boolean valid = VnPayUtil.verify(params, config.hashSecret());
        logger.info("[VNPAY] Checksum valid=" + valid);
        return valid;
    }

    public VnPayConfig config() { return config; }

    private String buildOrderInfo(PaymentSession session) {
        // VNPay yêu cầu OrderInfo không dấu, tối đa 100 ký tự, không có ký tự đặc biệt.
        StringBuilder sb = new StringBuilder();
        sb.append("Thanh toan ve xem phim CinemaHub - ticket ");
        sb.append(session.ticketId() == null ? session.paymentId() : session.ticketId());
        String s = sb.toString().replaceAll("[^\\x20-\\x7E]", "?");
        return s.length() > 100 ? s.substring(0, 100) : s;
    }

    private String maskQueryString(String url) {
        int idx = url.indexOf('?');
        if (idx < 0) return url;
        String qs = url.substring(idx + 1);
        return url.substring(0, idx) + "?" + qs.replaceAll("vnp_SecureHash=[^&]+", "vnp_SecureHash=***");
    }
}
