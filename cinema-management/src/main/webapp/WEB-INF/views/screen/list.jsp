<%@ page contentType="text/html;charset=UTF-8" pageEncoding="UTF-8" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%--
  Phòng chiếu — GET /screen?branchId=

  Trang presentation-only: chỉ khai báo module, layout và guard đăng nhập nằm ở
  layout/rest-page.jspf. Dữ liệu do assets/js/rest-page.js gọi REST API có sẵn.
  Route: PageController GET /console/screen/list
--%>
<c:set var="restPage" value="screen" scope="page"/>
<c:set var="activeNav" value="screen" scope="request"/>
<c:set var="pageTitle" value="Phòng chiếu" scope="request"/>
<c:set var="pageSubtitle" value="Phòng chiếu thuộc phạm vi chi nhánh hiện tại" scope="request"/>
<%@ include file="/WEB-INF/views/layout/rest-page.jspf" %>
