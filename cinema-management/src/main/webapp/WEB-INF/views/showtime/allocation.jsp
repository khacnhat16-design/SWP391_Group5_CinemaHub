<%@ page contentType="text/html;charset=UTF-8" pageEncoding="UTF-8" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%--
  Lịch sử dữ liệu phân bổ suất chiếu cũ (không còn giới hạn lịch chiếu).

  Trang presentation-only: dữ liệu do assets/js/modules-form-pages.js (module
  showtime-allocation) gọi REST API /api/showtime-allocations.
  Layout và guard đăng nhập nằm ở layout/rest-page.jspf.
  Route: PageController GET /console/showtime/allocation
--%>
<c:set var="restPage" value="showtime-allocation" scope="page"/>
<c:set var="activeNav" value="showtime-allocation" scope="request"/>
<c:set var="pageTitle" value="Lịch phân bổ cũ" scope="request"/>
<c:set var="pageSubtitle" value="Hạn mức phân bổ đã ngừng áp dụng; Branch Manager tự quản lý lịch chiếu." scope="request"/>
<%@ include file="/WEB-INF/views/layout/rest-page.jspf" %>
