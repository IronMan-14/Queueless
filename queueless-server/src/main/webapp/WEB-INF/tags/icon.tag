<%@ tag body-content="empty" pageEncoding="UTF-8" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ attribute name="name" required="true" type="java.lang.String" %>
<svg class="ui-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false">
<c:choose>
  <c:when test="${name == 'overview'}"><rect x="3" y="3" width="7" height="7" rx="1"/><rect x="14" y="3" width="7" height="7" rx="1"/><rect x="3" y="14" width="7" height="7" rx="1"/><rect x="14" y="14" width="7" height="7" rx="1"/></c:when>
  <c:when test="${name == 'visits'}"><path d="M8 6h13M8 12h13M8 18h13"/><circle cx="3" cy="6" r="1"/><circle cx="3" cy="12" r="1"/><circle cx="3" cy="18" r="1"/></c:when>
  <c:when test="${name == 'calendar'}"><rect x="3" y="5" width="18" height="16" rx="2"/><path d="M16 3v4M8 3v4M3 11h18M8 16h3"/></c:when>
  <c:when test="${name == 'bell'}"><path d="M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9M10 21h4"/></c:when>
  <c:when test="${name == 'counter'}"><path d="M3 21V11h18v10M3 16h18M8 21v-5M16 21v-5"/><circle cx="12" cy="5" r="3"/></c:when>
  <c:when test="${name == 'chart'}"><path d="M3 3v18h18M8 16v-4M13 16V8M18 16V5"/></c:when>
  <c:when test="${name == 'settings'}"><path d="M4 6h16M4 12h16M4 18h16M8 3v6M16 9v6M10 15v6"/></c:when>
  <c:when test="${name == 'refresh'}"><path d="M20 7a9 9 0 1 0 1 8M20 3v5h-5"/></c:when>
  <c:when test="${name == 'success' || name == 'check'}"><circle cx="12" cy="12" r="9"/><path d="m8 12 3 3 5-6"/></c:when>
  <c:when test="${name == 'cancelled' || name == 'cancel'}"><circle cx="12" cy="12" r="9"/><path d="m9 9 6 6m0-6-6 6"/></c:when>
  <c:when test="${name == 'reminder' || name == 'clock'}"><circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/></c:when>
  <c:when test="${name == 'warning'}"><path d="m12 3 10 18H2L12 3Z M12 9v5M12 17h.01"/></c:when>
  <c:otherwise><circle cx="12" cy="12" r="9"/><path d="M12 11v6M12 7h.01"/></c:otherwise>
</c:choose>
</svg>
