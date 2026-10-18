package com.cinema.controller.payment;

import com.cinema.controller.VnPayController;
import com.cinema.model.payment.VnPayConfig;
import com.cinema.model.payment.VnPayUtil;

import com.cinema.util.HmacUtil;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mô phỏng cổng thanh toán VNPay ở chế độ SANDBOX nội bộ (không redirect ra
 * {@code sandbox.vnpayment.vn}). Flow:
 * <ol>
 *   <li>{@code GET /vnpay/demo} – hiển thị form demo với các tham số VNPay + checksum
 *       truyền qua query string.</li>
 *   <li>{@code POST /vnpay/demo/callback} – nhận kết quả giả lập (SUCCESS / FAILED / CANCEL),
 *       gọi {@link VnPayController} để hoàn tất luồng confirm vé giống IPN thật.</li>
 * </ol>
 * URL này thay thế cho {@code payUrl} khi {@link VnPayConfig#isSandbox()} = true,
 * giúp dev/test offline không cần kết nối internet tới sandbox VNPay.
 */
@WebServlet(name = "vnpayDemoServlet", urlPatterns = {"/vnpay/demo", "/vnpay/demo/callback"})
public class VnPayDemoServlet extends HttpServlet {

    private static final String HMAC_SECRET = "4HP0IFJB-vnpay-demo-secret";

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        // Số tiền hiển thị: parse vnp_Amount (đơn vị xu) → VND
        long amountVnd = 0L;
        String vnpAmount = req.getParameter("vnp_Amount");
        if (vnpAmount != null && !vnpAmount.isBlank()) {
            try { amountVnd = Long.parseLong(vnpAmount) / 100L; } catch (NumberFormatException ignored) { }
        }
        req.setAttribute("vnp_AmountLong", amountVnd);
        req.getRequestDispatcher("/WEB-INF/views/payment/vnpay-demo.jsp").forward(req, resp);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        // Người dùng bấm Hủy → redirect về return URL với result=CANCEL
        String action = req.getParameter("action");
        if ("cancel".equalsIgnoreCase(action)) {
            redirectToReturn(req, resp, buildCancelParams(req));
            return;
        }

        String simulated = req.getParameter("simulatedResult");
        if (simulated == null) simulated = "SUCCESS";

        switch (simulated) {
            case "SUCCESS" -> handleSuccess(req, resp);
            case "FAILED" -> handleFailed(req, resp);
            default -> redirectToReturn(req, resp, buildCancelParams(req));
        }
    }

    private void handleSuccess(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Map<String, String> vnpParams = new LinkedHashMap<>();
        String amount = req.getParameter("amount");
        String idempotencyKey = req.getParameter("idempotencyKey");
        vnpParams.put("vnp_Amount", amount != null && !amount.isBlank()
                ? VnPayUtil.toVnpAmount(Long.parseLong(amount))
                : req.getParameter("vnp_Amount") == null ? "0" : req.getParameter("vnp_Amount"));
        vnpParams.put("vnp_TxnRef", idempotencyKey != null && !idempotencyKey.isBlank()
                ? idempotencyKey : req.getParameter("vnp_TxnRef"));
        vnpParams.put("vnp_OrderInfo", req.getParameter("orderInfo") != null
                ? req.getParameter("orderInfo") : "Thanh toan ve xem phim");
        vnpParams.put("vnp_ResponseCode", "00");
        vnpParams.put("vnp_TransactionStatus", "00");
        vnpParams.put("vnp_PayDate", VnPayUtil.currentDateTime());
        vnpParams.put("vnp_BankCode", "DEMO");
        vnpParams.put("vnp_CardType", "ATM");
        // Tính checksum theo secret của demo để VnPayController.verify() pass
        vnpParams.put("vnp_SecureHashType", "HmacSHA512");
        vnpParams.put("vnp_SecureHash",
                VnPayUtil.hmacSHA512(VnPayConfig.load().hashSecret(),
                        buildHashDataForVerify(vnpParams)));

        // Gọi thẳng VnPayController để xử lý callback
        req.setAttribute("vnp_SimulatedCallback", vnpParams);
        forwardToVnPayReturn(req, resp, vnpParams);
    }

    private void handleFailed(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Map<String, String> vnpParams = buildResultParams(req);
        vnpParams.put("vnp_OrderInfo", "Thanh toan ve xem phim");
        vnpParams.put("vnp_ResponseCode", "24"); // 24 = customer cancel
        vnpParams.put("vnp_TransactionStatus", "02");
        vnpParams.put("vnp_PayDate", VnPayUtil.currentDateTime());
        sign(vnpParams);
        forwardToVnPayReturn(req, resp, vnpParams);
    }

    /** Chuyển sang VnPayController xử lý y hệt như Return URL thật. */
    private void forwardToVnPayReturn(HttpServletRequest req, HttpServletResponse resp,
                                     Map<String, String> vnpParams) throws IOException {
        // Gắn các tham số vnp_ vào request để forward sang VnPayController.doGet
        Map<String, String[]> original = new HashMap<>(req.getParameterMap());
        vnpParams.forEach((k, v) -> original.put(k, new String[]{v}));
        req.getSession().setAttribute("vnpay.simulatedCallback", vnpParams);

        String query = vnpParams.entrySet().stream()
                .map(e -> java.net.URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)
                        + "=" + java.net.URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .reduce((a, b) -> a + "&" + b).orElse("");

        resp.sendRedirect(req.getContextPath() + "/vnpay/return?" + query);
    }

    private void redirectToReturn(HttpServletRequest req, HttpServletResponse resp,
                                  Map<String, String> params) throws IOException {
        String query = params.entrySet().stream()
                .map(e -> java.net.URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)
                        + "=" + java.net.URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .reduce((a, b) -> a + "&" + b).orElse("");
        resp.sendRedirect(req.getContextPath() + "/vnpay/return?" + query);
    }

    private Map<String, String> buildCancelParams(HttpServletRequest req) {
        Map<String, String> params = buildResultParams(req);
        params.put("vnp_ResponseCode", "24");
        params.put("vnp_TransactionStatus", "02");
        sign(params);
        return params;
    }

    private Map<String, String> buildResultParams(HttpServletRequest req) {
        Map<String, String> params = new LinkedHashMap<>();
        String amount = req.getParameter("amount");
        params.put("vnp_Amount", amount != null && !amount.isBlank()
                ? VnPayUtil.toVnpAmount(Long.parseLong(amount))
                : req.getParameter("vnp_Amount") == null ? "0" : req.getParameter("vnp_Amount"));
        String key = req.getParameter("idempotencyKey");
        params.put("vnp_TxnRef", key != null && !key.isBlank() ? key : req.getParameter("vnp_TxnRef"));
        return params;
    }

    private void sign(Map<String, String> params) {
        params.put("vnp_SecureHashType", "HmacSHA512");
        params.put("vnp_SecureHash",
                VnPayUtil.hmacSHA512(VnPayConfig.load().hashSecret(),
                        buildHashDataForVerify(params)));
    }

    private String buildHashDataForVerify(Map<String, String> params) {
        java.util.List<String> keys = new java.util.ArrayList<>(params.keySet());
        java.util.Collections.sort(keys);
        StringBuilder sb = new StringBuilder();
        try {
            boolean first = true;
            for (String k : keys) {
                String v = params.get(k);
                if ("vnp_SecureHash".equals(k) || "vnp_SecureHashType".equals(k)) continue;
                if (v == null || v.isEmpty()) continue;
                if (!first) sb.append('&');
                sb.append(k).append('=').append(java.net.URLEncoder.encode(v, "UTF-8"));
                first = false;
            }
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
        return sb.toString();
    }

    /** Verify HMAC do mock gateway ký — sanity check. */
    public static boolean verifyDemoSignature(long paymentId, long amount, String providedHmac) {
        return HmacUtil.verify(HMAC_SECRET, paymentId + "" + amount, providedHmac);
    }
}
