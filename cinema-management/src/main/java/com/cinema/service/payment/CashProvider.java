package com.cinema.service.payment;

import com.cinema.model.branch.Branch;
import com.cinema.model.payment.Payment;
/**
 * Provider tiền mặt tại quầy (Req 9.1): tạo payment PENDING, chỉ Branch Staff thuộc
 * chi nhánh của lịch chiếu xác nhận đã thu tiền mới chuyển SUCCESS (xác nhận xử lý
 * trong PaymentService.confirmCash).
 */
public class CashProvider implements PaymentProvider {

    @Override
    public String method() {
        return Payment.METHOD_CASH;
    }

    @Override
    public PaymentResult charge(PaymentSession session) {
        // Tiền mặt luôn chờ staff xác nhận thu tiền (Req 9.1)
        return PaymentResult.pending("CASH-COUNTER");
    }
}
