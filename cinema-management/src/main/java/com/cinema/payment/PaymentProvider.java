package com.cinema.payment;

/**
 * Strategy provider cho 4 phương thức thanh toán (design.md PaymentService).
 * Mỗi provider quyết định trạng thái ban đầu của payment session và cách xác nhận.
 */
public interface PaymentProvider {

    /** Method code mà provider xử lý (khớp CK_payment_method). */
    String method();

    /**
     * Xử lý charge cho một phiên thanh toán.
     *
     * @return kết quả: trạng thái payment (PENDING chờ xác nhận / SUCCESS ngay / FAILED)
     *         kèm tham chiếu provider và redirect URL (mock gateway)
     */
    PaymentResult charge(PaymentSession session);

    /** Phiên thanh toán được tạo bởi PaymentService.createSession. */
    record PaymentSession(long paymentId, Long ticketId, String method, long amount,
                          String idempotencyKey, Long userId) { }

    /** Kết quả charge của provider. */
    record PaymentResult(String status, String providerReference, String redirectUrl) {
        public static PaymentResult pending(String reference) {
            return new PaymentResult(Payment.STATUS_PENDING, reference, null);
        }
        public static PaymentResult pending(String reference, String redirectUrl) {
            return new PaymentResult(Payment.STATUS_PENDING, reference, redirectUrl);
        }
        public static PaymentResult success(String reference) {
            return new PaymentResult(Payment.STATUS_SUCCESS, reference, null);
        }
        public static PaymentResult failed(String reference) {
            return new PaymentResult(Payment.STATUS_FAILED, reference, null);
        }
    }
}
