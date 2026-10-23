<%@ tag body-content="scriptless" pageEncoding="UTF-8" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ attribute name="operation" required="true" type="java.lang.String" %>
<%@ attribute name="label" required="true" type="java.lang.String" %>
<%@ attribute name="serviceId" required="false" type="java.lang.Long" %>
<%@ attribute name="entityId" required="false" type="java.lang.Long" %>
<%@ attribute name="counterId" required="false" type="java.lang.Long" %>
<%@ attribute name="expectedVersion" required="false" type="java.lang.Long" %>
<%@ attribute name="claimVersion" required="false" type="java.lang.Long" %>
<form action="${pageContext.request.contextPath}/command" method="post" class="command-form">
  <input type="hidden" name="csrf" value="<c:out value='${requestScope.csrf}'/>" />
  <input type="hidden" name="requestId" value="${requestScope.ids.next}" />
  <input type="hidden" name="operation" value="<c:out value='${operation}'/>" />
  <input type="hidden" name="serviceId" value="${empty serviceId ? 0 : serviceId}" />
  <input type="hidden" name="entityId" value="${empty entityId ? 0 : entityId}" />
  <input type="hidden" name="counterId" value="${empty counterId ? 0 : counterId}" />
  <input type="hidden" name="expectedVersion" value="${empty expectedVersion ? 0 : expectedVersion}" />
  <input type="hidden" name="claimVersion" value="${empty claimVersion ? 0 : claimVersion}" />
  <jsp:doBody />
  <button type="submit"><c:out value="${label}" /></button>
</form>
