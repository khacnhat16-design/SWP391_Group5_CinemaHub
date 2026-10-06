package com.cinema.payment;

import com.cinema.util.HmacUtil;

/**
 * Provider cổng thanh toán online mock (Req 9.2-9.4): tạo phiên + redirect URL giả lập;
 * kết quả về qua callback có HMAC-SHA256 (design.md Security: HMAC(secret, paymentId+amount)).
 */
public class MockGatewayProvider implements PaymentProvider {
    /** Secret demo cho HMAC callback — production đọc từ cấu hình bảo mật. */
    private final String hmacSecret;
    private final String gatewayBaseUrl;

    public MockGatewayProvider(String hmacSecret, String gatewayBaseUrl) {
        this.hmacSecret = hmacSecret;
        this.gatewayBaseUrl = gatewayBaseUrl;
    }

    @Override
    public String method() {
        return Payment.METHOD_MOCK_GATEWAY;
    }

    @Override
    public PaymentResult charge(PaymentSession session) {
        // Req 9.2 — phiên mock chờ callback; redirect tới trang thanh toán giả lập
        String payload = session.paymentId() + "" + session.amount();
        String hmac = HmacUtil.sign(hmacSecret, payload);
        String redirectUrl = gatewayBaseUrl + "/payment/mock-gateway?paymentId=" + session.paymentId()
                + "&amount=" + session.amount()
                + "&idempotencyKey=" + session.idempotencyKey()
                + "&hmac=" + hmac;
        return PaymentResult.pending("MOCK-GW-" + session.paymentId(), redirectUrl);
    }

    /** Verify HMAC của callback trước khi xử lý kết quả (Req 9.3). */
    public boolean verifyCallback(long paymentId, long amount, String providedHmac) {
        return HmacUtil.verify(hmacSecret, paymentId + "" + amount, providedHmac);
    }
}
