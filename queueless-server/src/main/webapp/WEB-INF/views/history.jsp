<%@ page contentType="text/html; charset=UTF-8" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="ql" tagdir="/WEB-INF/tags" %>
<!doctype html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width,initial-scale=1">
  <title>${appointmentHistory ? 'Appointment' : 'Visit'} history · QueueLess</title>
  <link rel="stylesheet" href="${pageContext.request.contextPath}/assets/app.css">
  <script src="${pageContext.request.contextPath}/assets/app.js" defer></script>
</head>
<body>
<main class="history-layout">
  <a class="brand" href="${pageContext.request.contextPath}/app?serviceId=${serviceId}&amp;view=${appointmentHistory ? 'appointments' : 'visits'}"><span class="brand-mark">Q</span> QueueLess</a>
  <section class="panel">
    <div class="section-heading">
      <h1>${appointmentHistory ? 'Appointment' : 'Visit'} history</h1>
      <a href="${pageContext.request.contextPath}/app?serviceId=${serviceId}&amp;view=${appointmentHistory ? 'appointments' : 'visits'}">Back to ${appointmentHistory ? 'appointments' : 'queue &amp; visits'}</a>
    </div>
    <p class="hint">Showing up to 50 records, newest first. Use Older records to continue through the full history.</p>
    <div class="table-scroll">
      <table>
        <thead><tr><th>${appointmentHistory ? 'Reference' : 'Token / reference'}</th><th>${appointmentHistory ? 'Window start' : 'Joined'}</th><th>Status</th><th>Details</th></tr></thead>
        <tbody>
          <c:forEach items="${history.items}" var="item">
            <tr>
              <td>
                <c:choose>
                  <c:when test="${appointmentHistory}">Appointment #${item.id}</c:when>
                  <c:otherwise><ql:token value="${item.token}" visitId="${item.id}" /></c:otherwise>
                </c:choose>
              </td>
              <td><time data-instant="<c:out value='${appointmentHistory ? item.startsAt : item.createdAt}'/>"><c:out value="${appointmentHistory ? item.startsAt : item.createdAt}" /></time></td>
              <td><ql:status value="${item.state}" /></td>
              <td>
                <c:choose>
                  <c:when test="${appointmentHistory}">Window #${item.slotId}<c:if test="${not empty item.visitId}"> · Visit reference #${item.visitId}</c:if></c:when>
                  <c:otherwise>${item.source == 'WALK_IN' ? 'Walk-in' : 'Appointment'}</c:otherwise>
                </c:choose>
              </td>
            </tr>
          </c:forEach>
          <c:if test="${empty history.items}"><tr><td colspan="4" class="empty-state">No records on this page.</td></tr></c:if>
        </tbody>
      </table>
    </div>
    <div class="history-actions">
      <a class="button" href="${pageContext.request.contextPath}/${appointmentHistory ? 'appointment-history' : 'history'}?serviceId=${serviceId}">Newest records</a>
      <c:if test="${history.nextBeforeId > 0}"><a class="button primary" href="${pageContext.request.contextPath}/${appointmentHistory ? 'appointment-history' : 'history'}?serviceId=${serviceId}&amp;beforeId=${history.nextBeforeId}">Older records →</a></c:if>
    </div>
  </section>
</main>
</body>
</html>
