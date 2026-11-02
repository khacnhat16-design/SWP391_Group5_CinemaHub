package com.cinema.service.concession;

import com.cinema.common.ServiceException;
import com.cinema.dao.concession.ConcessionOrderDAO;
import com.cinema.dao.concession.ConcessionRefundRequestDAO;
import com.cinema.dao.payment.PaymentDAO;
import com.cinema.infrastructure.database.DBContext;
import com.cinema.model.concession.ConcessionOrder;
import com.cinema.model.concession.ConcessionRefundRequest;
import com.cinema.service.audit.AuditService;
import com.cinema.service.notification.NotificationService;
import com.cinema.util.TransactionTemplate;

import java.sql.Connection;
import java.util.List;
import java.util.Set;

public class ConcessionRefundService {
    private final ConcessionRefundRequestDAO requestDao;
    private final ConcessionOrderDAO orderDao;
    private final PaymentDAO paymentDao;
    private final ConcessionService concessionService;
    private final NotificationService notificationService;
    private final AuditService audit = new AuditService();
    private final TransactionTemplate tx = new TransactionTemplate();

    public ConcessionRefundService(ConcessionRefundRequestDAO requestDao,
                                   ConcessionOrderDAO orderDao,
                                   PaymentDAO paymentDao,
                                   NotificationService notificationService,
                                   ConcessionService concessionService) {
        this.requestDao = requestDao;
        this.orderDao = orderDao;
        this.paymentDao = paymentDao;
        this.concessionService = concessionService;
        this.notificationService = notificationService;
    }

    public ConcessionRefundRequest openRequest(long orderId, long userId, String bankName,
                                               String accountName, String accountNumber,
                                               String reason) throws Exception {
        requireText(bankName, "Vui lòng nhập tên ngân hàng");
        requireText(accountName, "Vui lòng nhập tên chủ tài khoản");
        requireText(accountNumber, "Vui lòng nhập số tài khoản");
        if (bankName.length() > 120 || accountName.length() > 120 || accountNumber.length() > 40) {
            throw new ServiceException.Validation("Thông tin ngân hàng vượt quá độ dài cho phép");
        }
        if (reason != null && reason.length() > 500) {
            throw new ServiceException.Validation("Lý do hoàn tiền tối đa 500 ký tự");
        }

        ConcessionRefundRequest request = tx.execute(conn -> {
            ConcessionOrder order = orderDao.findByIdLocked(conn, orderId)
                    .orElseThrow(() -> new ServiceException.NotFound("Đơn F&B không tồn tại"));
            if (order.userId() == null || order.userId() != userId) {
                throw new ServiceException.Forbidden("Chỉ chủ đơn mới có thể yêu cầu hoàn tiền");
            }
            if (!ConcessionOrder.STATUS_READY_FOR_PICKUP.equals(order.status())
                    && !ConcessionOrder.STATUS_FULFILLED.equals(order.status())) {
                throw new ServiceException.BusinessRule("ORDER_NOT_REFUNDABLE",
                        "Chỉ đơn đã thanh toán mới có thể yêu cầu hoàn tiền");
            }
            if (requestDao.hasPendingForOrder(conn, orderId)) {
                throw new ServiceException.Conflict("Đơn hàng đã có yêu cầu hoàn tiền đang chờ xử lý");
            }
            ConcessionRefundRequest created = requestDao.insert(conn, orderId, userId,
                    order.branchId(), bankName.trim(), accountName.trim(),
                    accountNumber.trim(), reason == null ? null : reason.trim(),
                    order.amountAfterPointsDiscount(), order.pointsUsed());
            if (notificationService != null) {
                notificationService.notifyRefundApprovers(conn, order.branchId(),
                        "Yêu cầu hoàn tiền F&B mới",
                        "Khách hàng yêu cầu hoàn 100% đơn " + order.orderCode()
                                + " (" + order.amountAfterPointsDiscount() + "đ).");
            }
            return created;
        });
        return request;
    }

