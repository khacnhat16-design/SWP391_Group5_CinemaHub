package com.cinema.payment;

/**
 * Provider chuyển khoản ngân hàng (Req 9.5, 9.6): tạo payment PENDING hiển thị thông
 * tin chuyển khoản; Branch Staff đối soát thủ công rồi xác nhận (approve → SUCCESS,
 * reject → FAILED, vé bị hủy và ghế giải phóng — xử lý trong PaymentService).
 */
public class BankTransferProvider implements PaymentProvider {
    /** Thông tin tài khoản nhận chuyển khoản (demo). */
    private final String bankAccountInfo;

    public BankTransferProvider(String bankAccountInfo) {
        this.bankAccountInfo = bankAccountInfo;
    }

    @Override
    public String method() {
        return Payment.METHOD_BANK_TRANSFER;
    }

    @Override
    public PaymentResult charge(PaymentSession session) {
        // Req 9.5 — pending chờ đối soát thủ công; nội dung chuyển khoản = idempotency key
        return PaymentResult.pending("BANK-" + session.paymentId() + " | " + bankAccountInfo
                + " | ND: " + session.idempotencyKey());
    }
}
