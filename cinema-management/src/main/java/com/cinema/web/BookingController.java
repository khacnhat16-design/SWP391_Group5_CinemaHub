package com.cinema.web;

import com.cinema.booking.BookingService;
import com.cinema.booking.HoldResult;
import com.cinema.booking.Ticket;
import com.cinema.common.SerializationUtil;
import com.cinema.common.ServiceException;
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

/**
 * Controller API cho luồng đặt vé — Phụ trách bởi Người 4 (Nhất).
 * Chức năng 2: Giữ ghế (/booking/hold), nhả ghế (/booking/release).
 * Chức năng 3: Báo giá (/booking/quote), xác nhận đặt vé (/booking/confirm) và tra cứu vé cá nhân (/booking/mine).
 */
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
            default -> sendError(response, 404, "NOT_FOUND", "Endpoint không tồn tại: " + path);
        }
    }

    /**
     * POST /booking/quote — Báo giá vé trước khi giữ ghế (Snapshot Pricing Quote).
     */
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

    /**
     * POST /booking/hold — Khách hàng giữ ghế trực tuyến trong 10 phút.
     * Request params: showtimeId, seatIds (dạng "1,2,3")
     */
    private void handleHold(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        var session = request.getSession(false);
        if (session == null || session.getAttribute("userId") == null) {
            sendError(response, 401, "UNAUTHORIZED", "Vui lòng đăng nhập để giữ ghế");
            return;
        }

        Long userId = (Long) session.getAttribute("userId");
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
                // Trả về 409 Conflict nếu ghế đã có người khác giữ trước
                sendError(response, 409, "CONFLICT", result.message());
            }
        } catch (NumberFormatException e) {
            sendError(response, 400, "BAD_REQUEST", "Định dạng ID không hợp lệ");
        }
    }

    /**
     * POST /booking/release — Khách hàng hủy giữ ghế khi bỏ chọn.
     * Request params: showtimeId, seatIds
     */
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

    /**
     * POST /booking/confirm — Xác nhận đặt vé trong thời hạn giữ ghế 10 phút (Chức năng 3).
     * Request params: holdId (hoặc gửi trong body)
     */
    private void handleConfirm(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        var session = request.getSession(false);
        if (session == null || session.getAttribute("userId") == null) {
            sendError(response, 401, "UNAUTHORIZED", "Vui lòng đăng nhập để xác nhận đặt vé");
            return;
        }

        Long userId = (Long) session.getAttribute("userId");
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

    /**
     * GET /booking/mine — Lấy danh sách lịch sử vé của khách hàng hiện tại (Chức năng 3).
     */
    private void handleMine(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        var session = request.getSession(false);
        if (session == null || session.getAttribute("userId") == null) {
            sendError(response, 401, "UNAUTHORIZED", "Vui lòng đăng nhập để xem vé");
            return;
        }

        Long userId = (Long) session.getAttribute("userId");
        try {
            List<Ticket> tickets = bookingService.listMyTickets(userId);
            sendOk(response, tickets);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Lỗi khi lấy danh sách vé", e);
            sendError(response, 500, "INTERNAL_ERROR", "Lỗi khi tải lịch sử vé");
        }
    }

    /**
     * GET /booking/ticket?code=... — Tra cứu chi tiết vé theo mã vé (Chức năng 3).
     */
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