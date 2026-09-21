package com.cinema.web;

import com.cinema.common.CsrfUtil;
import com.cinema.common.SerializationUtil;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bootstrap endpoint cho frontend (GET — không đổi trạng thái nên không bị CsrfFilter chặn).
 *
 * <p>Trả CSRF token của session + thông tin phiên hiện tại để JS dựng UI theo role
 * và gửi đúng token trên mọi request POST/PUT/DELETE (design.md Security: CSRF token).
 */
public class SessionController extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        response.setContentType("application/json;charset=UTF-8");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("csrfToken", CsrfUtil.token(request));

        var session = request.getSession(false);
        payload.put("username", session == null ? null : session.getAttribute("username"));
        payload.put("role", session == null ? null : session.getAttribute("role"));
        payload.put("userId", session == null ? null : session.getAttribute("userId"));

        // AccessScope chứa class load từ webapp loader; nếu pass qua request
        // attribute thì ở endpoint khác (vd. controller JSON) cùng class nhưng
        // classloader khác dẫn đến ClassCastException. Lưu branchIds là mảng
        // Long nguyên thuỷ thay vì object AccessScope để tránh vấn đề này.
        Object branchIdsRaw = session.getAttribute("scopeBranchIds");
        if (branchIdsRaw instanceof Long[] arr) {
            payload.put("branchIds", java.util.Set.of(arr));
        }

        response.getWriter().write(SerializationUtil.toJson(payload));
    }
}
