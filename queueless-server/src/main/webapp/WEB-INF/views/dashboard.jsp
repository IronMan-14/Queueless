<%@ page contentType="text/html; charset=UTF-8" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fn" uri="jakarta.tags.functions" %>
<%@ taglib prefix="ql" tagdir="/WEB-INF/tags" %>
<!doctype html>
<html lang="en">
<head><meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title><c:out value="${pageTitle}" /> · QueueLess</title><link rel="stylesheet" href="${pageContext.request.contextPath}/assets/app.css"><script src="${pageContext.request.contextPath}/assets/app.js" defer></script></head>
<body class="workspace" data-refresh="true" data-user-id="${dashboard.user.id}" data-workspace-view="${workspaceView}">
<aside class="sidebar">
  <a class="brand" href="${pageContext.request.contextPath}/app?serviceId=${dashboard.serviceId}&amp;view=overview"><span class="brand-mark">Q</span> QueueLess</a>
  <p class="nav-label">YOUR WORKSPACE</p>
  <nav aria-label="Workspace">
    <a href="${pageContext.request.contextPath}/app?serviceId=${dashboard.serviceId}&amp;view=overview" class="${workspaceView == 'overview' ? 'nav-active' : ''}" ${workspaceView == 'overview' ? 'aria-current="page"' : ''}><ql:icon name="overview" /><span>Overview</span></a>
    <a href="${pageContext.request.contextPath}/app?serviceId=${dashboard.serviceId}&amp;view=visits" class="${workspaceView == 'visits' ? 'nav-active' : ''}" ${workspaceView == 'visits' ? 'aria-current="page"' : ''}><ql:icon name="visits" /><span>Queue &amp; visits</span></a>
    <a href="${pageContext.request.contextPath}/app?serviceId=${dashboard.serviceId}&amp;view=appointments" class="${workspaceView == 'appointments' ? 'nav-active' : ''}" ${workspaceView == 'appointments' ? 'aria-current="page"' : ''}><ql:icon name="calendar" /><span>Appointments</span></a>
    <a href="${pageContext.request.contextPath}/app?serviceId=${dashboard.serviceId}&amp;view=notifications" class="${workspaceView == 'notifications' ? 'nav-active' : ''}" ${workspaceView == 'notifications' ? 'aria-current="page"' : ''}><ql:icon name="bell" /><span>Notifications</span><c:if test="${dashboard.unreadCount > 0}"><span class="nav-badge"><span class="sr-only">Unread: </span>${dashboard.unreadCount}</span></c:if></a>
    <c:if test="${dashboard.operator}">
      <a href="${pageContext.request.contextPath}/app?serviceId=${dashboard.serviceId}&amp;view=counters" class="${workspaceView == 'counters' ? 'nav-active' : ''}" ${workspaceView == 'counters' ? 'aria-current="page"' : ''}><ql:icon name="counter" /><span>Service counters</span></a>
      <a href="${pageContext.request.contextPath}/app?serviceId=${dashboard.serviceId}&amp;view=reports" class="${workspaceView == 'reports' ? 'nav-active' : ''}" ${workspaceView == 'reports' ? 'aria-current="page"' : ''}><ql:icon name="chart" /><span>Reports</span></a>
    </c:if>
    <c:if test="${dashboard.admin}"><a href="${pageContext.request.contextPath}/app?serviceId=${dashboard.serviceId}&amp;view=administration" class="${workspaceView == 'administration' ? 'nav-active' : ''}" ${workspaceView == 'administration' ? 'aria-current="page"' : ''}><ql:icon name="settings" /><span>Administration</span></a></c:if>
  </nav>
  <div class="sidebar-bottom"><span class="avatar"><c:out value="${fn:substring(dashboard.user.name, 0, 1)}" /></span><div><strong><c:out value="${dashboard.user.name}" /></strong><small><c:out value="${dashboard.user.role}" /></small></div><form method="post" action="${pageContext.request.contextPath}/logout"><input type="hidden" name="csrf" value="<c:out value='${csrf}'/>"><button class="link-button" type="submit">Sign out</button></form></div>
</aside>
<main class="main">
  <header class="topbar workspace-toolbar">
  <c:if test="${workspaceView != 'notifications' && not empty dashboard.services}">
    <form class="service-picker" action="${pageContext.request.contextPath}/app" method="get" aria-label="Choose a service">
      <input type="hidden" name="view" value="${workspaceView}" />
      <label for="selected-service">Service</label>
      <div class="service-picker-controls"><select id="selected-service" name="serviceId"><c:forEach items="${dashboard.services}" var="service"><option value="${service.id}" ${service.id == dashboard.serviceId ? 'selected' : ''}><c:out value="${service.name}" /></option></c:forEach></select><button type="submit">Switch service</button></div>
    </form>
  </c:if>
  <c:if test="${workspaceView == 'notifications'}"><span class="scope-pill">All services</span></c:if>
  <div class="snapshot-controls"><span class="connection">Last updated <time data-instant="<c:out value='${dashboard.serverTime}'/>"><c:out value="${dashboard.serverTime}" /></time></span><a class="refresh-link" data-refresh-link href="${pageContext.request.contextPath}/app?serviceId=${dashboard.serviceId}&amp;view=${workspaceView}"><ql:icon name="refresh" />Refresh status</a></div>
  </header>
  <div class="page-heading"><div><p class="eyebrow">YOUR WORKSPACE</p><h1><c:out value="${pageTitle}" /></h1><p class="muted"><c:out value="${pageDescription}" /></p></div></div>
  <c:if test="${not empty notice}"><div class="notice" role="status" data-flash><c:out value="${notice}" /></div></c:if>
  <c:choose>
    <c:when test="${workspaceView == 'notifications'}"><jsp:include page="workspace/notifications.jsp" /></c:when>
    <c:when test="${workspaceView == 'administration' && dashboard.admin}"><jsp:include page="admin.jsp" /></c:when>
    <c:when test="${dashboard.serviceId == 0}"><section class="panel"><p class="empty-state">No services are available yet. Please check back later.</p></section></c:when>
    <c:when test="${workspaceView == 'overview'}"><jsp:include page="workspace/overview.jsp" /></c:when>
    <c:when test="${workspaceView == 'visits'}"><jsp:include page="workspace/visits.jsp" /></c:when>
    <c:when test="${workspaceView == 'appointments'}"><jsp:include page="workspace/appointments.jsp" /></c:when>
    <c:when test="${workspaceView == 'counters' && dashboard.operator}"><jsp:include page="workspace/counters.jsp" /></c:when>
    <c:when test="${workspaceView == 'reports' && dashboard.operator}"><jsp:include page="workspace/reports.jsp" /></c:when>
  </c:choose>
  <footer>QueueLess · Service time in Asia/Kolkata · Status refreshes while this page is idle.</footer>
</main></body></html>
