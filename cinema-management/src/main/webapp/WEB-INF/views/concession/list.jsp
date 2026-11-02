<%@ page contentType="text/html;charset=UTF-8" pageEncoding="UTF-8" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%--
  Đơn bắp nước — GET /concession/orders/mine

  Trang presentation-only: chỉ khai báo module, layout và guard đăng nhập nằm ở
  layout/rest-page.jspf. Dữ liệu do assets/js/rest-page.js gọi REST API có sẵn.
  Route: PageController GET /console/concession/list
--%>
<c:set var="restPage" value="concession" scope="page"/>
<c:set var="activeNav" value="my-orders" scope="request"/>
<c:set var="pageTitle" value="Đơn bắp nước" scope="request"/>
<c:set var="pageSubtitle" value="Đơn pre-order của tài khoản đang đăng nhập" scope="request"/>
<%@ include file="/WEB-INF/views/layout/rest-page.jspf" %>
