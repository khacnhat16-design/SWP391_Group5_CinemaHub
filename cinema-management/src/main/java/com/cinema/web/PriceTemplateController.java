package com.cinema.web;

import com.cinema.auth.AccessScope;
import com.cinema.common.ErrorEnvelope;
import com.cinema.common.SerializationUtil;
import com.cinema.common.ServiceException;
import com.cinema.filter.AuthFilter;
import com.cinema.pricing.PriceRuleDAO;
import com.cinema.pricing.PriceTemplate;
import com.cinema.pricing.PriceTemplateDAO;
import com.cinema.pricing.PriceTemplateService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** REST endpoints for reusable ticket-price templates. */
public final class PriceTemplateController extends HttpServlet {
    private PriceTemplateService templateService;

    public record SourceInput(String name, String description, long movieId, long sourceBranchId) { }
    public record ApplyInput(long movieId, List<Long> branchIds, Map<String, String> expectedState,
                             boolean replaceExisting) { }
    public record PreviewInput(long movieId, List<Long> branchIds) { }

    @Override
    public void init() throws ServletException {
        templateService = new PriceTemplateService(new PriceTemplateDAO(), new PriceRuleDAO());
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        try {
            AccessScope scope = scope(request);
            String path = normalizedPath(request);
            if ("/source-rules".equals(path)) {
                sendOk(response, templateService.previewSource(scope,
                        requiredLong(request, "movieId"), requiredLong(request, "branchId")));
            } else if (path.isEmpty()) {
                sendOk(response, templateService.listTemplates(scope));
            } else {
                sendOk(response, templateService.getTemplate(scope, pathId(path)));
            }
        } catch (Exception e) {
            handleError(response, e);
        }
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        try {
            AccessScope scope = scope(request);
            String path = normalizedPath(request);
            if ("/from-movie".equals(path)) {
                SourceInput input = readJson(request, SourceInput.class);
                PriceTemplate created = templateService.createFromMovie(scope, input.name(),
                        input.description(), input.movieId(), input.sourceBranchId());
                sendOk(response, created);
            } else if (path.matches("/\\d+/preview")) {
                PreviewInput input = readJson(request, PreviewInput.class);
                sendOk(response, templateService.previewApplication(scope,
                        pathId(path.substring(0, path.indexOf("/preview"))),
                        input.movieId(), input.branchIds()));
            } else if (path.matches("/\\d+/apply")) {
                ApplyInput input = readJson(request, ApplyInput.class);
                Map<Long, String> expectedState = new HashMap<>();
                if (input.expectedState() != null) {
                    input.expectedState().forEach((key, value) ->
                            expectedState.put(Long.parseLong(key), value));
                }
                sendOk(response, templateService.applyTemplate(scope,
                        pathId(path.substring(0, path.indexOf("/apply"))),
                        input.movieId(), input.branchIds(), expectedState, input.replaceExisting()));
            } else if (path.isEmpty()) {
                sendOk(response, templateService.createTemplate(scope,
                        readJson(request, PriceTemplateService.TemplateInput.class)));
            } else {
                sendError(response, 404, "NOT_FOUND", "Đường dẫn mẫu giá không tồn tại.");
            }
        } catch (Exception e) {
            handleError(response, e);
        }
    }

    @Override
    protected void doPut(HttpServletRequest request, HttpServletResponse response) throws IOException {
        try {
            String path = normalizedPath(request);
            if (!path.matches("/\\d+")) {
                throw new ServiceException.Validation("ID mẫu giá không hợp lệ");
            }
            sendOk(response, templateService.updateTemplate(scope(request), pathId(path),
                    readJson(request, PriceTemplateService.TemplateInput.class)));
        } catch (Exception e) {
            handleError(response, e);
        }
    }

    @Override
    protected void doDelete(HttpServletRequest request, HttpServletResponse response) throws IOException {
        try {
            String path = normalizedPath(request);
            if (!path.matches("/\\d+")) {
                throw new ServiceException.Validation("ID mẫu giá không hợp lệ");
            }
            templateService.deleteTemplate(scope(request), pathId(path));
            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
        } catch (Exception e) {
            handleError(response, e);
        }
    }

    private static AccessScope scope(HttpServletRequest request) {
        return (AccessScope) request.getAttribute(AuthFilter.SCOPE_ATTRIBUTE);
    }

    private static String normalizedPath(HttpServletRequest request) {
        String path = request.getPathInfo();
        return path == null || "/".equals(path) ? "" : path;
    }

    private static long pathId(String path) {
        if (path == null || !path.matches("/?\\d+")) {
            throw new ServiceException.Validation("ID mẫu giá không hợp lệ");
        }
        return Long.parseLong(path.startsWith("/") ? path.substring(1) : path);
    }

    private static long requiredLong(HttpServletRequest request, String name) {
        String value = request.getParameter(name);
        if (value == null || value.isBlank()) {
            throw new ServiceException.Validation(name + " là bắt buộc");
        }
        return Long.parseLong(value.trim());
    }

    private static <T> T readJson(HttpServletRequest request, Class<T> type) throws IOException {
        try {
            return SerializationUtil.fromJson(
                    request.getReader().lines().reduce("", String::concat), type);
        } catch (Exception e) {
            throw new IOException("Dữ liệu JSON không hợp lệ", e);
        }
    }

    private void handleError(HttpServletResponse response, Exception exception) throws IOException {
        if (exception instanceof ServiceException serviceException) {
            sendError(response, serviceException.httpStatus(), serviceException.code(),
                    serviceException.getMessage());
        } else if (exception instanceof NumberFormatException || exception instanceof IOException) {
            sendError(response, 400, "BAD_REQUEST", "Dữ liệu yêu cầu không hợp lệ.");
        } else {
            getServletContext().log("Price template request failed", exception);
            sendError(response, 500, "INTERNAL_ERROR", "Không thể xử lý mẫu giá.");
        }
    }

    private void sendOk(HttpServletResponse response, Object data) throws IOException {
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(SerializationUtil.toJson(data));
    }

    private void sendError(HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(SerializationUtil.toJson(new ErrorEnvelope(code, message)));
    }
}
