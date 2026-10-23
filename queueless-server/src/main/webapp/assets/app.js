'use strict';
(() => {
  // Preserve bookmarked links from the former scrolling workspace.
  const pageUrl = new URL(window.location.href);
  const legacyView = pageUrl.hash.slice(1);
  if (document.body.dataset.workspaceView && !pageUrl.searchParams.has('view')
      && ['overview', 'visits', 'appointments', 'notifications', 'counters', 'reports', 'administration'].includes(legacyView)) {
    pageUrl.searchParams.set('view', legacyView); pageUrl.hash = '';
    window.location.replace(pageUrl.href); return;
  }
  const formatter = new Intl.DateTimeFormat('en-IN', { timeZone: 'Asia/Kolkata', dateStyle: 'medium', timeStyle: 'short' });
  for (const element of document.querySelectorAll('[data-instant]')) {
    const date = new Date(element.dataset.instant);
    if (!Number.isNaN(date.getTime())) { element.textContent = formatter.format(date); element.dateTime = date.toISOString(); }
  }
  let edited = false;
  let draftEdited = false;
  let submitting = false;
  let commandInFlight = false;
  let pending = null;
  let confirming = false;
  let feedbackUntil = 0;
  const feedback = document.createElement('aside');
  feedback.className = 'action-feedback'; feedback.hidden = true;
  const feedbackText = document.createElement('div');
  feedbackText.setAttribute('role', 'status'); feedbackText.setAttribute('aria-live', 'polite');
  const feedbackTitle = document.createElement('strong');
  const feedbackMessage = document.createElement('p');
  feedbackText.append(feedbackTitle, feedbackMessage);
  const dismiss = document.createElement('button');
  dismiss.type = 'button'; dismiss.className = 'feedback-dismiss'; dismiss.textContent = '×'; dismiss.setAttribute('aria-label', 'Dismiss message');
  dismiss.addEventListener('click', () => { feedback.hidden = true; feedbackUntil = 0; });
  feedback.append(feedbackText, dismiss); document.body.append(feedback);
  const showFeedback = (message, kind = 'success') => {
    feedback.className = 'action-feedback ' + kind;
    feedbackText.setAttribute('role', kind === 'error' ? 'alert' : 'status');
    feedbackText.setAttribute('aria-live', kind === 'error' ? 'assertive' : 'polite');
    feedbackTitle.textContent = kind === 'error' ? 'Action needs your attention' : kind === 'busy' ? 'Please wait' : 'All set';
    feedbackTitle.hidden = kind === 'success';
    feedbackMessage.textContent = message;
    feedback.hidden = false; dismiss.hidden = kind === 'busy';
    feedbackUntil = Date.now() + 30000;
  };
  const flash = document.querySelector('[data-flash]');
  if (flash) { showFeedback(flash.textContent.trim()); flash.hidden = true; }
  const confirmations = {
    CANCEL_VISIT: ['Leave this queue?', 'Your current place will be cancelled. If you join again, you will receive a new place in line.', 'Leave queue'],
    CANCEL_APPOINTMENT: ['Cancel this appointment?', 'Your reserved place will be released. You can book again if a window is available.', 'Cancel appointment'],
    ABORT: ['End this service?', 'This visit will be recorded as aborted with the reason you entered.', 'End service'],
    TAKEOVER: ['Take over this counter?', 'The current operator will lose control of this counter. Any visit in progress stays at the counter.', 'Take over']
  };
  const confirmPrompt = async content => {
    confirming = true;
    const previousFocus = document.activeElement;
    const dialog = document.createElement('dialog'); dialog.className = 'confirm-dialog'; dialog.setAttribute('aria-labelledby', 'confirm-title'); dialog.setAttribute('aria-describedby', 'confirm-copy');
    const title = document.createElement('h2'); title.id = 'confirm-title'; title.textContent = content[0];
    const copy = document.createElement('p'); copy.id = 'confirm-copy'; copy.textContent = content[1];
    const actions = document.createElement('div'); actions.className = 'actions';
    const keep = document.createElement('button'); keep.type = 'button'; keep.textContent = content[3] || 'Go back'; keep.autofocus = true;
    const proceed = document.createElement('button'); proceed.type = 'button'; proceed.className = 'danger'; proceed.textContent = content[2];
    keep.addEventListener('click', () => dialog.close('back'));
    proceed.addEventListener('click', () => dialog.close('confirm'));
    actions.append(keep, proceed); dialog.append(title, copy, actions); document.body.append(dialog);
    const accepted = await new Promise(resolve => { dialog.addEventListener('close', () => resolve(dialog.returnValue === 'confirm'), { once: true }); dialog.showModal(); });
    dialog.remove(); confirming = false; previousFocus?.focus(); return accepted;
  };
  const confirmAction = operation => confirmations[operation] ? confirmPrompt(confirmations[operation]) : Promise.resolve(true);
  const progressLabels = { JOIN: 'Joining the queue…', BOOK: 'Booking your appointment…', CHECK_IN: 'Checking you in…', CANCEL_VISIT: 'Leaving the queue…', CANCEL_APPOINTMENT: 'Cancelling your appointment…', READ_NOTIFICATION: 'Marking notification read…', READ_NOTIFICATIONS: 'Marking notifications read…', RESCHEDULE: 'Moving your appointment…', NEXT: 'Calling the next visitor…' };
  const commands = [...document.forms].filter(form => new URL(form.action).pathname.endsWith('/command'));
  const navigationButtons = [...document.forms].filter(form => !commands.includes(form))
    .flatMap(form => [...form.querySelectorAll('button[type="submit"]')])
    .map(button => ({ button, label: button.textContent, disabled: button.disabled }));
  window.addEventListener('pageshow', event => {
    if (event.persisted && !pending && !commandInFlight) {
      submitting = false;
      for (const saved of navigationButtons) { saved.button.disabled = saved.disabled; saved.button.textContent = saved.label; }
      if (feedback.classList.contains('busy')) feedback.hidden = true;
    }
  });
  const originalDisabled = new WeakMap();
  const originalLabels = new WeakMap();
  for (const form of commands) for (const control of form.elements) {
    originalDisabled.set(control, control.disabled);
    if (control.tagName === 'BUTTON') originalLabels.set(control, control.textContent);
  }
  const lockCommands = (retryForm = null) => {
    for (const form of commands) for (const control of form.elements) {
      control.disabled = retryForm === null ? originalDisabled.get(control) : !(form === retryForm && control.tagName === 'BUTTON' && control.type === 'submit');
      if (control.tagName === 'BUTTON' && originalLabels.has(control)) control.textContent = form === retryForm ? 'Retry same request' : originalLabels.get(control);
    }
  };
  const notice = (form, message) => {
    let element = form.querySelector('.request-error');
    if (!element) { element = document.createElement('p'); element.className = 'notice error request-error'; element.setAttribute('role', 'alert'); form.append(element); }
    element.textContent = message;
    showFeedback(message, 'error');
    return element;
  };
  const recordEdit = event => {
    edited = true;
    if (!event.target.closest('.service-picker')) draftEdited = true;
  };
  document.addEventListener('input', recordEdit);
  document.addEventListener('change', recordEdit);
  const navigationBlocked = () => {
    if (pending || commandInFlight) {
      showFeedback('Please finish or retry the pending action before leaving this page. Its request details are kept here so you can safely check the outcome.', 'error');
      return true;
    }
    return submitting || confirming;
  };
  const confirmLeave = refreshing => confirmPrompt([
    refreshing ? 'Refresh this page?' : 'Leave this page?',
    'Your unsaved changes will be lost. You can keep editing or continue.',
    refreshing ? 'Refresh page' : 'Leave page', 'Keep editing'
  ]);
  document.addEventListener('click', async event => {
    const link = event.target.closest('a[href]');
    if (!link || event.defaultPrevented || event.button !== 0 || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey
        || link.target === '_blank' || link.hasAttribute('download')) return;
    const target = new URL(link.href);
    if (target.origin !== window.location.origin) return;
    // In-page links reveal the booking/admission section without discarding the current request.
    if (target.pathname === window.location.pathname && target.search === window.location.search && target.hash) return;
    if (navigationBlocked()) { event.preventDefault(); return; }
    const refreshing = link.hasAttribute('data-refresh-link');
    if (refreshing) {
      event.preventDefault();
      if (edited && !await confirmLeave(true)) return;
      // Dropping a section fragment alone does not fetch a fresh server snapshot.
      window.history.replaceState(null, '', target.href);
      window.location.reload();
      return;
    }
    if (draftEdited) {
      event.preventDefault();
      if (await confirmLeave(refreshing)) window.location.assign(target.href);
    }
  });
  window.addEventListener('beforeunload', event => {
    if (pending || commandInFlight) { event.preventDefault(); event.returnValue = ''; }
  });
  for (const form of document.forms) {
    form.addEventListener('formdata', event => {
      for (const input of form.querySelectorAll('[data-india-time]')) if (input.value) event.formData.set(input.name, new Date(input.value + '+05:30').toISOString());
      if (event.formData.get('operation') === 'UPDATE_SLOT') event.formData.set('entityId', event.formData.get('slotId'));
    });
    form.addEventListener('submit', async event => {
      if (!commands.includes(form)) {
        if (form.method.toLowerCase() === 'get') {
          if (navigationBlocked()) { event.preventDefault(); return; }
          if (draftEdited) {
            event.preventDefault();
            if (await confirmLeave(false)) HTMLFormElement.prototype.submit.call(form);
          }
          return;
        }
        if (form.method.toLowerCase() === 'post') {
          if (pending) {
            event.preventDefault();
            notice(pending.form, 'Please retry the pending action before signing out. If you need to sign in again, use another tab so this request stays available.');
            return;
          }
          if (submitting || confirming) { event.preventDefault(); return; }
          submitting = true;
          const action = new URL(form.action).pathname.split('/').pop();
          const label = action === 'login' ? 'Signing in…' : action === 'register' ? 'Creating your account…' : 'Signing out…';
          for (const button of form.querySelectorAll('button[type="submit"]')) { button.disabled = true; button.textContent = label; }
          showFeedback(label, 'busy');
        }
        return;
      }
      event.preventDefault();
      if (submitting || confirming || (pending && pending.form !== form)) return;
      const operation = form.elements.namedItem('operation').value;
      if (!pending && !await confirmAction(operation)) return;
      // Serialize once. On an uncertain outcome, this exact body stays frozen.
      const request = pending || { form, body: new URLSearchParams(new FormData(form)).toString(), url: form.action, actorId: Number(document.body.dataset.userId) };
      const wasUncertain = pending !== null;
      submitting = true;
      commandInFlight = true;
      for (const commandForm of commands) for (const control of commandForm.elements) control.disabled = true;
      const progress = pending ? 'Checking the previous outcome…' : progressLabels[operation] || 'Saving your changes…';
      for (const button of form.querySelectorAll('button[type="submit"]')) button.textContent = progress;
      form.querySelector('.request-error')?.remove();
      showFeedback(progress, 'busy');
      const controller = new AbortController();
      const timeout = window.setTimeout(() => controller.abort(), 20000);
      try {
        let transportBody = request.body;
        if (wasUncertain) {
          const sessionResponse = await fetch(new URL('api/session', request.url), { credentials: 'same-origin', signal: controller.signal });
          if (!sessionResponse.ok) throw new Error('Cannot recover session');
          const session = await sessionResponse.json();
          if (!session.user || session.user.id !== request.actorId) {
            const message = notice(form, 'Sign in as the original account in another tab, then return here and retry the same request. Its details remain locked. ');
            const link = document.createElement('a'); link.href = new URL('login', request.url).href; link.target = '_blank'; link.rel = 'noopener'; link.textContent = 'Sign in again'; message.append(link);
            return;
          }
          // Only the transport CSRF token changes after login; the request key and command fields stay fixed.
          const recovered = new URLSearchParams(request.body); recovered.set('csrf', session.csrfToken); transportBody = recovered.toString();
        }
        const response = await fetch(request.url, { method: 'POST', headers: { 'Accept': 'application/json', 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8' }, body: transportBody, signal: controller.signal, credentials: 'same-origin' });
        if (response.ok && (response.headers.get('Content-Type') || '').includes('application/json')) {
          const result = await response.json();
          const target = new URL(result.redirect, window.location.origin);
          if (target.origin !== window.location.origin) throw new Error('Invalid redirect');
          pending = null; commandInFlight = false;
          // A fragment-only navigation does not fetch the updated JSP or consume its flash.
          if (target.pathname === window.location.pathname && target.search === window.location.search) {
            window.history.replaceState(null, '', target.href);
            window.location.reload();
          } else window.location.assign(target.href);
          return;
        }
        const content = await response.text();
        let message;
        if ((response.headers.get('Content-Type') || '').includes('application/json')) {
          message = JSON.parse(content).message;
        } else {
          const html = new DOMParser().parseFromString(content, 'text/html');
          message = html.querySelector('[role="alert"]')?.textContent;
        }
        message = message || 'The request could not be completed. Please refresh the page and try again.';
        if (response.status >= 500 || (wasUncertain && [401, 403].includes(response.status))) {
          pending = request;
          notice(form, message + ' Retry this same request before starting another command. Its details are locked.');
        } else {
          pending = null;
          notice(form, message);
        }
        edited = true;
      } catch (error) {
        pending = request; edited = true;
        notice(form, 'The connection was interrupted. Retry this same request to establish its outcome. Its details and other commands are locked until then.');
      } finally {
        window.clearTimeout(timeout); submitting = false; commandInFlight = false;
        lockCommands(pending ? pending.form : null);
      }
    });
  }
  if (document.body.dataset.refresh === 'true') window.setInterval(() => {
    if (!edited && !submitting && !pending && !confirming && Date.now() > feedbackUntil && document.visibilityState === 'visible' && !document.querySelector('details[open]')) window.location.reload();
  }, 15000);
})();
