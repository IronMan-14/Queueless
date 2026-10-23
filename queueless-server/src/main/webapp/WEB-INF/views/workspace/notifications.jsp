<%@ page contentType="text/html; charset=UTF-8" %><%@ taglib prefix="c" uri="jakarta.tags.core" %><%@ taglib prefix="ql" tagdir="/WEB-INF/tags" %>
<section id="notifications" class="panel" aria-label="Notification inbox">
  <div class="inbox-toolbar">
    <p class="inbox-summary"><c:choose><c:when test="${dashboard.unreadCount > 0}"><strong>${dashboard.unreadCount} unread</strong><span>New updates across all services</span></c:when><c:otherwise><strong>You're all caught up</strong><span>No unread updates</span></c:otherwise></c:choose></p>
    <c:if test="${dashboard.unreadCount > 0}">
      <c:set var="bulkLabel" value="${dashboard.unreadCount > 100 ? 'Mark 100 as read' : 'Mark all as read'}" />
      <ql:command operation="READ_NOTIFICATIONS" serviceId="${dashboard.serviceId}" label="${bulkLabel}"><input type="hidden" name="notificationIds" value="<c:out value='${dashboard.unreadNotificationIds}'/>" /></ql:command>
    </c:if>
  </div>
  <c:if test="${dashboard.unreadCount > 100}"><p class="hint">Marking the newest 100 unread updates at a time. Any updates that arrive after this page loaded stay unread.</p></c:if>
  <ul class="notification-list">
    <c:forEach items="${dashboard.notifications}" var="notification">
      <c:choose>
        <c:when test="${notification.kind == 'CANCELLED'}"><c:set var="eventIcon" value="cancelled" /></c:when>
        <c:when test="${notification.kind == 'MISSED'}"><c:set var="eventIcon" value="warning" /></c:when>
        <c:when test="${notification.kind == 'CALLED'}"><c:set var="eventIcon" value="bell" /></c:when>
        <c:when test="${notification.kind == 'SERVING'}"><c:set var="eventIcon" value="counter" /></c:when>
        <c:when test="${notification.kind == 'BOOKED' || notification.kind == 'CHANGED'}"><c:set var="eventIcon" value="calendar" /></c:when>
        <c:when test="${notification.kind == 'COMPLETED' || notification.kind == 'CHECKED_IN'}"><c:set var="eventIcon" value="success" /></c:when>
        <c:when test="${notification.kind == 'QUEUED'}"><c:set var="eventIcon" value="visits" /></c:when>
        <c:otherwise><c:set var="eventIcon" value="info" /></c:otherwise>
      </c:choose>
      <li class="notification-item ${notification.read ? 'is-read' : 'is-unread'}">
        <span class="notification-icon notification-${eventIcon}"><ql:icon name="${eventIcon}" /></span>
        <div class="notification-copy"><span class="notification-kind"><c:out value="${notification.kindLabel}" /></span><p><c:out value="${notification.message}" /></p><small><time data-instant="<c:out value='${notification.createdAt}'/>"><c:out value="${notification.createdAt}" /></time></small></div>
        <c:choose><c:when test="${notification.read}"><span class="status status-muted">Read</span></c:when><c:otherwise><ql:command operation="READ_NOTIFICATION" serviceId="${dashboard.serviceId}" entityId="${notification.id}" label="Mark read" /></c:otherwise></c:choose>
      </li>
    </c:forEach>
    <c:if test="${empty dashboard.notifications}"><li class="empty-state">Updates will appear here when you join a queue, book an appointment or receive a service update.</li></c:if>
  </ul>
</section>
