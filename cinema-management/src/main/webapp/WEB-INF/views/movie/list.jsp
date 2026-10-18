<%@ page contentType="text/html;charset=UTF-8" pageEncoding="UTF-8" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%--
  Phim — GET /movie

  Trang presentation-only: chỉ khai báo module, layout và guard đăng nhập nằm ở
  layout/rest-page.jspf. Dữ liệu do assets/js/rest-page.js gọi REST API có sẵn.
  Route: PageController GET /console/movie/list
--%>
<c:set var="restPage" value="movie" scope="page"/>
<c:set var="activeNav" value="movie" scope="request"/>
<c:set var="pageTitle" value="Danh mục phim" scope="request"/>
<c:set var="pageSubtitle" value="Phim trong hệ thống và trạng thái phát hành" scope="request"/>
<%@ include file="/WEB-INF/views/layout/rest-page.jspf" %>
