<%@ page contentType="text/html;charset=UTF-8" pageEncoding="UTF-8" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%--
  Thông báo — GET /notification

  Trang presentation-only: chỉ khai báo module, layout và guard đăng nhập nằm ở
  layout/rest-page.jspf. Dữ liệu do assets/js/rest-page.js gọi REST API có sẵn.
  Route: PageController GET /console/notification/list
--%>
<c:set var="restPage" value="notification" scope="page"/>
<c:set var="activeNav" value="notification" scope="request"/>
<c:set var="pageTitle" value="Thông báo" scope="request"/>
<c:set var="pageSubtitle" value="Theo dõi cảnh báo xếp lịch và chọn Xếp lịch ngay để xử lý phân bổ còn thiếu." scope="request"/>
<%@ include file="/WEB-INF/views/layout/rest-page.jspf" %>
