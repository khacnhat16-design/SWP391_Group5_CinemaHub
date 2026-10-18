<%@ page contentType="text/html;charset=UTF-8" pageEncoding="UTF-8" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%--
  Vé của tôi — GET /booking

  Trang presentation-only: chỉ khai báo module, layout và guard đăng nhập nằm ở
  layout/rest-page.jspf. Dữ liệu do assets/js/rest-page.js gọi REST API có sẵn.
  Route: PageController GET /console/booking/list
--%>
<c:set var="restPage" value="booking" scope="page"/>
<c:set var="activeNav" value="my-tickets" scope="request"/>
<c:set var="pageTitle" value="Vé của tôi" scope="request"/>
<c:set var="pageSubtitle" value="Vé thuộc tài khoản đang đăng nhập" scope="request"/>
<%@ include file="/WEB-INF/views/layout/rest-page.jspf" %>