    public List<ConcessionRefundRequest> listMine(long userId) throws Exception {
        try (Connection conn = DBContext.getConnection()) {
            return requestDao.findMine(conn, userId);
        }
    }

    public List<ConcessionRefundRequest> listForConsole(List<Long> branchIds, String status)
            throws Exception {
        if (status != null && !status.isBlank()
                && !Set.of("PENDING", "APPROVED", "REJECTED").contains(status)) {
            throw new ServiceException.Validation("Trạng thái yêu cầu hoàn tiền không hợp lệ");
        }
        try (Connection conn = DBContext.getConnection()) {
            return requestDao.findForConsole(conn, branchIds, status);
        }
    }

    public ConcessionRefundRequest decide(long requestId, long managerId,
                                         Set<Long> managerBranchIds, boolean approve,
                                         String managerNote) throws Exception {
        if (managerNote != null && managerNote.length() > 500) {
            throw new ServiceException.Validation("Ghi chú tối đa 500 ký tự");
        }
        ConcessionRefundRequest request = tx.execute(conn -> {
            ConcessionRefundRequest observed = requestDao.findById(conn, requestId);
            if (observed == null) {
                throw new ServiceException.NotFound("Yêu cầu hoàn tiền F&B không tồn tại");
            }
            ConcessionOrder order = orderDao.findByIdLocked(conn, observed.orderId())
                    .orElseThrow(() -> new ServiceException.NotFound("Đơn F&B không tồn tại"));
            ConcessionRefundRequest locked = requestDao.lockById(conn, requestId);
            if (locked == null) {
                throw new ServiceException.NotFound("Yêu cầu hoàn tiền F&B không tồn tại");
            }
            if (!"PENDING".equals(locked.status())) {
                throw new ServiceException.Conflict("Yêu cầu hoàn tiền đã được xử lý");
            }
            if (managerBranchIds != null && !managerBranchIds.contains(locked.branchId())) {
                throw new ServiceException.Forbidden("Bạn không thể xử lý yêu cầu của chi nhánh khác");
            }
            if (locked.orderId() != order.id()) {
                throw new ServiceException.Conflict("Đơn liên kết với yêu cầu hoàn tiền đã thay đổi");
            }
            String nextStatus = approve ? "APPROVED" : "REJECTED";
            if (approve) {
                if (!ConcessionOrder.STATUS_READY_FOR_PICKUP.equals(order.status())
                        && !ConcessionOrder.STATUS_FULFILLED.equals(order.status())) {
                    throw new ServiceException.Conflict("Trạng thái đơn không còn có thể hoàn tiền");
                }
                concessionService.restoreOrderInventory(
                        conn, order.branchId(), orderDao.findLines(conn, order.id()), managerId, order.orderCode());
                if (!orderDao.updateStatusGuarded(conn, order.id(), ConcessionOrder.STATUS_CANCELLED,
                        order.status(), "Refund approved (request #" + requestId + ")",
                        order.fulfilledAt())) {
                    throw new ServiceException.Conflict("Đơn vừa được cập nhật bởi một yêu cầu khác");
                }
                paymentDao.markConcessionPaymentsRefunded(conn, order.id());
            }
            if (!requestDao.updateDecision(conn, requestId, nextStatus, managerId,
                    managerNote == null ? null : managerNote.trim())) {
                throw new ServiceException.Conflict("Yêu cầu vừa được xử lý bởi một quản lý khác");
            }
            audit.record(conn, managerId,
                    approve ? "APPROVE_CONCESSION_REFUND" : "REJECT_CONCESSION_REFUND",
                    "CONCESSION_REFUND_REQUEST", requestId,
                    "{\"status\":\"PENDING\"}",
                    "{\"status\":\"" + nextStatus + "\",\"orderId\":" + locked.orderId()
                            + ",\"amount\":" + locked.refundAmount() + "}",
                    AuditService.SUCCESS);
            return requestDao.findById(conn, requestId);
        });
        return request;
    }

    private void requireText(String value, String message) {
        if (value == null || value.isBlank()) throw new ServiceException.Validation(message);
    }
}
