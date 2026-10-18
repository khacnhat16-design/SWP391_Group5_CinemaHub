<%@ page contentType="text/html;charset=UTF-8" pageEncoding="UTF-8" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%--
  Chi nhánh — GET /branch

  Trang presentation-only: chỉ khai báo module, layout và guard đăng nhập nằm ở
  layout/rest-page.jspf. Dữ liệu do assets/js/rest-page.js gọi REST API có sẵn.
  Route: PageController GET /console/branch/list
--%>
<c:set var="restPage" value="branch" scope="page"/>
<c:set var="activeNav" value="branch" scope="request"/>
<c:set var="pageTitle" value="Chi nhánh" scope="request"/>
<c:set var="pageSubtitle" value="Danh mục chi nhánh toàn hệ thống" scope="request"/>
<%@ include file="/WEB-INF/views/layout/rest-page.jspf" %>
