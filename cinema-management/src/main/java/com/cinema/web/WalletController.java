package com.cinema.web;

import com.cinema.auth.AccessScope;
import com.cinema.auth.Role;
import com.cinema.common.ErrorEnvelope;
import com.cinema.common.SerializationUtil;
import com.cinema.common.ServiceException;
import com.cinema.filter.AuthFilter;
import com.cinema.payment.Payment;
import com.cinema.payment.PaymentDAO;
import com.cinema.payment.VnPayConfig;
import com.cinema.payment.VnPayUtil;
import com.cinema.wallet.Wallet;
import com.cinema.wallet.WalletService;
import com.cinema.wallet.WalletTx;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Customer wallet API for balance, transaction history, and VNPay top-ups. */
public final class WalletController extends HttpServlet {
    private static final Logger logger = Logger.getLogger(WalletController.class.getName());

    private WalletService walletService;
    private PaymentDAO paymentDao;
    private VnPayConfig vnPayConfig;

    @Override
    public void init() throws ServletException {
        this.walletService = new WalletService(new com.cinema.wallet.WalletDAO());
        this.paymentDao = new PaymentDAO();
        this.vnPayConfig = VnPayConfig.load();
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        String path = request.getPathInfo();
        if (path != null && !path.isBlank() && !"/".equals(path)) {
            sendError(response, HttpServletResponse.SC_NOT_FOUND, "NOT_FOUND",
                    "Endpoint ví không tồn tại");
            return;
        }
        try {
            Long userId = requireCustomer(request, response);
            if (userId == null) return;

            Wallet wallet = walletService.getWallet(userId);
            Map<String, Object> walletData = new LinkedHashMap<>();
            walletData.put("balance", wallet.balance());
            walletData.put("version", wallet.version());

            List<Map<String, Object>> transactions = new ArrayList<>();
            for (WalletTx tx : walletService.history(userId)) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", tx.id());
                row.put("type", tx.type());
                row.put("amount", tx.amount());
                row.put("balanceAfter", tx.balanceAfter());
                row.put("refType", tx.refType());
                row.put("refId", tx.refId());
                row.put("createdAt", tx.createdAt());
                row.put("description", describe(tx));
                transactions.add(row);
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("wallet", walletData);
            payload.put("transactions", transactions);
            sendOk(response, payload);
        } catch (Exception e) {
            handleException(response, e);
        }
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        String path = request.getPathInfo() == null ? "" : request.getPathInfo();
        if (!"/top-up".equals(path)) {
            sendError(response, HttpServletResponse.SC_NOT_FOUND, "NOT_FOUND",
                    "Endpoint ví không tồn tại");
            return;
        }
        try {
            Long userId = requireCustomer(request, response);
            if (userId == null) return;

            String amountValue = request.getParameter("amount");
            if (amountValue == null || amountValue.isBlank()) {
                sendError(response, HttpServletResponse.SC_BAD_REQUEST, "BAD_REQUEST",
                        "Thiếu số tiền nạp");
                return;
            }
            long amount;
            try {
                amount = Long.parseLong(amountValue);
            } catch (NumberFormatException e) {
                sendError(response, HttpServletResponse.SC_BAD_REQUEST, "BAD_REQUEST",
                        "Số tiền nạp không hợp lệ");
                return;
            }

            String method = request.getParameter("method");
            if (method != null && !method.isBlank() && !"VNPAY".equalsIgnoreCase(method.trim())) {
                sendError(response, HttpServletResponse.SC_BAD_REQUEST, "UNSUPPORTED_PAYMENT_METHOD",
                        "Nạp ví hiện chỉ hỗ trợ VNPay");
                return;
            }

            String idempotencyKey = walletService.requestTopUp(userId, amount);
            Payment payment = paymentDao.findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> new ServiceException.NotFound(
                            "Không tìm thấy giao dịch nạp ví vừa tạo"));

            Map<String, String> params = new LinkedHashMap<>();
            params.put("vnp_Version", "2.1.0");
            params.put("vnp_Command", "pay");
            params.put("vnp_TmnCode", vnPayConfig.tmnCode());
            params.put("vnp_Amount", VnPayUtil.toVnpAmount(payment.amount()));
            params.put("vnp_CurrCode", "VND");
            params.put("vnp_TxnRef", payment.idempotencyKey());
            params.put("vnp_OrderInfo", "Nap vi CinemaHub user " + userId);
            params.put("vnp_OrderType", "other");
            params.put("vnp_Locale", "vn");
            params.put("vnp_ReturnUrl", buildReturnUrl(request));
            params.put("vnp_IpAddr", request.getRemoteAddr());
            params.putAll(VnPayUtil.paymentWindow(vnPayConfig.expireMinutes()));

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("redirectUrl", VnPayUtil.buildRedirectUrl(
                    vnPayConfig.payUrl(), params, vnPayConfig.hashSecret()));
            sendOk(response, result);
        } catch (Exception e) {
            handleException(response, e);
        }
    }

    private Long requireCustomer(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        AccessScope scope = (AccessScope) request.getAttribute(AuthFilter.SCOPE_ATTRIBUTE);
        if (scope == null || scope.isGuest() || scope.role() != Role.CUSTOMER) {
            sendError(response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN",
                    "Chỉ khách hàng đã đăng nhập mới được sử dụng ví");
            return null;
        }
        return scope.userId();
    }

    private String buildReturnUrl(HttpServletRequest request) {
        String scheme = request.getScheme();
        int port = request.getServerPort();
        boolean standardPort = ("http".equalsIgnoreCase(scheme) && port == 80)
                || ("https".equalsIgnoreCase(scheme) && port == 443);
        return scheme + "://" + request.getServerName()
                + (standardPort ? "" : ":" + port)
                + request.getContextPath() + "/vnpay/return";
    }

    private String describe(WalletTx tx) {
        String type = tx.type() == null ? "Giao dịch" : switch (tx.type()) {
            case WalletTx.TYPE_TOPUP -> "Nạp tiền";
            case WalletTx.TYPE_SPEND -> "Thanh toán";
            case WalletTx.TYPE_REFUND -> "Hoàn tiền";
            default -> tx.type();
        };
        if (tx.refType() == null || tx.refType().isBlank()) return type;
        return tx.refId() == null ? type + " · " + tx.refType()
                : type + " · " + tx.refType() + " #" + tx.refId();
    }

    private void sendOk(HttpServletResponse response, Object data) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(SerializationUtil.toJson(data));
    }

    private void sendError(HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(SerializationUtil.toJson(new ErrorEnvelope(code, message)));
    }

    private void handleException(HttpServletResponse response, Exception e) throws IOException {
        if (e instanceof ServiceException service) {
            sendError(response, service.httpStatus(), service.code(), service.getMessage());
        } else {
            logger.log(Level.SEVERE, "Wallet request failed", e);
            sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                    "Không thể xử lý yêu cầu ví lúc này");
        }
    }
}
