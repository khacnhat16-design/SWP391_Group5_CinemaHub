package com.cinema.web;

import com.cinema.auth.AccessScope;
import com.cinema.auth.Role;
import com.cinema.branch.BranchDAO;
import com.cinema.common.ErrorEnvelope;
import com.cinema.common.SerializationUtil;
import com.cinema.common.ServiceException;
import com.cinema.filter.AuthFilter;
import com.cinema.movie.MovieDAO;
import com.cinema.pricing.PriceRuleDAO;
import com.cinema.pricing.PricingService;
import com.cinema.screen.ScreenDAO;
import com.cinema.showtime.Showtime;
import com.cinema.showtime.ShowtimeDAO;
import com.cinema.showtime.ShowtimeService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;

/**
 * REST API controller cho lịch chiếu (Req 4.1-4.7) và discovery công khai (Req 6.1-6.4).
 *
 * <p>Access model:
 * <ul>
 *   <li>GET /showtime/discovery — Guest/Customer/mọi role: danh sách OPEN kèm ghế trống (Req 6.1).</li>
 *   <li>GET /showtime/{id} — chi tiết suất chiếu + trạng thái ghế (Req 6.3).</li>
 *   <li>POST/PUT — Branch Manager (SHOWTIME_MANAGE) hoặc Admin, ép branch scope (Req 4.1).</li>
 * </ul>
 */
public class ShowtimeController extends HttpServlet {
    private ShowtimeService showtimeService;
    private PricingService pricingService;
    private ScreenDAO screenDao;

