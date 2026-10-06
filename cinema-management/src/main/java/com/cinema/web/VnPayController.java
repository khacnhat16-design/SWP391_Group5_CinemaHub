package com.cinema.web;

import com.cinema.booking.Ticket;
import com.cinema.booking.TicketDAO;
import com.cinema.common.ErrorEnvelope;
import com.cinema.common.SerializationUtil;
import com.cinema.common.ServiceException;
import com.cinema.payment.Payment;
import com.cinema.payment.PaymentDAO;
import com.cinema.payment.PaymentService;
import com.cinema.payment.VnPayConfig;
import com.cinema.payment.VnPayProvider;
import com.cinema.payment.VnPayUtil;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * VNPay endpoint:
 * <ul>
 *   <li>{@code GET /vnpay/return} — VNPay redirect Customer về sau thanh toán.
 *       Dùng để <b>hiển thị</b> kết quả; trạng thái thực sự được cập nhật qua IPN.</li>
 *   <li>{@code GET /vnpay/ipn} — VNPay gọi server-to-server (Req 9.3 IPN); nguồn cập nhật
 *       trạng thái chính thức. Phải trả JSON theo format VNPay yêu cầu.</li>
 * </ul>
 *
 * <p>Đường dẫn URL này phải khớp với {@link VnPayConfig#returnUrl()} và {@link VnPayConfig#ipnUrl()}.
 */
public final class VnPayController extends HttpServlet {
    private static final Logger logger = Logger.getLogger(VnPayController.class.getName());

    private PaymentService paymentService;
    private VnPayProvider provider;
    private PaymentDAO paymentDao;
    private TicketDAO ticketDao;

    @Override
    public void init() throws ServletException {
        VnPayConfig config = VnPayConfig.load();
        this.provider = new VnPayProvider(config);
        this.paymentDao = new PaymentDAO();
        this.ticketDao = new TicketDAO();
        com.cinema.wallet.WalletService walletService =
                new com.cinema.wallet.WalletService(new com.cinema.wallet.WalletDAO());
        this.paymentService = new PaymentService(paymentDao, walletService,
                new com.cinema.payment.MockGatewayProvider("unused-for-vnpay", ""),
                "CinemaHub demo account",
                provider);
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        String path = request.getPathInfo() == null ? "" : request.getPathInfo();
        switch (path) {
            case "/return":
            case "/return/":
                handleReturn(request, response);
                break;
            case "/ipn":
            case "/ipn/":
                handleIpn(request, response);
                break;
            default:
                sendJsonError(response, 404, "NOT_FOUND", "Endpoint VNPay không tồn tại");
        }
    }

    /**
     * Return URL: verify checksum, cập nhật trạng thái (idempotent), redirect Customer
     * về trang kết quả. Nếu checksum sai → redirect về trang thất bại.
     *
     * <p>Phân biệt F&B vs vé qua:
     * <ul>
     *   <li>VNPay redirect với TxnRef dạng "FNB-{orderId}-..." → F&B</li>
     *   <li>Hoặc orderInfo bắt đầu bằng "FNB:"</li>
     *   <li>Mặc định: ticket payment</li>
     * </ul>
     */
    private void handleReturn(HttpServletRequest request, HttpServletResponse response) throws IOException {
        Map<String, String> params = VnPayUtil.extractVnpParams(request);
        logger.info("[VNPAY] Return received txnRef=" + params.get("vnp_TxnRef")
                + " responseCode=" + params.get("vnp_ResponseCode")
                + " amount=" + params.get("vnp_Amount"));

        String ctx = request.getContextPath();
        String txnRef = params.get("vnp_TxnRef");

        String successUrl;
        String failedUrl;
        if (txnRef != null && txnRef.startsWith("TOPUP-")) {
            successUrl = ctx + "/console?module=wallet&topup=success";
            failedUrl = ctx + "/console?module=wallet&topup=failed";
        } else {
            successUrl = ctx + "/booking?payment=success";
            failedUrl = ctx + "/booking?payment=failed";
        }

        try {
            paymentService.ensureChecksumValid(params, provider, "RETURN");
            paymentService.handleVnPayCallback(params, "RETURN");
            Payment payment = paymentDao.findByIdempotencyKey(txnRef)
                    .orElseThrow(() -> new ServiceException.NotFound("Không tìm thấy giao dịch"));

            if (Payment.METHOD_WALLET.equals(payment.method())
                    && txnRef != null && txnRef.startsWith("TOPUP-")) {
                response.sendRedirect(ctx + "/console?module=wallet&topup="
                        + (Payment.STATUS_SUCCESS.equals(payment.status()) ? "success" : "failed"));
                return;
            }
            if (Payment.STATUS_REVIEW_REQUIRED.equals(payment.status())) {
                response.sendRedirect(ctx + "/console?module=my-tickets&payment=review&paymentId="
                        + payment.id());
                return;
            }
            if (Payment.STATUS_SUCCESS.equals(payment.status())) {
                String ticketCode = resolveTicketCode(payment);
                StringBuilder url = new StringBuilder(successUrl);
                url.append("&paymentId=").append(payment.id());
                if (ticketCode != null) {
                    url.append("&ticketCode=").append(URLEncoder.encode(ticketCode, StandardCharsets.UTF_8));
                }
                response.sendRedirect(url.toString());
            } else {
                String reason = URLEncoder.encode(
                        messageFor(params.get("vnp_ResponseCode")), StandardCharsets.UTF_8);
                response.sendRedirect(failedUrl + "&reason=" + reason
                        + "&paymentId=" + payment.id());
            }
        } catch (Exception e) {
            String reason = URLEncoder.encode(
                    e instanceof ServiceException ? e.getMessage() : "Lỗi hệ thống",
                    StandardCharsets.UTF_8);
            response.sendRedirect(failedUrl + "&reason=" + reason);
        }
    }

    /**
     * IPN: server-to-server từ VNPay. Verify checksum → cập nhật DB → trả về response
     * đúng định dạng VNPay yêu cầu:
     * {@code {"RspCode":"00","Message":"Confirm Success"}} khi thành công.
     */
    private void handleIpn(HttpServletRequest request, HttpServletResponse response) throws IOException {
        Map<String, String> params = VnPayUtil.extractVnpParams(request);
        logger.info("[VNPAY] IPN received txnRef=" + params.get("vnp_TxnRef")
                + " responseCode=" + params.get("vnp_ResponseCode"));
        response.setContentType("application/json;charset=UTF-8");
        try {
            paymentService.ensureChecksumValid(params, provider, "IPN");
            paymentService.handleVnPayCallback(params, "IPN");
            response.getWriter().write("{\"RspCode\":\"00\",\"Message\":\"Confirm Success\"}");
        } catch (ServiceException.Forbidden e) {
            logger.warning("[VNPAY] IPN invalid checksum txnRef=" + params.get("vnp_TxnRef"));
            response.getWriter().write("{\"RspCode\":\"97\",\"Message\":\"Invalid Checksum\"}");
        } catch (ServiceException.NotFound e) {
            response.getWriter().write("{\"RspCode\":\"01\",\"Message\":\"Order not found\"}");
        } catch (ServiceException.Validation e) {
            response.getWriter().write("{\"RspCode\":\"99\",\"Message\":\"Input invalid\"}");
        } catch (Exception e) {
            logger.log(Level.WARNING, "[VNPAY] IPN processing error", e);
            response.getWriter().write("{\"RspCode\":\"99\",\"Message\":\"Unknown error\"}");
        }
    }

    /** Trả về ticketCode nếu payment đã gắn với một vé, ngược lại null. */
    private String resolveTicketCode(Payment payment) {
        if (payment.ticketId() == null) return null;
        try {
            return ticketDao.findById(payment.ticketId())
                    .map(Ticket::ticketCode)
                    .orElse(null);
        } catch (Exception e) {
            logger.warning("[VNPAY] Không tải được ticketCode cho ticketId=" + payment.ticketId());
            return null;
        }
    }

    /** Ánh xạ responseCode phổ biến của VNPay sang message thân thiện. */
    private String messageFor(String code) {
        if (code == null) return "Không có phản hồi từ VNPay";
        return switch (code) {
            case "00" -> "Giao dịch thành công";
            case "07" -> "Trừ tiền thành công, giao dịch bị nghi ngờ (liên hệ VNPay)";
            case "09" -> "Thẻ chưa đăng ký dịch vụ InternetBanking";
            case "10" -> "Xác thực thông tin thẻ không đúng quá 3 lần";
            case "11" -> "Giao dịch đã hết thời gian chờ thanh toán. Vui lòng tạo giao dịch mới.";
            case "12" -> "Thẻ bị khóa";
            case "13" -> "Mật khẩu OTP không đúng";
            case "24" -> "Khách hàng hủy giao dịch";
            case "51" -> "Tài khoản không đủ số dư";
            case "65" -> "Tài khoản đã vượt quá hạn mức giao dịch trong ngày";
            case "75" -> "Ngân hàng đang bảo trì";
            case "99" -> "Lỗi không xác định";
            default -> "Thanh toán thất bại (mã " + code + ")";
        };
    }

    private void sendJsonError(HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(SerializationUtil.toJson(new ErrorEnvelope(code, message)));
    }
}
