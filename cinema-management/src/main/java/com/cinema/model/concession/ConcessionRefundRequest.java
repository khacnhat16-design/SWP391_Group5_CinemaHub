package com.cinema.model.concession;

import java.time.LocalDateTime;

public record ConcessionRefundRequest(
        long id,
        long orderId,
        long userId,
        long branchId,
        String orderCode,
        long refundAmount,
        int refundPoints,
        String bankName,
        String bankAccountName,
        String bankAccountNumber,
        String reason,
        String status,
        String managerNote,
        LocalDateTime createdAt,
        LocalDateTime decidedAt) {
}
