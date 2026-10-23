<%@ page contentType="text/html; charset=UTF-8" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fn" uri="jakarta.tags.functions" %>
<%@ taglib prefix="ql" tagdir="/WEB-INF/tags" %>
<section id="walk-in-options" class="queue-entry panel" aria-label="Join a walk-in queue">
  <div class="queue-entry-main">
    <div>
      <h2>Walk-in queue</h2>
      <p class="muted"><c:out value="${dashboard.selectedService.description}" /></p>
      <p class="hint">${dashboard.selectedService.policy == 'FIFO' ? 'First in, first out' : 'Appointments and walk-ins share this queue'}. Up to ${dashboard.selectedService.walkInLimit} active walk-in visits.</p>
      <p class="hint">${dashboard.selectedService.open ? 'Joining is subject to working hours and available space.' : 'New walk-in admissions are currently paused.'}</p>
    </div>
    <ql:command operation="JOIN" serviceId="${dashboard.serviceId}" label="Join the queue →" />
  </div>
  <jsp:include page="../calendar.jsp" />
</section>
<section id="visits" class="panel">
  <div class="section-heading">
    <div><p class="eyebrow">CURRENT &amp; RECENT</p><h2>${dashboard.operator ? 'Service visits' : 'Your visits'}</h2></div>
    <div class="section-heading-actions">
      <span class="count">${fn:length(dashboard.visits)} visits</span>
      <a class="button" href="${pageContext.request.contextPath}/history?serviceId=${dashboard.serviceId}">Full visit history</a>
    </div>
  </div>
  <p class="hint">${dashboard.operator ? 'Active service visits' : 'Your active visits'} and 100 most recent outcomes appear here. Waiting times are estimates based on recent service activity and available counters.</p>
  <c:choose>
    <c:when test="${empty dashboard.visits}">
      <div class="empty-state">
        <p>${dashboard.operator ? 'No visits are currently listed for this service.' : 'You have no visits listed for this service.'}</p>
        <p>Check working hours and walk-in availability above to join the queue.</p>
        <a class="button" href="#walk-in-options">Review walk-in options</a>
      </div>
    </c:when>
    <c:otherwise>
      <div class="table-scroll">
        <table>
          <thead><tr><th>Token</th><th>Status</th><th>Origin</th><th>Position / estimate</th><th>Next step</th></tr></thead>
          <tbody>
            <c:forEach items="${dashboard.visits}" var="visit">
              <tr>
                <td><ql:token value="${visit.token}" visitId="${visit.id}" /></td>
                <td><ql:status value="${visit.state}" /></td>
                <td>${visit.source == 'WALK_IN' ? 'Walk-in' : 'Appointment'}</td>
                <td>
                  <c:choose>
                    <c:when test="${not empty visit.position}">#${visit.position}<small>${empty visit.estimatedMinutes ? 'Estimate unavailable' : visit.estimatedMinutes += ' min approx.'}</small></c:when>
                    <c:otherwise>—</c:otherwise>
                  </c:choose>
                </td>
                <td>
                  <div class="actions">
                    <c:if test="${visit.state == 'WAITING' || visit.state == 'SKIPPED'}">
                      <ql:command operation="CANCEL_VISIT" serviceId="${dashboard.serviceId}" entityId="${visit.id}" expectedVersion="${visit.version}" label="Cancel" />
                    </c:if>
                    <c:if test="${visit.state == 'SKIPPED'}">
                      <ql:command operation="REQUEUE" serviceId="${dashboard.serviceId}" entityId="${visit.id}" expectedVersion="${visit.version}" label="I have returned" />
                    </c:if>
                    <c:if test="${visit.state == 'CALLED'}"><span class="hint">Proceed to counter ${visit.counterId}</span></c:if>
                    <c:if test="${visit.state == 'SERVING'}"><span class="hint">Service in progress</span></c:if>
                    <c:if test="${visit.state != 'WAITING' and visit.state != 'SKIPPED' and visit.state != 'CALLED' and visit.state != 'SERVING'}"><span class="hint">No further action</span></c:if>
                  </div>
                </td>
              </tr>
            </c:forEach>
          </tbody>
        </table>
      </div>
    </c:otherwise>
  </c:choose>
</section>
