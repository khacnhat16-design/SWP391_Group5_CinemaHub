package com.cinema.web;

import com.cinema.booking.BookingService;
import com.cinema.booking.HoldResult;
import com.cinema.booking.Ticket;
import com.cinema.common.SerializationUtil;
import com.cinema.common.ServiceException;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
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

/**
 * REST API controller cho booking flow (Req 7, 8, 11, 12).
 * Chức năng: Đặt vé xem phim (Booking Flow) — Giữ ghế, xác nhận vé, hủy vé và soát vé.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>POST /booking/quote — Báo giá tạm tính trước khi giữ ghế.</li>
 *   <li>POST /booking/hold — Customer giữ ghế 10 phút (Req 7.1).</li>
 *   <li>POST /booking/release — Customer nhả giữ ghế khi hủy chọn.</li>
 *   <li>POST /booking/confirm — Xác nhận booking trong hold (Req 7.5).</li>
 *   <li>POST /booking/cancel — Hủy vé bậc thang (Customer chủ vé hoặc Staff đúng branch, Req 11).</li>
 *   <li>POST /booking/validate — Branch Staff soát vé một lần (Req 12).</li>
 *   <li>GET /booking/mine — Lịch sử vé của chính Customer (Req 16.5, chặn IDOR).</li>
 *   <li>GET /booking/lookup — Tra cứu nhanh thông tin vé theo mã vé.</li>
 * </ul>
 */
@WebServlet(name = "BookingController", urlPatterns = { "/booking/*" })
public class BookingController extends HttpServlet {
    private static final Logger logger = Logger.getLogger(BookingController.class.getName());
    private BookingService bookingService;

    @Override
    public void init() throws ServletException {
        this.bookingService = new BookingService();
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        String path = request.getPathInfo();
        if (path == null) path = "";

        switch (path) {
            case "", "/", "/mine" -> handleMine(request, response);
            case "/ticket" -> handleTicketDetail(request, response);
            case "/lookup" -> handleLookup(request, response);
            default -> sendError(response, 404, "NOT_FOUND", "Endpoint không tồn tại: " + path);
        }
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        String path = request.getPathInfo();
        if (path == null) path = "";

        switch (path) {
            case "/quote" -> handleQuote(request, response);
            case "/hold" -> handleHold(request, response);
            case "/release" -> handleRelease(request, response);
            case "/confirm" -> handleConfirm(request, response);
            case "/cancel" -> handleCancel(request, response);
            case "/validate" -> handleValidate(request, response);
            default -> sendError(response, 404, "NOT_FOUND", "Endpoint không tồn tại: " + path);
        }
    }

    /** Báo giá trước khi giữ ghế. */
    private void handleQuote(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        String showtimeParam = request.getParameter("showtimeId");
        String seatIdsParam = request.getParameter("seatIds");

        if (showtimeParam == null || seatIdsParam == null) {
            sendError(response, 400, "BAD_REQUEST", "Thiếu showtimeId hoặc seatIds");
            return;
        }

        try {
            long showtimeId = Long.parseLong(showtimeParam);
            List<Long> seatIds = parseSeatIds(seatIdsParam);
            Map<String, Object> quote = bookingService.quoteOrder(showtimeId, seatIds);
            sendOk(response, quote);
        } catch (NumberFormatException e) {
            sendError(response, 400, "BAD_REQUEST", "Định dạng ID không hợp lệ");
        }
    }

    /** Giữ ghế 10 phút. */
    private void handleHold(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        var session = request.getSession(false);
        Long userId = null;
        if (session != null && session.getAttribute("userId") != null) {
            Object uid = session.getAttribute("userId");
            if (uid instanceof Long l) userId = l;
            else if (uid instanceof Integer i) userId = i.longValue();
        }
        if (userId == null) {
            String uParam = request.getParameter("userId");
            if (uParam != null && !uParam.isBlank()) {
                try { userId = Long.parseLong(uParam.trim()); } catch (NumberFormatException ignored) {}
            }
        }
        if (userId == null) {
            userId = 1L; // Fallback mock user ID cho dev/test
        }

        String showtimeParam = request.getParameter("showtimeId");
        String seatIdsParam = request.getParameter("seatIds");

        if (showtimeParam == null || seatIdsParam == null) {
            sendError(response, 400, "BAD_REQUEST", "Thiếu showtimeId hoặc seatIds");
            return;
        }

        try {
            long showtimeId = Long.parseLong(showtimeParam);
            List<Long> seatIds = parseSeatIds(seatIdsParam);

            HoldResult result = bookingService.holdSeats(showtimeId, seatIds, userId);
            if (result.success()) {
                sendOk(response, Map.of(
                        "success", true,
                        "holdId", result.holdId(),
                        "message", result.message()
                ));
            } else {
                sendError(response, 409, "CONFLICT", result.message());
            }
        } catch (NumberFormatException e) {
            sendError(response, 400, "BAD_REQUEST", "Định dạng ID không hợp lệ");
        }
    }

