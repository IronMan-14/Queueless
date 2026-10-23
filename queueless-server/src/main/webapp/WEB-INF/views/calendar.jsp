<%@ page contentType="text/html; charset=UTF-8" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<details class="calendar-details">
  <summary>Working hours &amp; closures</summary>
  <p class="hint">All hours are in Asia/Kolkata. Unlisted weekdays are closed. Temporary date closures override weekly hours.</p>
  <c:choose>
    <c:when test="${empty hours}"><p class="hint">No weekly opening hours are listed.</p></c:when>
    <c:otherwise>
      <div class="table-scroll">
        <table>
          <thead><tr><th>Day</th><th>Opens</th><th>Closes</th></tr></thead>
          <tbody>
            <c:forEach items="${hours}" var="hoursRow">
              <tr>
                <td>
                  <c:choose>
                    <c:when test="${hoursRow.day == 1}">Monday</c:when>
                    <c:when test="${hoursRow.day == 2}">Tuesday</c:when>
                    <c:when test="${hoursRow.day == 3}">Wednesday</c:when>
                    <c:when test="${hoursRow.day == 4}">Thursday</c:when>
                    <c:when test="${hoursRow.day == 5}">Friday</c:when>
                    <c:when test="${hoursRow.day == 6}">Saturday</c:when>
                    <c:otherwise>Sunday</c:otherwise>
                  </c:choose>
                </td>
                <td><c:out value="${hoursRow.opens}" /></td>
                <td><c:out value="${hoursRow.closes}" /></td>
              </tr>
            </c:forEach>
          </tbody>
        </table>
      </div>
    </c:otherwise>
  </c:choose>
  <h3>Upcoming closures (up to 100)</h3>
  <ul>
    <c:forEach items="${closures}" var="closure"><li><strong><c:out value="${closure.date}" /></strong> — <c:out value="${closure.reason}" /></li></c:forEach>
    <c:if test="${empty closures}"><li>No date closures are listed.</li></c:if>
  </ul>
</details>
