<%@ page contentType="text/html; charset=UTF-8" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<!doctype html><html lang="en"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>${register ? 'Create an account' : 'Sign in'} · QueueLess</title><link rel="stylesheet" href="${pageContext.request.contextPath}/assets/app.css"><script src="${pageContext.request.contextPath}/assets/app.js" defer></script></head>
<body class="auth-page"><main class="auth-layout">
  <section class="auth-intro"><a class="brand" href="${pageContext.request.contextPath}/"><span class="brand-mark">Q</span> QueueLess</a><div><p class="eyebrow">YOUR TIME, BETTER SPENT</p><h1>A place in line. <br>Room to get on <br>with your day.</h1><p class="intro-copy">Join a service queue, reserve an appointment and follow your visit from one place.</p></div><p class="small">Connected services. Clear next steps.</p></section>
  <section class="auth-card"><p class="eyebrow">WELCOME ${register ? 'ABOARD' : 'BACK'}</p><h2>${register ? 'Create your account' : 'Sign in to QueueLess'}</h2><p class="muted">${register ? 'Your account gives you access to queues and appointments.' : 'Continue to your services, visits and appointments.'}</p>
    <c:if test="${not empty notice}"><div class="notice" role="status" data-flash><c:out value="${notice}" /></div></c:if>
    <c:if test="${not empty error}"><div class="notice error" role="alert"><c:out value="${error}" /></div></c:if>
    <form method="post" action="${pageContext.request.contextPath}/${register ? 'register' : 'login'}" class="stack">
      <input type="hidden" name="csrf" value="<c:out value='${csrf}'/>" />
      <c:if test="${register}"><label>Full name<input name="name" maxlength="100" autocomplete="name" required></label></c:if>
      <label>Email address<input type="email" name="email" maxlength="254" autocomplete="username" required></label>
      <label>Password<input type="password" name="password" maxlength="256" minlength="${register ? 12 : 1}" autocomplete="${register ? 'new-password' : 'current-password'}" required></label>
      <c:if test="${register}"><p class="hint">Use at least 12 characters. Staff access is assigned by an administrator.</p></c:if>
      <button type="submit" class="primary wide">${register ? 'Create account' : 'Sign in'} <span aria-hidden="true">→</span></button>
    </form>
    <p class="auth-switch">${register ? 'Already have an account?' : 'New to QueueLess?'} <a href="${pageContext.request.contextPath}/${register ? 'login' : 'register'}">${register ? 'Sign in' : 'Create an account'}</a></p>
  </section>
</main></body></html>