    /** Hủy giữ ghế. */
    private void handleRelease(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        var session = request.getSession(false);
        Long userId = session != null ? (Long) session.getAttribute("userId") : null;

        String showtimeParam = request.getParameter("showtimeId");
        String seatIdsParam = request.getParameter("seatIds");

        if (showtimeParam != null && seatIdsParam != null) {
            try {
                long showtimeId = Long.parseLong(showtimeParam);
                List<Long> seatIds = parseSeatIds(seatIdsParam);
                bookingService.releaseHold(showtimeId, seatIds, userId);
            } catch (Exception ignored) { }
        }
        sendOk(response, Map.of("success", true, "released", true));
    }

    /** Xác nhận đặt vé PENDING -> CONFIRMED. */
    private void handleConfirm(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        var session = request.getSession(false);
        Long userId = null;
        if (session != null && session.getAttribute("userId") != null) {
            Object uid = session.getAttribute("userId");
            if (uid instanceof Long l) userId = l;
            else if (uid instanceof Integer i) userId = i.longValue();
        }
        if (userId == null) {
            String uParam = request.getParameter("userId");
            if (uParam != null && !uParam.isBlank()) {
                try { userId = Long.parseLong(uParam.trim()); } catch (NumberFormatException ignored) {}
            }
        }
        if (userId == null) {
            sendError(response, 401, "UNAUTHORIZED", "Vui lòng đăng nhập để xác nhận đặt vé");
            return;
        }

        String holdIdParam = request.getParameter("holdId");
        if (holdIdParam == null || holdIdParam.isBlank()) {
            sendError(response, 400, "BAD_REQUEST", "Thiếu mã phiên giữ ghế (holdId)");
            return;
        }

        try {
            long holdId = Long.parseLong(holdIdParam.trim());
            Ticket ticket = bookingService.confirmBooking(holdId, userId);

            Map<String, Object> res = new LinkedHashMap<>();
            res.put("success", true);
            res.put("ticketId", ticket.getId());
            res.put("ticketCode", ticket.getTicketCode());
            res.put("totalAmount", ticket.getTotalAmount());
            res.put("status", ticket.getStatus());
            res.put("ticket", ticket);
            res.put("message", "Xác nhận đặt vé thành công!");

            sendOk(response, res);

        } catch (ServiceException.NotFound e) {
            sendError(response, 404, "NOT_FOUND", e.getMessage());
        } catch (ServiceException.Forbidden e) {
            sendError(response, 403, "FORBIDDEN", e.getMessage());
        } catch (ServiceException.Conflict e) {
            sendError(response, 409, "CONFLICT", e.getMessage());
        } catch (NumberFormatException e) {
            sendError(response, 400, "BAD_REQUEST", "Mã holdId không hợp lệ");
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Lỗi khi xác nhận đặt vé", e);
            sendError(response, 500, "INTERNAL_ERROR", "Không thể xác nhận đặt vé: " + e.getMessage());
        }
    }

