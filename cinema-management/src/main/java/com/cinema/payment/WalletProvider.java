package com.cinema.payment;

/**
 * Provider ví khách hàng (Req 18.3): trừ ví guarded trong cùng transaction với xác
 * nhận đơn hàng — việc trừ tiền thật do WalletService.spend thực hiện, provider chỉ
 * báo trạng thái SUCCESS tức thời khi được gọi trong transaction đã kiểm số dư.
 */
public class WalletProvider implements PaymentProvider {

    @Override
    public String method() {
        return Payment.METHOD_WALLET;
    }

    @Override
    public PaymentResult charge(PaymentSession session) {
        // Wallet spend được PaymentService thực hiện guarded trong cùng transaction;
        // nếu số dư không đủ WalletService ném lỗi trước khi tới đây.
        return PaymentResult.success("WALLET-" + session.paymentId());
    }
}
