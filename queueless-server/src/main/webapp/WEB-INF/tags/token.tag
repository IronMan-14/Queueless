<%@ tag body-content="empty" pageEncoding="UTF-8" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fn" uri="jakarta.tags.functions" %>
<%@ attribute name="value" required="true" type="java.lang.String" %>
<%@ attribute name="visitId" required="true" type="java.lang.Long" %>
<span class="visit-token">
  <c:choose>
    <c:when test="${not empty value and value.matches('[0-9]{4}-[0-9]{2}-[0-9]{2}/[0-9]+-[0-9]+')}">
      <c:set var="serviceToken" value="${fn:substringAfter(value, '/')}" />
      <strong>Token #<c:out value="${fn:substringAfter(serviceToken, '-')}" /></strong>
      <small class="token-context"><c:out value="${fn:substringBefore(value, '/')}" /> · Service <c:out value="${fn:substringBefore(serviceToken, '-')}" /></small>
      <small class="token-code">Code <c:out value="${value}" /></small>
    </c:when>
    <c:otherwise><strong><c:out value="${value}" default="Token unavailable" /></strong></c:otherwise>
  </c:choose>
  <small>Visit reference #<c:out value="${visitId}" /></small>
</span>