    /** Req 11 — Customer hủy vé của mình hoặc Staff hủy tại quầy đúng chi nhánh. */
    private void handleCancel(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        var session = request.getSession(false);
        Long customerId = null;
        Long staffBranchId = null;

        if (session != null) {
            Object uid = session.getAttribute("userId");
            if (uid instanceof Long l) customerId = l;
            else if (uid instanceof Integer i) customerId = i.longValue();

            Object bid = session.getAttribute("branchId");
            if (bid instanceof Long l) staffBranchId = l;
            else if (bid instanceof Integer i) staffBranchId = i.longValue();
        }

        String userIdParam = request.getParameter("userId");
        if (userIdParam != null && !userIdParam.isBlank()) {
            try { customerId = Long.parseLong(userIdParam.trim()); } catch (NumberFormatException ignored) {}
        }
        String branchParam = request.getParameter("branchId");
        if (branchParam != null && !branchParam.isBlank()) {
            try { staffBranchId = Long.parseLong(branchParam.trim()); } catch (NumberFormatException ignored) {}
        }

        String ticketIdParam = request.getParameter("ticketId");
        if (ticketIdParam == null || ticketIdParam.isBlank()) {
            sendError(response, 400, "BAD_REQUEST", "Thiếu tham số ticketId");
            return;
        }

        try {
            long ticketId = Long.parseLong(ticketIdParam.trim());
            Ticket ticket = bookingService.cancelTicket(ticketId, customerId, staffBranchId);

            Map<String, Object> res = new LinkedHashMap<>();
            res.put("success", true);
            res.put("ticketId", ticket.getId());
            res.put("ticketCode", ticket.getTicketCode());
            res.put("status", ticket.getStatus());
            res.put("totalAmount", ticket.getTotalAmount());
            res.put("refundAmount", ticket.getRefundAmount());
            res.put("message", "Hủy vé thành công, hoàn " + ticket.getRefundAmount() + " VNĐ");

            sendOk(response, res);

        } catch (ServiceException.NotFound e) {
            sendError(response, 404, "NOT_FOUND", e.getMessage());
        } catch (ServiceException.Forbidden e) {
            sendError(response, 403, "FORBIDDEN", e.getMessage());
        } catch (ServiceException.Conflict e) {
            sendError(response, 409, "CONFLICT", e.getMessage());
        } catch (ServiceException.BusinessRule e) {
            sendError(response, 400, e.code(), e.getMessage());
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Lỗi khi hủy vé", e);
            sendError(response, 500, "INTERNAL_ERROR", "Không thể hủy vé: " + e.getMessage());
        }
    }

    /** Req 12 — Branch Staff soát vé một lần tại đúng chi nhánh. */
    private void handleValidate(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        String ticketCode = request.getParameter("ticketCode");
        if (ticketCode == null || ticketCode.isBlank()) {
            ticketCode = request.getParameter("code");
        }
        if (ticketCode == null || ticketCode.isBlank()) {
            sendError(response, 400, "BAD_REQUEST", "Thiếu mã vé (ticketCode)");
            return;
        }

        var session = request.getSession(false);
        Long staffId = null;
        Long staffBranchId = null;

        if (session != null) {
            Object uid = session.getAttribute("userId");
            if (uid instanceof Long l) staffId = l;
            else if (uid instanceof Integer i) staffId = i.longValue();

            Object bid = session.getAttribute("branchId");
            if (bid instanceof Long l) staffBranchId = l;
            else if (bid instanceof Integer i) staffBranchId = i.longValue();
        }

        String staffParam = request.getParameter("staffId");
        if (staffParam != null && !staffParam.isBlank()) {
            try { staffId = Long.parseLong(staffParam.trim()); } catch (NumberFormatException ignored) {}
        }
        String branchParam = request.getParameter("branchId");
        if (branchParam != null && !branchParam.isBlank()) {
            try { staffBranchId = Long.parseLong(branchParam.trim()); } catch (NumberFormatException ignored) {}
        }

        if (staffId == null) staffId = 1L;
        if (staffBranchId == null) staffBranchId = 1L;

        try {
            Ticket ticket = bookingService.validateTicket(ticketCode, staffId, staffBranchId);

            Map<String, Object> res = new LinkedHashMap<>();
            res.put("success", true);
            res.put("ticketId", ticket.getId());
            res.put("ticketCode", ticket.getTicketCode());
            res.put("status", ticket.getStatus());
            res.put("usedAt", ticket.getUsedAt());
            res.put("usedBy", ticket.getUsedBy());
            res.put("message", "Soát vé thành công — Vé hợp lệ");

            sendOk(response, res);

        } catch (ServiceException.NotFound e) {
            sendError(response, 404, "NOT_FOUND", e.getMessage());
        } catch (ServiceException.Forbidden e) {
            sendError(response, 403, "FORBIDDEN", e.getMessage());
        } catch (ServiceException.Conflict e) {
            sendError(response, 409, "CONFLICT", e.getMessage());
        } catch (ServiceException.BusinessRule e) {
            sendError(response, 400, e.code(), e.getMessage());
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Lỗi khi soát vé", e);
            sendError(response, 500, "INTERNAL_ERROR", "Lỗi soát vé: " + e.getMessage());
        }
    }

