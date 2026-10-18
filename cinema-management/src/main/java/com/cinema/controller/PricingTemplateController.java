package com.cinema.controller;

import com.cinema.model.auth.AccessScope;
import com.cinema.common.ErrorEnvelope;
import com.cinema.common.SerializationUtil;
import com.cinema.common.ServiceException;
import com.cinema.filter.AuthFilter;
import com.cinema.model.pricing.PriceTemplateRule;
import com.cinema.service.pricing.PriceTemplateService;
import com.cinema.dao.pricing.PriceTemplateDAO;
import com.cinema.dao.pricing.PriceRuleDAO;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/** REST API for reusable ticket-price templates and their movie/branch application. */
public final class PricingTemplateController extends HttpServlet {
    private static final Logger LOGGER = Logger.getLogger(PricingTemplateController.class.getName());
    private PriceTemplateService service;

    private record RuleRequest(String seatType, String dayType, String timeSlot, Long price,
                               String ticketType, String activeFrom, String activeTo) { }
    private record TemplateRequest(String name, String description, List<RuleRequest> rules) { }
    private record CloneRequest(String name, String description, Long movieId, Long sourceBranchId) { }
    private record ApplyRequest(Long movieId, List<Long> branchIds,
                                Map<String, String> expectedState, Boolean replaceExisting) { }
    private record ApplyConflict(String code, String message,
                                List<PriceTemplateService.BranchApplyResult> results) { }

    @Override
    public void init() throws ServletException {
        service = new PriceTemplateService(new PriceTemplateDAO(), new PriceRuleDAO());
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        AccessScope scope = scope(request, response);
        if (scope == null) return;
        String path = path(request);
        try {
            if ("/source-rules".equals(path)) {
                long movieId = queryId(request, "movieId");
                long branchId = queryId(request, "branchId");
                sendOk(response, service.previewSource(scope, movieId, branchId));
                return;
            }
            if (isRoot(path)) {
                sendOk(response, service.listTemplates(scope));
                return;
            }
            Long id = templateId(path);
            if (id != null) {
                sendOk(response, service.getTemplate(scope, id));
                return;
            }
            sendError(response, 404, "NOT_FOUND", "Endpoint không tồn tại");
        } catch (Exception e) {
            handleError(response, e);
        }
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        AccessScope scope = scope(request, response);
        if (scope == null) return;
        String path = path(request);
        try {
            if (isRoot(path)) {
                TemplateRequest body = readBody(request, TemplateRequest.class);
                sendOk(response, service.createTemplate(scope, toInput(body)));
                return;
            }
            if ("/from-movie".equals(path)) {
                CloneRequest body = readBody(request, CloneRequest.class);
                if (body.movieId() == null || body.sourceBranchId() == null) {
                    throw new ServiceException.Validation("Chọn phim và chi nhánh nguồn");
                }
                sendOk(response, service.createFromMovie(scope, body.name(), body.description(),
                        body.movieId(), body.sourceBranchId()));
                return;
            }
            String[] action = path.split("/");
            if (action.length == 3 && "preview".equals(action[2])) {
                long id = parseId(action[1]);
                ApplyRequest body = readBody(request, ApplyRequest.class);
                if (body.movieId() == null) {
                    throw new ServiceException.Validation("Thiếu movieId");
                }
                sendOk(response, service.previewApplication(scope, id, body.movieId(), body.branchIds()));
                return;
            }
            if (action.length == 3 && "apply".equals(action[2])) {
                long id = parseId(action[1]);
                ApplyRequest body = readBody(request, ApplyRequest.class);
                if (body.movieId() == null) throw new ServiceException.Validation("Thiếu movieId");
                Map<Long, String> expected = expectedState(body.expectedState());
                PriceTemplateService.ApplyResult result = service.applyTemplate(scope, id,
                        body.movieId(), body.branchIds(), expected,
                        Boolean.TRUE.equals(body.replaceExisting()));
                boolean conflict = result.branches().stream()
                        .anyMatch(branch -> "CONFLICT".equals(branch.status()));
                if (conflict) {
                    sendJson(response, 409, new ApplyConflict("CONFLICT",
                            "Giá tại một hoặc nhiều chi nhánh đã thay đổi; hãy xem trước lại.",
                            result.branches()));
                } else {
                    sendOk(response, result);
                }
                return;
            }
            sendError(response, 404, "NOT_FOUND", "Endpoint không tồn tại");
        } catch (Exception e) {
            handleError(response, e);
        }
    }

    @Override
    protected void doPut(HttpServletRequest request, HttpServletResponse response) throws IOException {
        AccessScope scope = scope(request, response);
        if (scope == null) return;
        try {
            Long id = templateId(path(request));
            if (id == null) {
                sendError(response, 404, "NOT_FOUND", "Endpoint không tồn tại");
                return;
            }
            sendOk(response, service.updateTemplate(scope, id,
                    toInput(readBody(request, TemplateRequest.class))));
        } catch (Exception e) {
            handleError(response, e);
        }
    }

