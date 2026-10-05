package com.cinema.web;

import com.cinema.booking.BookingService;
import com.cinema.booking.HoldResult;
import com.cinema.common.SerializationUtil;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Controller API cho luồng đặt vé — Phụ trách bởi Người 4 (Nhất).
 * Chức năng 2: Cung cấp API giữ ghế 10 phút (/booking/hold) và nhả ghế (/booking/release).
 */
public class BookingController extends HttpServlet {
    private BookingService bookingService;

    @Override
    public void init() throws ServletException {
        this.bookingService = new BookingService();
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        String path = request.getPathInfo();
        if (path == null) path = "";

        switch (path) {
            case "/hold" -> handleHold(request, response);
            case "/release" -> handleRelease(request, response);
            default -> sendError(response, 404, "NOT_FOUND", "Endpoint không tồn tại: " + path);
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
        SerializationUtil.writeJson(response, data);
    }

    private void sendError(HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.setContentType("application/json;charset=UTF-8");
        response.setStatus(status);
        SerializationUtil.writeJson(response, Map.of(
                "success", false,
                "code", code,
                "message", message
        ));
    }
}
