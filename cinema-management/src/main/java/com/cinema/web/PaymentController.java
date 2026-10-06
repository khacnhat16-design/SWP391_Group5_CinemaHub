package com.cinema.web;

import com.cinema.booking.Ticket;
import com.cinema.booking.TicketDAO;
import com.cinema.common.ErrorEnvelope;
import com.cinema.common.SerializationUtil;
import com.cinema.common.ServiceException;
import com.cinema.notification.NotificationDAO;
import com.cinema.notification.NotificationService;
import com.cinema.payment.MockGatewayProvider;
import com.cinema.payment.Payment;
import com.cinema.payment.PaymentDAO;
import com.cinema.payment.PaymentService;
import com.cinema.wallet.WalletService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.sql.Connection;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Mock payment sandbox page and signed callback endpoint. */
public final class PaymentController extends HttpServlet {
    private static final Logger logger = Logger.getLogger(PaymentController.class.getName());
    private static final String HMAC_SECRET = System.getProperty(
            "cinema.payment.hmacSecret", "cinema-demo-secret");

    private PaymentService paymentService;
    private WalletService walletService;
    private PaymentDAO paymentDao;
    private TicketDAO ticketDao;
    private NotificationService notificationService;

    @Override
    public void init() throws ServletException {
        walletService = new WalletService(new com.cinema.wallet.WalletDAO());
        paymentDao = new PaymentDAO();
        ticketDao = new TicketDAO();
        notificationService = new NotificationService(new NotificationDAO());
        paymentService = new PaymentService(paymentDao, walletService,
                new MockGatewayProvider(HMAC_SECRET, ""), "CinemaHub demo account");
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        if (!"/mock-gateway".equals(request.getPathInfo())) {
            sendError(response, 400, "BAD_REQUEST", "Payment endpoint không hợp lệ");
            return;
        }
        String ctx = request.getContextPath();
        String paymentId = esc(request.getParameter("paymentId"));
        String amount = esc(request.getParameter("amount"));
        String key = esc(request.getParameter("idempotencyKey"));
        String hmac = esc(request.getParameter("hmac"));
        response.setContentType("text/html;charset=UTF-8");
        response.getWriter().printf("""
            <!doctype html><html lang="vi"><head><meta charset="UTF-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <title>VNPay Sandbox - CinemaHub</title>
            <link rel="stylesheet" href="%s/assets/css/cinema.css"></head><body>
            <main class="container section"><div class="auth-card">
            <span class="eyebrow">PAYMENT SANDBOX</span><h1>Xác nhận thanh toán thử nghiệm</h1>
            <p class="muted">Đây là màn hình mô phỏng cổng thanh toán Sandbox. Không phát sinh giao dịch thật.</p>
            <div class="summary-line"><span>Mã giao dịch</span><strong>%s</strong></div>
            <div class="summary-line total"><span>Số tiền</span><strong>%sđ</strong></div>
            <form method="post" action="%s/payment/mock-gateway/callback">
              <input type="hidden" name="paymentId" value="%s">
              <input type="hidden" name="amount" value="%s">
              <input type="hidden" name="idempotencyKey" value="%s">
              <input type="hidden" name="hmac" value="%s">
              <label class="field"><span>Nhap SUCCESS de thanh toan thanh cong</span>
                <input name="sandboxCode" autocomplete="one-time-code" placeholder="SUCCESS"></label>
              <button class="btn full" name="result" value="success" type="submit">Thanh toán thành công</button>
              <button class="btn ghost full" name="result" value="failed" type="submit">Hủy / thất bại</button>
            </form></div></main></body></html>
            """, ctx, paymentId, amount, ctx, paymentId, amount, key, hmac);
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        if (!"/mock-gateway/callback".equals(request.getPathInfo())) {
            sendError(response, 400, "BAD_REQUEST", "Callback endpoint không hợp lệ");
            return;
        }
        try {
            boolean requestedSuccess = "success".equalsIgnoreCase(request.getParameter("result"));
            boolean success = requestedSuccess && isSandboxSuccessCode(request.getParameter("sandboxCode"));
            String hmac = request.getParameter("hmac");
            String key = request.getParameter("idempotencyKey");
            String redirect = request.getContextPath() + "/console?module=wallet";
            if (key != null && key.startsWith("TOPUP-")) {
                walletService.handleTopUpCallback(key, hmac, success,
                        new MockGatewayProvider(HMAC_SECRET, ""));
                redirect += success ? "&topup=success" : "&topup=failed";
            } else {
                long paymentId = Long.parseLong(require(request, "paymentId"));
                paymentService.handleMockCallback(paymentId, hmac, success,
                        new MockGatewayProvider(HMAC_SECRET, ""));
                Payment payment = paymentDao.findById(paymentId).orElse(null);
                if (payment != null && payment.ticketId() != null) {
                    Ticket ticket = ticketDao.findById(payment.ticketId()).orElse(null);
                    String paymentResult = Payment.STATUS_REVIEW_REQUIRED.equals(payment.status())
                            ? "review"
                            : Payment.STATUS_SUCCESS.equals(payment.status()) ? "success" : "failed";
                    redirect = request.getContextPath() + "/console?module=my-tickets"
                            + "&payment=" + paymentResult;
                    if ("success".equals(paymentResult) && ticket != null
                            && ticket.ticketCode() != null) {
                        TicketDAO.ConfirmationDetails details = null;
                        try (Connection conn = dal.DBContext.getConnection()) {
                            details = ticketDao.findConfirmationDetails(conn, ticket.id()).orElse(null);
                        } catch (Exception notificationDetailsFailure) {
                            logger.log(Level.WARNING, "Unable to load confirmation details for ticket "
                                    + ticket.id(), notificationDetailsFailure);
                        }
                        notificationService.notifyTicketConfirmed(null, ticket.userId(),
                                ticket.ticketCode(), details, payment.amount());
                        redirect += "&ticketCode=" + java.net.URLEncoder.encode(
                                ticket.ticketCode(), java.nio.charset.StandardCharsets.UTF_8);
                    } else if ("review".equals(paymentResult)) {
                        redirect += "&paymentId=" + payment.id();
                    }
                }
            }
            response.sendRedirect(redirect);
        } catch (Exception e) {
            response.setContentType("text/html;charset=UTF-8");
            response.getWriter().printf("""
                <!doctype html><html lang="vi"><head><meta charset="UTF-8">
                <link rel="stylesheet" href="%s/assets/css/cinema.css"></head><body>
                <main class="container section"><div class="flash flash-err">%s</div>
                <a class="btn" href="%s/">Quay lại CinemaHub</a></main></body></html>
                """, request.getContextPath(), esc(message(e)), request.getContextPath());
        }
    }

    private String require(HttpServletRequest request, String name) {
        String value = request.getParameter(name);
        if (value == null || value.isBlank()) {
            throw new ServiceException.Validation("Thiếu tham số: " + name);
        }
        return value.trim();
    }

    private boolean isSandboxSuccessCode(String code) {
        if (code == null) return false;
        String normalized = code.trim();
        return "SUCCESS".equalsIgnoreCase(normalized)
                || "THANHCONG".equalsIgnoreCase(normalized)
                || "00".equals(normalized);
    }

    private void sendError(HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(SerializationUtil.toJson(new ErrorEnvelope(code, message)));
    }

    private String message(Exception e) {
        return e instanceof ServiceException ? e.getMessage() : "Lỗi hệ thống";
    }

    private String esc(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }
}