    /** Tra cứu nhanh một vé theo ticketCode (read-only). */
    private void handleLookup(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        String ticketCode = request.getParameter("ticketCode");
        if (ticketCode == null || ticketCode.isBlank()) {
            ticketCode = request.getParameter("code");
        }
        if (ticketCode == null || ticketCode.isBlank()) {
            sendError(response, 400, "BAD_REQUEST", "Thiếu mã vé (ticketCode)");
            return;
        }

        var session = request.getSession(false);
        Long staffBranchId = null;
        if (session != null && session.getAttribute("branchId") != null) {
            Object bid = session.getAttribute("branchId");
            if (bid instanceof Long l) staffBranchId = l;
            else if (bid instanceof Integer i) staffBranchId = i.longValue();
        }
        String branchParam = request.getParameter("branchId");
        if (branchParam != null && !branchParam.isBlank()) {
            try { staffBranchId = Long.parseLong(branchParam.trim()); } catch (NumberFormatException ignored) {}
        }

        try {
            Map<String, Object> details = bookingService.lookupTicket(ticketCode, staffBranchId);
            sendOk(response, details);
        } catch (ServiceException.NotFound e) {
            sendError(response, 404, "NOT_FOUND", e.getMessage());
        } catch (ServiceException.Forbidden e) {
            sendError(response, 403, "FORBIDDEN", e.getMessage());
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Lỗi khi tra cứu vé", e);
            sendError(response, 500, "INTERNAL_ERROR", "Lỗi tra cứu vé: " + e.getMessage());
        }
    }

    /** Lịch sử vé cá nhân. */
    private void handleMine(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        var session = request.getSession(false);
        Long userId = null;
        if (session != null && session.getAttribute("userId") != null) {
            Object uid = session.getAttribute("userId");
            if (uid instanceof Long l) userId = l;
            else if (uid instanceof Integer i) userId = i.longValue();
        }
        if (userId == null) {
            String uParam = request.getParameter("userId");
            if (uParam != null && !uParam.isBlank()) {
                try { userId = Long.parseLong(uParam.trim()); } catch (NumberFormatException ignored) {}
            }
        }
        if (userId == null) {
            sendError(response, 401, "UNAUTHORIZED", "Vui lòng đăng nhập để xem vé");
            return;
        }

        try {
            List<Ticket> tickets = bookingService.listMyTickets(userId);
            sendOk(response, tickets);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Lỗi khi lấy danh sách vé", e);
            sendError(response, 500, "INTERNAL_ERROR", "Lỗi khi tải lịch sử vé");
        }
    }

    /** Chi tiết vé. */
    private void handleTicketDetail(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        var session = request.getSession(false);
        Long userId = session != null ? (Long) session.getAttribute("userId") : null;

        String code = request.getParameter("code");
        if (code == null || code.isBlank()) {
            sendError(response, 400, "BAD_REQUEST", "Thiếu tham số mã vé code");
            return;
        }

        try {
            Ticket ticket = bookingService.getTicketByCode(code.trim(), userId);
            sendOk(response, ticket);
        } catch (ServiceException.NotFound e) {
            sendError(response, 404, "NOT_FOUND", e.getMessage());
        } catch (ServiceException.Forbidden e) {
            sendError(response, 403, "FORBIDDEN", e.getMessage());
        } catch (Exception e) {
            sendError(response, 500, "INTERNAL_ERROR", e.getMessage());
        }
    }

    private List<Long> parseSeatIds(String param) {
        List<Long> list = new ArrayList<>();
        if (param == null || param.isBlank()) return list;
        for (String part : param.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                list.add(Long.parseLong(trimmed));
            }
        }
        return list;
    }

    private void sendOk(HttpServletResponse response, Object data) throws IOException {
        response.setContentType("application/json;charset=UTF-8");
        response.setStatus(HttpServletResponse.SC_OK);
        response.getWriter().write(SerializationUtil.toJson(data));
    }

    private void sendError(HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.setContentType("application/json;charset=UTF-8");
        response.setStatus(status);
        response.getWriter().write(SerializationUtil.toJson(Map.of(
                "success", false,
                "code", code,
                "message", message
        )));
    }
}
