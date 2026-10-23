<%@ tag body-content="empty" pageEncoding="UTF-8" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fn" uri="jakarta.tags.functions" %>
<%@ attribute name="value" required="true" type="java.lang.String" %>
<c:set var="tone" value="neutral" />
<c:choose>
  <c:when test="${value == 'COMPLETED' || value == 'FULFILLED'}"><c:set var="tone" value="success" /></c:when>
  <c:when test="${value == 'CALLED' || value == 'SERVING' || value == 'CHECKED_IN'}"><c:set var="tone" value="active" /></c:when>
  <c:when test="${value == 'CANCELLED' || value == 'NO_SHOW' || value == 'ABORTED'}"><c:set var="tone" value="muted" /></c:when>
  <c:when test="${value == 'SKIPPED'}"><c:set var="tone" value="warning" /></c:when>
</c:choose>
<c:set var="words" value="${fn:toLowerCase(fn:replace(value, '_', ' '))}" />
<span class="status status-${tone}"><c:out value="${fn:toUpperCase(fn:substring(words, 0, 1))}${fn:substring(words, 1, fn:length(words))}" /></span>