    @Override
    public void init() throws ServletException {
        this.screenDao = new ScreenDAO();
        this.showtimeService = new ShowtimeService(
                new ShowtimeDAO(), screenDao, new MovieDAO(), new BranchDAO());
        this.pricingService = new PricingService(new PriceRuleDAO(), new ShowtimeDAO(),
                new com.cinema.pricing.HolidayDAO(),
                new com.cinema.pricing.PricingConfigDAO());
        this.showtimeService.setAllocationHook(
                new com.cinema.showtime.ShowtimeAllocationService(
                        new com.cinema.showtime.ShowtimeAllocationDAO(),
                        new com.cinema.notification.NotificationService(
                                new com.cinema.notification.NotificationDAO())));
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        try {
            String pathInfo = request.getPathInfo();

            // Req 6.1 — discovery công khai, Guest không cần đăng nhập
            if (pathInfo != null && pathInfo.startsWith("/discovery")) {
                Long branchId = parseOptionalLong(request.getParameter("branchId"));
                Long movieId = parseOptionalLong(request.getParameter("movieId"));
                LocalDate date = parseOptionalDate(request.getParameter("date"));
                java.util.List<Showtime> shows = showtimeService.discover(branchId, movieId, date);
                java.time.LocalDateTime now = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC);
                java.util.List<java.util.Map<String, Object>> payload = new java.util.ArrayList<>();
                for (Showtime s : shows) {
                    java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("id", s.id());
                    row.put("movieId", s.movieId());
                    row.put("movieTitle", s.movieTitle());
                    row.put("branchId", s.branchId());
                    row.put("branchName", s.branchName());
                    row.put("screenId", s.screenId());
                    row.put("screenName", s.screenName());
                    row.put("startTime", s.startTime());
                    row.put("endTime", s.endTime());
                    row.put("availableSeats", s.availableSeats());
                    java.util.Map<String, Long> seatPrices = new java.util.LinkedHashMap<>();
                    pricingService.priceTableFor(s).forEach((type, price) ->
                            seatPrices.put(type, price.price()));
                    row.put("seatPrices", seatPrices);
                    row.put("standardPrice", seatPrices.get("STANDARD"));
                    row.put("status", s.status());
                    boolean passed = s.endTime() != null && now.isAfter(s.endTime());
                    row.put("showtimePassed", passed);
                    payload.add(row);
                }
                sendOk(response, payload);
                return;
            }

            // Req 6.3 — chi tiết suất chiếu kèm trạng thái ghế (công khai cho Customer chọn ghế)
            if (pathInfo != null && pathInfo.matches("/\\d+")) {
                long showtimeId = Long.parseLong(pathInfo.substring(1));
                var detail = showtimeService.getDetail(showtimeId);
                var screen = screenDao.findById(detail.showtime().screenId())
                        .orElseThrow(() -> new ServiceException.NotFound("Phòng chiếu không tồn tại"));
                Object sessionUserId = request.getSession(false) == null
                        ? null : request.getSession(false).getAttribute("userId");
                java.util.List<java.util.Map<String, Object>> seatPayload = new java.util.ArrayList<>();
                java.util.Map<String, Long> statusCounts = new java.util.LinkedHashMap<>();
                for (var seat : detail.seats()) {
                    statusCounts.merge(seat.status(), 1L, Long::sum);
                    java.util.Map<String, Object> seatRow = new java.util.LinkedHashMap<>();
                    seatRow.put("seatId", seat.seatId());
                    seatRow.put("rowLabel", seat.rowLabel());
                    seatRow.put("colNo", seat.colNo());
                    seatRow.put("seatType", seat.seatType());
                    seatRow.put("status", seat.status());
                    seatRow.put("holdId", seat.holdId());
                    seatRow.put("holdExpiresAt", seat.holdExpiresAt());
                    seatRow.put("holdUserId", seat.holdUserId());
                    seatRow.put("ownedByCurrentUser", sessionUserId instanceof Long
                            && seat.holdUserId() != null
                            && seat.holdUserId().equals(sessionUserId));
                    seatPayload.add(seatRow);
                }
                java.util.logging.Logger.getLogger(ShowtimeController.class.getName()).info(
                        "[booking-debug] showtime=" + showtimeId
                                + " seats=" + detail.seats().size()
                                + " statusCounts=" + statusCounts);
                java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
                result.put("showtime", detail.showtime());
                result.put("seats", seatPayload);
                result.put("screenRowCount", screen.rowCount());
                result.put("screenColCount", screen.colCount());
                java.util.Map<String, Long> seatPrices = new java.util.LinkedHashMap<>();
                pricingService.priceTableFor(detail.showtime()).forEach((type, price) ->
                        seatPrices.put(type, price.price()));
                result.put("seatPrices", seatPrices);
                sendOk(response, result);
                return;
            }

            // Endpoint tìm kiếm suất chiếu cho trang quản lý (Admin/Manager);
            // paginated table phục vụ filter theo chi nhánh, phim, ngày...
            if (pathInfo != null && pathInfo.equals("/manage/search")) {
                AccessScope scope = requireManager(request, response);
                if (scope == null) return;
                ShowtimeDAO.ShowtimeQuery q = new ShowtimeDAO.ShowtimeQuery();
                q.branchId = parseOptionalLong(request.getParameter("branchId"));
                q.movieId = parseOptionalLong(request.getParameter("movieId"));
                q.status = request.getParameter("status");
                q.date = parseOptionalDate(request.getParameter("date"));
                q.sortBy = request.getParameter("sortBy");
                q.sortDir = request.getParameter("sortDir");
                q.offset = parseInt(request.getParameter("offset"), 0);
                q.limit = parseInt(request.getParameter("limit"), 20);
                if (scope.role() != Role.ADMIN) {
                    if (q.branchId != null && !scope.includesBranch(q.branchId)) {
                        sendForbidden(response, "Không thể xem suất chiếu của chi nhánh khác");
                        return;
                    }
                    if (q.branchId == null) {
                        if (scope.branchIds().isEmpty()) {
                            q.branchId = -1L;
                        } else if (scope.branchIds().size() == 1) {
                            q.branchId = scope.branchIds().iterator().next();
                        } else {
                            sendBadRequest(response,
                                    "Chọn chi nhánh được phân công để xem suất chiếu");
                            return;
                        }
                    }
                }
                ShowtimeDAO dao = new ShowtimeDAO();
                long total = dao.countSearch(q);
                java.util.List<Showtime> items = dao.search(q);
                sendOk(response, java.util.Map.of(
                        "items", items,
                        "total", total,
                        "offset", q.offset,
                        "limit", q.limit
                ));
                return;
            }

            sendBadRequest(response, "Đường dẫn không hợp lệ — dùng /showtime/discovery hoặc /showtime/{id}");
        } catch (NumberFormatException e) {
            sendBadRequest(response, "ID không hợp lệ");
        } catch (DateTimeParseException e) {
            sendBadRequest(response, "Định dạng ngày không hợp lệ (yyyy-MM-dd)");
        } catch (Exception e) {
            handleException(response, e);
        }
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        try {
            AccessScope scope = requireManager(request, response);
            if (scope == null) return;

            long movieId = Long.parseLong(requireParam(request, "movieId"));
            long screenId = Long.parseLong(requireParam(request, "screenId"));
            LocalDateTime startTime = parseDateTime(requireParam(request, "startTime"));
            Integer bufferMin = parseOptionalInt(request.getParameter("cleaningBufferMin"));

            Showtime showtime = showtimeService.create(scopeBranch(scope), movieId, screenId,
                    startTime, bufferMin);
            sendOk(response, showtime);
        } catch (NumberFormatException e) {
            sendBadRequest(response, "Dữ liệu số không hợp lệ");
        } catch (DateTimeParseException e) {
            sendBadRequest(response, "Định dạng thời gian không hợp lệ (yyyy-MM-ddTHH:mm)");
        } catch (Exception e) {
            handleException(response, e);
        }
    }

    @Override
    protected void doPut(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        try {
            AccessScope scope = requireManager(request, response);
            if (scope == null) return;

            long showtimeId = Long.parseLong(request.getPathInfo().substring(1));
            String action = request.getParameter("action");

            if ("cancel".equals(action)) {
                sendOk(response, showtimeService.cancel(scopeBranch(scope), showtimeId));
            } else if ("restore".equals(action)) {
                sendOk(response, showtimeService.restore(scopeBranch(scope), showtimeId));
            } else {
                LocalDateTime newStart = parseDateTime(requireParam(request, "startTime"));
                sendOk(response, showtimeService.updateTime(scopeBranch(scope), showtimeId, newStart));
            }
        } catch (NumberFormatException e) {
            sendBadRequest(response, "Dữ liệu số không hợp lệ");
        } catch (DateTimeParseException e) {
            sendBadRequest(response, "Định dạng thời gian không hợp lệ (yyyy-MM-ddTHH:mm)");
        } catch (Exception e) {
            handleException(response, e);
        }
    }

    // ---- helpers (mirror ScreenController patterns) ----

    /** Branch Manager (SHOWTIME_MANAGE) hoặc Admin; các role khác 403. */
    private AccessScope requireManager(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        AccessScope scope = (AccessScope) request.getAttribute(AuthFilter.SCOPE_ATTRIBUTE);
        if (scope == null || scope.isGuest() || !scope.can("SHOWTIME_MANAGE")) {
            sendForbidden(response, "Chỉ Branch Manager hoặc Admin mới có thể quản lý lịch chiếu");
            return null;
        }
        return scope;
    }

    private Long scopeBranch(AccessScope scope) {
        if (scope.role() == Role.ADMIN) return null;
        return scope.branchIds().isEmpty() ? -1L : scope.branchIds().iterator().next();
    }

    private String requireParam(HttpServletRequest request, String name) {
        String value = request.getParameter(name);
        if (value == null || value.isBlank()) {
            throw new ServiceException.Validation("Thiếu tham số bắt buộc: " + name);
        }
        return value.trim();
    }

    private Long parseOptionalLong(String raw) {
        return raw == null || raw.isBlank() ? null : Long.parseLong(raw.trim());
    }

    private Integer parseOptionalInt(String raw) {
        return raw == null || raw.isBlank() ? null : Integer.parseInt(raw.trim());
    }

    private int parseInt(String raw, int fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private LocalDate parseOptionalDate(String raw) {
        return raw == null || raw.isBlank() ? null : LocalDate.parse(raw.trim());
    }

    private LocalDateTime parseDateTime(String raw) {
        return LocalDateTime.parse(raw.trim());
    }

    private void sendOk(HttpServletResponse response, Object data) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(SerializationUtil.toJson(data));
    }

    private void sendForbidden(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(SerializationUtil.toJson(new ErrorEnvelope("FORBIDDEN", message)));
    }

    private void sendBadRequest(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(SerializationUtil.toJson(new ErrorEnvelope("BAD_REQUEST", message)));
    }

    private void handleException(HttpServletResponse response, Exception e) throws IOException {
        if (e instanceof ServiceException service) {
            response.setStatus(service.httpStatus());
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(SerializationUtil.toJson(
                    new ErrorEnvelope(service.code(), service.getMessage())));
        } else {
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(SerializationUtil.toJson(
                    new ErrorEnvelope("INTERNAL_ERROR", "Lỗi hệ thống")));
        }
    }
}