    @Override
    protected void doDelete(HttpServletRequest request, HttpServletResponse response) throws IOException {
        AccessScope scope = scope(request, response);
        if (scope == null) return;
        try {
            Long id = templateId(path(request));
            if (id == null) {
                sendError(response, 404, "NOT_FOUND", "Endpoint không tồn tại");
                return;
            }
            service.deleteTemplate(scope, id);
            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
        } catch (Exception e) {
            handleError(response, e);
        }
    }

    private static PriceTemplateService.TemplateInput toInput(TemplateRequest request) {
        if (request == null) throw new ServiceException.Validation("Thiếu dữ liệu mẫu giá");
        if (request.rules() == null) throw new ServiceException.Validation("Thiếu danh sách khung giá");
        List<PriceTemplateRule> rules = request.rules().stream().map(row -> {
            if (row == null || row.price() == null) {
                throw new ServiceException.Validation("Mỗi khung giá phải có giá");
            }
            PriceTemplateRule rule = new PriceTemplateRule();
            rule.setSeatType(row.seatType());
            rule.setDayType(row.dayType());
            rule.setTimeSlot(row.timeSlot());
            rule.setPrice(row.price());
            rule.setTicketType(blankToNull(row.ticketType()));
            rule.setActiveFrom(parseDate(row.activeFrom()));
            rule.setActiveTo(parseDate(row.activeTo()));
            return rule;
        }).toList();
        return new PriceTemplateService.TemplateInput(request.name(), request.description(), rules);
    }

    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new ServiceException.Validation("Ngày hiệu lực phải theo định dạng yyyy-MM-dd");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Map<Long, String> expectedState(Map<String, String> raw) {
        if (raw == null) return null;
        try {
            return raw.entrySet().stream().collect(Collectors.toMap(
                    entry -> Long.parseLong(entry.getKey()), Map.Entry::getValue));
        } catch (NumberFormatException e) {
            throw new ServiceException.Validation("Trạng thái xem trước không hợp lệ");
        }
    }

    private static <T> T readBody(HttpServletRequest request, Class<T> type) throws IOException {
        String json = request.getReader().lines().collect(Collectors.joining());
        if (json.isBlank()) throw new ServiceException.Validation("Thiếu dữ liệu request");
        try {
            return SerializationUtil.fromJson(json, type);
        } catch (Exception e) {
            throw new ServiceException.Validation("Dữ liệu JSON không hợp lệ");
        }
    }

    private static AccessScope scope(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        AccessScope scope = (AccessScope) request.getAttribute(AuthFilter.SCOPE_ATTRIBUTE);
        if (scope == null || scope.isGuest()) {
            sendError(response, 401, "UNAUTHORIZED", "Cần đăng nhập");
            return null;
        }
        return scope;
    }

    private static String path(HttpServletRequest request) {
        String path = request.getPathInfo();
        return path == null || path.isBlank() ? "/" : path;
    }

    private static boolean isRoot(String path) {
        return "/".equals(path);
    }

    private static Long templateId(String path) {
        if (path == null || !path.matches("/\\d+")) return null;
        return parseId(path.substring(1));
    }

    private static long parseId(String value) {
        try {
            long id = Long.parseLong(value);
            if (id < 1) throw new NumberFormatException();
            return id;
        } catch (NumberFormatException e) {
            throw new ServiceException.Validation("ID mẫu giá không hợp lệ");
        }
    }

    private static long queryId(HttpServletRequest request, String key) {
        String value = request.getParameter(key);
        if (value == null || value.isBlank()) {
            throw new ServiceException.Validation("Thiếu " + key);
        }
        try {
            long id = Long.parseLong(value.trim());
            if (id < 1) throw new NumberFormatException();
            return id;
        } catch (NumberFormatException e) {
            throw new ServiceException.Validation(key + " không hợp lệ");
        }
    }

    private static void handleError(HttpServletResponse response, Exception error) throws IOException {
        if (error instanceof ServiceException se) {
            sendError(response, se.httpStatus(), se.code(), se.getMessage());
            return;
        }
        LOGGER.log(Level.SEVERE, "Lỗi xử lý API mẫu giá vé", error);
        sendError(response, 500, "INTERNAL_ERROR", "Không thể xử lý yêu cầu mẫu giá lúc này");
    }

    private static void sendOk(HttpServletResponse response, Object data) throws IOException {
        sendJson(response, HttpServletResponse.SC_OK, data);
    }

    private static void sendError(HttpServletResponse response, int status, String code, String message)
            throws IOException {
        sendJson(response, status, new ErrorEnvelope(code, message));
    }

    private static void sendJson(HttpServletResponse response, int status, Object body) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(SerializationUtil.toJson(body));
    }
}
