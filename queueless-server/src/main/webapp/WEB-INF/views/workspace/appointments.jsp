<%@ page contentType="text/html; charset=UTF-8" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fn" uri="jakarta.tags.functions" %>
<%@ taglib prefix="ql" tagdir="/WEB-INF/tags" %>
<section id="appointments">
  <section id="available-windows" class="panel" aria-labelledby="arrival-windows-title">
    <div class="section-heading">
      <div><p class="eyebrow">PLAN YOUR VISIT</p><h2 id="arrival-windows-title">${dashboard.user.role == 'ADMIN' ? 'Arrival windows' : 'Available arrival windows'}</h2></div>
    </div>
    <p class="hint">An appointment reserves an arrival window. Service may begin later, depending on the queue and available counters.</p>
    <c:if test="${dashboard.user.role == 'ADMIN'}"><p class="hint">Full or closed windows may remain visible for administration. Availability is checked when you book.</p></c:if>
    <jsp:include page="../calendar.jsp" />
    <c:choose>
      <c:when test="${empty dashboard.slots}">
        <div class="empty-state">
          <p>${dashboard.user.role == 'ADMIN' ? 'No upcoming arrival windows are listed.' : 'No appointment windows are available to book right now.'}</p>
          <p>Windows may not have been published, may be full, or may be unavailable under this service's hours or closures.</p>
          <a class="button" href="${pageContext.request.contextPath}/app?serviceId=${dashboard.serviceId}&amp;view=visits">Check walk-in availability</a>
        </div>
      </c:when>
      <c:otherwise>
        <div class="slot-grid">
          <c:forEach items="${dashboard.slots}" var="slot">
            <article class="slot-card">
              <time data-instant="<c:out value='${slot.startsAt}'/>"><c:out value="${slot.startsAt}" /></time>
              <small>Until <time data-instant="<c:out value='${slot.endsAt}'/>"><c:out value="${slot.endsAt}" /></time></small>
              <p>${slot.capacity - slot.reserved} of ${slot.capacity} reservations available</p>
              <c:if test="${slot.reserved < slot.capacity}"><ql:command operation="BOOK" serviceId="${dashboard.serviceId}" entityId="${slot.id}" label="Book this window" /></c:if>
              <c:if test="${slot.reserved >= slot.capacity}"><span class="hint">Fully reserved</span></c:if>
            </article>
          </c:forEach>
        </div>
      </c:otherwise>
    </c:choose>
  </section>
  <section class="panel" aria-labelledby="bookings-title">
    <div class="section-heading">
      <h2 id="bookings-title">${dashboard.operator ? 'Service appointments' : 'Your bookings'}</h2>
      <div class="section-heading-actions"><a class="button" href="${pageContext.request.contextPath}/appointment-history?serviceId=${dashboard.serviceId}">Full appointment history</a></div>
    </div>
    <p class="hint">${dashboard.operator ? 'Upcoming appointments and recent outcomes for this service.' : 'Your upcoming appointments and recent outcomes.'}</p>
    <c:choose>
      <c:when test="${empty dashboard.appointments}">
        <div class="empty-state">
          <p>${dashboard.operator ? 'No appointments are currently listed for this service.' : 'You have no bookings listed for this service.'}</p>
          <p>Review the arrival windows above to plan a visit. Older bookings remain in your appointment history.</p>
          <a class="button" href="#available-windows">Review arrival windows</a>
        </div>
      </c:when>
      <c:otherwise>
        <div class="table-scroll">
          <table>
            <thead><tr><th>Appointment</th><th>Window start</th><th>Status</th><th>Next step</th></tr></thead>
            <tbody>
              <c:forEach items="${dashboard.appointments}" var="booking">
                <tr>
                  <td>#${booking.id}</td>
                  <td><time data-instant="<c:out value='${booking.startsAt}'/>"><c:out value="${booking.startsAt}" /></time></td>
                  <td><ql:status value="${booking.state}" /></td>
                  <td>
                    <c:if test="${booking.state == 'BOOKED'}">
                      <div class="actions">
                        <ql:command operation="CHECK_IN" serviceId="${booking.serviceId}" entityId="${booking.id}" expectedVersion="${booking.version}" label="Check in" />
                        <ql:command operation="CANCEL_APPOINTMENT" serviceId="${booking.serviceId}" entityId="${booking.id}" expectedVersion="${booking.version}" label="Cancel booking" />
                      </div>
                      <details>
                        <summary>Reschedule</summary>
                        <c:set var="rescheduleOptions" value="0" />
                        <c:forEach items="${dashboard.slots}" var="slot">
                          <c:if test="${slot.id != booking.slotId and slot.reserved < slot.capacity}"><c:set var="rescheduleOptions" value="${rescheduleOptions + 1}" /></c:if>
                        </c:forEach>
                        <c:choose>
                          <c:when test="${rescheduleOptions > 0}">
                            <ql:command operation="RESCHEDULE" serviceId="${booking.serviceId}" entityId="${booking.id}" expectedVersion="${booking.version}" label="Move appointment">
                              <label>New window
                                <select name="slotId" required>
                                  <c:forEach items="${dashboard.slots}" var="slot">
                                    <c:if test="${slot.id != booking.slotId and slot.reserved < slot.capacity}">
                                      <option value="${slot.id}" data-instant="<c:out value='${slot.startsAt}'/>"><c:out value="${slot.startsAt}" /></option>
                                    </c:if>
                                  </c:forEach>
                                </select>
                              </label>
                            </ql:command>
                          </c:when>
                          <c:otherwise><p class="hint">No alternative windows are currently available.</p></c:otherwise>
                        </c:choose>
                      </details>
                    </c:if>
                    <c:if test="${not empty booking.visitId}"><a href="${pageContext.request.contextPath}/app?serviceId=${booking.serviceId}&amp;view=visits">View visit reference #${booking.visitId}</a></c:if>
                    <c:if test="${booking.state != 'BOOKED' and empty booking.visitId}"><span class="hint">No further action</span></c:if>
                  </td>
                </tr>
              </c:forEach>
            </tbody>
          </table>
        </div>
      </c:otherwise>
    </c:choose>
  </section>
</section>
