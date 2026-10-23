<%@ page contentType="text/html; charset=UTF-8" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<c:set var="activeVisits" value="0" />
<c:set var="activeAppointments" value="0" />
<c:set var="unread" value="0" />
<c:forEach items="${dashboard.visits}" var="visit">
  <c:if test="${visit.state == 'WAITING' || visit.state == 'CALLED' || visit.state == 'SERVING' || visit.state == 'SKIPPED'}">
    <c:set var="activeVisits" value="${activeVisits + 1}" />
  </c:if>
</c:forEach>
<c:forEach items="${dashboard.appointments}" var="booking">
  <c:if test="${booking.state == 'BOOKED' || booking.state == 'CHECKED_IN'}">
    <c:set var="activeAppointments" value="${activeAppointments + 1}" />
  </c:if>
</c:forEach>
<c:forEach items="${dashboard.notifications}" var="notification">
  <c:if test="${not notification.read}"><c:set var="unread" value="${unread + 1}" /></c:if>
</c:forEach>
<section id="overview">
  <p class="overview-scope">${dashboard.operator ? 'Visits and appointments for' : 'Your visits and appointments at'} <strong><c:out value="${dashboard.selectedService.name}" /></strong>. Your inbox includes updates across all services.</p>
  <div class="overview-grid">
    <a class="overview-card" href="${pageContext.request.contextPath}/app?serviceId=${dashboard.serviceId}&amp;view=visits">
      <span class="eyebrow">QUEUE &amp; VISITS</span>
      <strong>${activeVisits}</strong>
      <span>${dashboard.operator ? 'Active service visits' : 'Your active visits'}</span>
      <c:if test="${activeVisits == 0}">
        <span class="overview-card-help">${dashboard.operator ? 'No active visits at this service. Review recent visits or check walk-in availability.' : 'No active visit. Check working hours and walk-in availability before joining.'}</span>
      </c:if>
      <span class="overview-card-action">${activeVisits == 0 ? 'Check walk-in availability' : 'View visits'} <span aria-hidden="true">→</span></span>
    </a>
    <a class="overview-card" href="${pageContext.request.contextPath}/app?serviceId=${dashboard.serviceId}&amp;view=appointments">
      <span class="eyebrow">APPOINTMENTS</span>
      <strong>${activeAppointments}</strong>
      <span>${dashboard.operator ? 'Active service appointments' : 'Your active appointments'}</span>
      <c:if test="${activeAppointments == 0}">
        <span class="overview-card-help">${dashboard.operator ? 'No active appointments at this service. Review arrival windows and booking history.' : 'No active appointment. Browse arrival windows to plan your next visit.'}</span>
      </c:if>
      <span class="overview-card-action">${activeAppointments == 0 ? 'Browse arrival windows' : 'Manage appointments'} <span aria-hidden="true">→</span></span>
    </a>
    <a class="overview-card" href="${pageContext.request.contextPath}/app?serviceId=${dashboard.serviceId}&amp;view=notifications">
      <span class="eyebrow">NOTIFICATIONS</span>
      <strong>${unread}</strong>
      <span>Your unread updates across services</span>
      <c:if test="${unread == 0}"><span class="overview-card-help">You are up to date. Your inbox keeps earlier updates for reference.</span></c:if>
      <span class="overview-card-action">${unread == 0 ? 'View previous updates' : 'Read updates'} <span aria-hidden="true">→</span></span>
    </a>
  </div>
</section>
