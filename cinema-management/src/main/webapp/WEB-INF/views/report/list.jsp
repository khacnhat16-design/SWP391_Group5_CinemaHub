<%@ page contentType="text/html;charset=UTF-8" pageEncoding="UTF-8" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%--
  Báo cáo — GET /report/summary

  Trang presentation-only: chỉ khai báo module, layout và guard đăng nhập nằm ở
  layout/rest-page.jspf. Dữ liệu do assets/js/rest-page.js gọi REST API có sẵn.
  Route: PageController GET /console/report/list
--%>
<c:set var="restPage" value="report" scope="page"/>
<c:set var="activeNav" value="report" scope="request"/>
<c:set var="pageTitle" value="Báo cáo" scope="request"/>
<c:set var="pageSubtitle" value="Doanh thu và vận hành theo phạm vi dữ liệu" scope="request"/>
<%@ include file="/WEB-INF/views/layout/rest-page.jspf" %>
