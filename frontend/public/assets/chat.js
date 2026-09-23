import { ApiError, auth, conversations, knowledge } from './api.js';
import {
  activeTransport,
  askChat,
  onFallback,
  resetTransports,
  setTransportName,
} from './chat-transport.js';

const MAX_LENGTH = 1000;
const FALLBACK_TYPES = [
  ['TROUBLESHOOTING', '장애·트러블슈팅'],
  ['TECH_DOC', '기술 문서'],
  ['PROJECT_DOC', '프로젝트 문서'],
  ['NOTE', '개발·학습 노트'],
  ['RETROSPECTIVE', '회고'],
  ['EXPERIMENT', '실험·측정 결과'],
  ['DECISION', '기술 선택 근거'],
  ['OTHER', '기타'],
];

const $ = (id) => document.getElementById(id);
const dom = {
  loginView: $('login-view'), loginForm: $('login-form'), loginError: $('login-error'),
  loginButton: $('btn-login'), username: $('username'), password: $('password'),
  chatView: $('chat-view'), navActions: $('nav-actions'), who: $('who'), logout: $('btn-logout'),
  hero: $('hero'), thread: $('thread'), live: $('live'), form: $('form'), input: $('input'),
  send: $('btn-send'), stop: $('btn-stop'), reset: $('btn-reset'), suggestions: $('suggestions'),
  count: $('count'), transport: $('btn-transport'),
  conversationNew: $('conversation-new'), conversationList: $('conversation-list'),
  conversationEmpty: $('conversation-empty'),
  scopeProjects: $('scope-projects'), scopeTechnologies: $('scope-technologies'),
  scopeTypes: $('scope-types'), scopeFrom: $('scope-from'), scopeTo: $('scope-to'),
  scopeClear: $('scope-clear'), scopeSummary: $('scope-summary'),
};

let inflight = null;
let conversationId = null;
let conversationNextBefore = null;
let loadingOlderMessages = false;
let activeName = activeTransport().name;

function el(tag, attrs = {}, children = []) {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(attrs)) {
    if (value == null || value === false) continue;
    if (key === 'class') node.className = value;
    else if (key === 'text') node.textContent = value;
    else node.setAttribute(key, value === true ? '' : String(value));
  }
  for (const child of [].concat(children)) if (child) node.append(child);
  return node;
}

function announce(text) {
  dom.live.textContent = text;
}

function showLogin(message = '') {
  abortActive();
  dom.chatView.classList.add('hidden');
  dom.navActions.classList.add('hidden');
  dom.loginView.classList.remove('hidden');
  dom.loginError.textContent = message;
  dom.loginError.classList.toggle('hidden', !message);
  dom.password.value = '';
  dom.username.focus();
}

async function showChat(me) {
  dom.loginView.classList.add('hidden');
  dom.chatView.classList.remove('hidden');
  dom.navActions.classList.remove('hidden');
  dom.who.textContent = me.username ?? '';
  dom.input.focus();
  await Promise.allSettled([loadFacets(), loadConversations()]);
}

async function boot() {
  renderTransport();
  updateCounter();
  try {
    const me = await auth.me();
    if (me?.authenticated) await showChat(me);
    else showLogin();
  } catch (error) {
    showLogin(error instanceof ApiError ? error.message : '로그인 상태를 확인할 수 없습니다.');
  }
}

dom.loginForm.addEventListener('submit', async (event) => {
  event.preventDefault();
  dom.loginButton.disabled = true;
  dom.loginError.classList.add('hidden');
  try {
    const me = await auth.login(dom.username.value.trim(), dom.password.value);
    const next = new URLSearchParams(location.search).get('next');
    if (next === 'knowledge') {
      location.replace('./knowledge.html');
      return;
    }
    await showChat(me);
  } catch (error) {
    const apiError = asApiError(error);
    dom.loginError.textContent = apiError.message;
    dom.loginError.classList.remove('hidden');
  } finally {
    dom.loginButton.disabled = false;
  }
});

dom.logout.addEventListener('click', async () => {
  try {
    await auth.logout();
  } catch {
  }
  resetConversation({ reload: false });
  showLogin();
});

async function loadFacets() {
  let facets = null;
  try {
    facets = await knowledge.facets();
  } catch (error) {
    if (error?.status === 401) return showLogin(error.message);
  }

  const types = facets?.documentTypes?.length
    ? facets.documentTypes.map((item) => [item.code ?? item.value, item.label ?? item.code ?? item.value])
    : FALLBACK_TYPES;
  renderChoices(dom.scopeTypes, types);
  renderChoices(dom.scopeProjects, normalizeFacetValues(facets?.projects));
  renderChoices(dom.scopeTechnologies, normalizeFacetValues(facets?.technologies));
  updateScopeSummary();
}

function normalizeFacetValues(values = []) {
  return values.map((item) => {
    if (typeof item === 'string') return [item, item];
    const value = item.value ?? item.name ?? item.code;
    return [value, item.label ?? value];
  }).filter(([value]) => value);
}

function renderChoices(host, choices) {
  host.replaceChildren();
  if (!choices.length) {
    host.append(el('span', { class: 't-body-sm t-mute', text: '등록된 값 없음' }));
    return;
  }
  for (const [value, label] of choices) {
    const input = el('input', { type: 'checkbox', value });
    input.addEventListener('change', updateScopeSummary);
    host.append(el('label', { class: 'choice-chip' }, [input, el('span', { text: label })]));
  }
}

function selectedValues(host) {
  return [...host.querySelectorAll('input:checked')].map((input) => input.value);
}

function currentScope() {
  return {
    projects: selectedValues(dom.scopeProjects),
    technologies: selectedValues(dom.scopeTechnologies),
    documentTypes: selectedValues(dom.scopeTypes),
    from: dom.scopeFrom.value || null,
    to: dom.scopeTo.value || null,
  };
}

function scopeIsAll(scope) {
  return !scope.projects.length && !scope.technologies.length && !scope.documentTypes.length
    && !scope.from && !scope.to;
}

function updateScopeSummary() {
  const scope = currentScope();
  const parts = [];
  if (scope.projects.length) parts.push(`프로젝트 ${scope.projects.length}`);
  if (scope.technologies.length) parts.push(`기술 ${scope.technologies.length}`);
  if (scope.documentTypes.length) parts.push(`유형 ${scope.documentTypes.length}`);
  if (scope.from || scope.to) parts.push(`${scope.from || '처음'}~${scope.to || '현재'}`);
  dom.scopeSummary.textContent = parts.length ? parts.join(' · ') : '전체 지식';
}

[dom.scopeFrom, dom.scopeTo].forEach((input) => input.addEventListener('change', updateScopeSummary));
dom.scopeClear.addEventListener('click', () => {
  document.querySelectorAll('.scope-panel input[type="checkbox"]').forEach((input) => { input.checked = false; });
  dom.scopeFrom.value = '';
  dom.scopeTo.value = '';
  updateScopeSummary();
});

async function loadConversations() {
  try {
    const page = await conversations.list();
    renderConversationList(page.content ?? []);
  } catch (error) {
    if (error?.status === 401) showLogin(error.message);
    else {
      dom.conversationList.replaceChildren();
      dom.conversationEmpty.textContent = '대화 기록을 불러오지 못했습니다.';
      dom.conversationEmpty.classList.remove('hidden');
    }
  }
}

function renderConversationList(items) {
  dom.conversationList.replaceChildren();
  dom.conversationEmpty.textContent = '저장된 대화가 없습니다.';
  dom.conversationEmpty.classList.toggle('hidden', items.length > 0);
  for (const item of items) {
    const id = item.id ?? item.conversationId;
    const button = el('button', {
      type: 'button',
      class: `conversation-item${String(id) === String(conversationId) ? ' is-active' : ''}`,
    }, [
      el('span', { class: 'conversation-item__title', text: item.title || '제목 없는 대화' }),
      el('span', { class: 'conversation-item__date', text: formatDateTime(item.updatedAt ?? item.createdAt) }),
    ]);
    button.addEventListener('click', () => openConversation(id));
    dom.conversationList.append(button);
  }
}

async function openConversation(id) {
  abortActive();
  try {
    const detail = await conversations.detail(id);
    conversationId = detail.id ?? detail.conversationId ?? id;
    conversationNextBefore = detail.hasMoreMessages ? detail.nextBeforeSequence : null;
    dom.thread.replaceChildren();
    dom.hero.classList.add('hidden');
    renderSavedMessages(detail.messages ?? detail.content ?? []);
    renderOlderMessagesControl();
    await loadConversations();
    scrollToEnd(true);
  } catch (error) {
    dom.thread.append(renderNotice('error', asApiError(error).message));
  }
}

function renderOlderMessagesControl() {
  dom.thread.querySelector('.messages-older')?.remove();
  if (conversationNextBefore == null) return;
  const button = el('button', { type: 'button', class: 'btn btn-ghost-sm', text: '이전 메시지 불러오기' });
  button.disabled = loadingOlderMessages;
  button.addEventListener('click', loadOlderMessages);
  dom.thread.prepend(el('div', { class: 'messages-older' }, [button]));
}

async function loadOlderMessages() {
  if (loadingOlderMessages || conversationId == null || conversationNextBefore == null) return;
  loadingOlderMessages = true;
  renderOlderMessagesControl();
  const anchor = dom.thread.querySelector('.messages-older')?.nextElementSibling;
  const previousTop = anchor?.getBoundingClientRect().top;
  try {
    const detail = await conversations.detail(conversationId, {
      beforeSequence: conversationNextBefore,
      limit: 100,
    });
    const fragment = document.createDocumentFragment();
    renderSavedMessages(detail.messages ?? detail.content ?? [], fragment);
    dom.thread.querySelector('.messages-older')?.remove();
    dom.thread.prepend(fragment);
    conversationNextBefore = detail.hasMoreMessages ? detail.nextBeforeSequence : null;
    renderOlderMessagesControl();
    if (anchor && previousTop != null) {
      window.scrollBy({ top: anchor.getBoundingClientRect().top - previousTop, behavior: 'auto' });
    }
  } catch (error) {
    dom.thread.prepend(renderNotice('error', asApiError(error).message));
  } finally {
    loadingOlderMessages = false;
    renderOlderMessagesControl();
  }
}

function renderSavedMessages(messages, parent = dom.thread) {
  for (const message of messages) {
    const role = String(message.role ?? message.type ?? '').toUpperCase();
    const content = message.content ?? message.message ?? message.answer ?? '';
    if (role === 'USER') {
      renderUserMessage(content, parent);
    } else if (role === 'ASSISTANT') {
      renderSavedAssistant(content, message.sources ?? [], message, parent);
    } else if (message.userMessage || message.question) {
      renderUserMessage(message.userMessage ?? message.question, parent);
      renderSavedAssistant(message.answer ?? '', message.sources ?? [], message, parent);
    }
  }
}

function renderSavedAssistant(text, sources, meta, parent = dom.thread) {
  const bot = createBotMessage(false, parent);
  bot.answer.append(document.createTextNode(text));
  fillEyebrow(bot.eyebrow, meta);
  const sourcesNode = renderSources(sources);
  if (sourcesNode) bot.extras.append(sourcesNode);
}

function renderUserMessage(text, parent = dom.thread) {
  parent.append(el('div', { class: 'msg-user', text }));
}

function createBotMessage(streaming = true, parent = dom.thread) {
  const eyebrow = el('div', { class: 't-eyebrow msg-bot__eyebrow' }, [
    el('span', { text: streaming ? '답변 생성 중' : '답변' }),
  ]);
  const answer = el('p', { class: 'msg-bot__answer' });
  const caret = streaming ? el('span', { class: 'caret', 'aria-hidden': true }) : null;
  if (caret) answer.append(caret);
  const extras = el('div');
  const wrap = el('div', { class: 'msg-bot' }, [eyebrow, answer, extras]);
  parent.append(wrap);
  return { wrap, eyebrow, answer, caret, extras, text: '' };
}

function fillEyebrow(node, meta = {}) {
  node.replaceChildren();
  const scope = scopeDescription(meta.scope);
  const parts = ['답변', scope && scope !== '전체' ? scope : null, meta.model].filter(Boolean);
  parts.forEach((part, index) => {
    if (index) node.append(el('span', { class: 'dot', text: '·' }));
    node.append(el('span', { text: part }));
  });
  if (meta.requestId) {
    node.append(el('span', { class: 'dot', text: '·' }));
    node.append(el('span', { class: 't-faint', title: '요청 ID', text: String(meta.requestId).slice(0, 8) }));
  }
}

function scopeDescription(scope) {
  if (!scope) return '';
  if (typeof scope === 'string') return scope;
  if (scope.description) return scope.description;
  const parts = [];
  if (scope.projects?.length) parts.push(scope.projects.join(', '));
  if (scope.technologies?.length) parts.push(scope.technologies.join(', '));
  if (scope.documentTypes?.length) parts.push(scope.documentTypes.join(', '));
  return parts.join(' · ') || '전체';
}

function renderSources(sources) {
  if (!sources?.length) return null;
  const groups = new Map();
  for (const source of sources) {
    const key = source.documentId ?? source.id ?? `${source.title}-${groups.size}`;
    if (!groups.has(key)) groups.set(key, { source, chunks: [] });
    groups.get(key).chunks.push(source);
  }

  const list = el('div', { class: 'sources__list' });
  for (const [documentId, group] of groups) {
    const source = group.source;
    const type = source.documentTypeLabel ?? source.documentType ?? '문서';
    const projects = source.projects ?? (source.project ? [source.project] : []);
    const metadata = [...projects, ...(source.technologies ?? []), ...(source.tags ?? [])];
    const snippets = el('div', { class: 'source__snippets' });
    for (const chunk of group.chunks) {
      snippets.append(el('div', { class: 'source__snippet' }, [
        chunk.heading ? el('strong', { text: chunk.heading }) : null,
        el('p', { text: chunk.snippet ?? '' }),
      ]));
    }
    list.append(el('article', { class: 'source' }, [
      el('div', { class: 'source__head' }, [
        el('span', { class: 'badge', text: type }),
        el('a', {
          class: 'source__title',
          href: `./knowledge.html?document=${encodeURIComponent(documentId)}`,
          text: source.title ?? '제목 없는 문서',
        }),
      ]),
      metadata.length ? el('div', { class: 'source__meta', text: metadata.join(' · ') }) : null,
      source.occurredOn ? el('time', { class: 'source__date', datetime: source.occurredOn, text: source.occurredOn }) : null,
      snippets,
    ]));
  }

  return el('details', { class: 'sources' }, [
    el('summary', { class: 'sources__summary', text: `근거 문서 ${groups.size}건` }),
    list,
  ]);
}

function renderNotice(tone, message, actions = []) {
  const className = tone === 'error' ? 'notice notice--error'
    : tone === 'warning' ? 'notice notice--warning' : 'notice';
  const body = el('div', { class: 'notice__body' }, [el('div', { text: message })]);
  if (actions.length) {
    const row = el('div', { class: 'notice__actions' });
    for (const action of actions) {
      const button = el('button', { type: 'button', class: 'btn btn-ghost-sm', text: action.label });
      button.addEventListener('click', action.onClick);
      row.append(button);
    }
    body.append(row);
  }
  return el('div', { class: `msg-notice ${className}` }, [body]);
}

async function ask(message, { echo = true, request = null } = {}) {
  abortActive();
  const scope = currentScope();
  if (scope.from && scope.to && scope.from > scope.to) {
    dom.thread.append(renderNotice('warning', '검색 기간의 시작일은 종료일보다 늦을 수 없습니다.'));
    return;
  }

  const payload = request ?? {
    message,
    conversationId,
    scope: scopeIsAll(scope) ? null : scope,
  };
  const controller = new AbortController();
  inflight = controller;
  dom.hero.classList.add('hidden');
  if (echo) renderUserMessage(message);
  const bot = createBotMessage();
  setBusy(true);
  announce('답변을 생성하고 있습니다.');
  scrollToEnd();

  let received = { sources: [], scope: payload.scope, requestId: null, model: null };
  let finished = false;
  const finish = () => {
    if (finished) return;
    finished = true;
    bot.caret?.remove();
    if (inflight === controller) {
      inflight = null;
      setBusy(false);
      dom.input.focus();
    }
  };

  try {
    const used = await askChat(payload, {
      onMeta(meta) {
        received = { ...received, ...meta };
        if (meta.conversationId != null) conversationId = meta.conversationId;
        fillEyebrow(bot.eyebrow, received);
      },
      onDelta(text) {
        bot.text += text;
        bot.caret?.before(document.createTextNode(text));
        scrollToEnd();
      },
      onDone(reason) {
        finish();
        const sourcesNode = renderSources(received.sources);
        if (sourcesNode) bot.extras.prepend(sourcesNode);
        if (reason === 'NO_CONTEXT') {
          if (!bot.text) bot.answer.append(document.createTextNode('선택한 범위의 개인 지식에서 근거를 찾지 못했습니다.'));
          bot.extras.append(renderNotice('plain', '범위가 너무 좁다면 전체 지식에서 다시 찾아보세요.', [
            { label: '범위 초기화', onClick: () => { dom.scopeClear.click(); } },
          ]));
          fillEyebrow(bot.eyebrow, { ...received, scope: '근거 없음' });
          announce('개인 지식에서 근거를 찾지 못했습니다.');
        } else {
          announce('답변이 완료되었습니다.');
        }
        loadConversations();
      },
      onError(error) {
        finish();
        if (error.isAborted) return;
        if (error.conversationId != null) conversationId = error.conversationId;
        if (conversationId != null) loadConversations();
        if (error.requestId) received.requestId = error.requestId;
        fillEyebrow(bot.eyebrow, { ...received, scope: '실패' });
        bot.extras.append(errorNotice(error, { ...payload, conversationId }));
        announce(error.message);
        if (error.status === 401) showLogin(error.message);
      },
      onIncomplete() {
        finish();
        fillEyebrow(bot.eyebrow, { ...received, scope: '중단됨' });
        bot.extras.append(renderNotice('warning', '답변이 끝까지 전달되지 않았습니다. 위 내용은 일부일 수 있습니다.', [
          { label: '다시 시도', onClick: () => ask(message, { echo: false, request: { ...payload, conversationId } }) },
        ]));
        announce('답변이 중단되었습니다.');
      },
    }, { signal: controller.signal });

    if (controller.signal.aborted) finish();
    if (used !== activeName) renderTransport();
  } catch (error) {
    finish();
    const apiError = asApiError(error);
    if (apiError.conversationId != null) conversationId = apiError.conversationId;
    if (!apiError.isAborted) {
      bot.extras.append(errorNotice(apiError, { ...payload, conversationId }));
    }
  }
}

function errorNotice(error, payload) {
  const actions = [];
  if (error.retryable) {
    actions.push({
      label: error.retryAfterSeconds ? `${error.retryAfterSeconds}초 후 다시 시도` : '다시 시도',
      onClick: () => ask(payload.message, { echo: false, request: payload }),
    });
  }
  return renderNotice('error', error.message, actions);
}

function setBusy(busy) {
  dom.send.disabled = busy;
  dom.input.disabled = busy;
  dom.stop.classList.toggle('hidden', !busy);
  dom.send.classList.toggle('hidden', busy);
}

function submit() {
  const message = dom.input.value.trim();
  if (!message || dom.send.disabled || message.length > MAX_LENGTH) return;
  dom.input.value = '';
  updateCounter();
  autoGrow();
  ask(message);
}

function updateCounter() {
  const length = dom.input.value.length;
  dom.count.textContent = `${length} / ${MAX_LENGTH}`;
  dom.count.classList.toggle('composer__count--warn', length > MAX_LENGTH * 0.9 && length <= MAX_LENGTH);
  dom.count.classList.toggle('composer__count--over', length > MAX_LENGTH);
}

function autoGrow() {
  dom.input.style.height = 'auto';
  dom.input.style.height = `${dom.input.scrollHeight}px`;
}

function scrollToEnd(force = false) {
  const nearBottom = window.innerHeight + window.scrollY >= document.body.scrollHeight - 160;
  if (force || nearBottom) window.scrollTo({ top: document.body.scrollHeight, behavior: 'smooth' });
}

function abortActive() {
  inflight?.abort();
  inflight = null;
  setBusy(false);
}

function resetConversation({ reload = true } = {}) {
  abortActive();
  resetTransports();
  conversationId = null;
  conversationNextBefore = null;
  dom.thread.replaceChildren();
  dom.hero.classList.remove('hidden');
  dom.input.value = '';
  updateCounter();
  autoGrow();
  if (reload) loadConversations();
  window.scrollTo({ top: 0 });
}

const TRANSPORT_ORDER = ['sse', 'ws'];
const TRANSPORT_LABELS = { sse: 'SSE', ws: 'WebSocket' };

function renderTransport() {
  activeName = activeTransport().name;
  const next = TRANSPORT_ORDER[(TRANSPORT_ORDER.indexOf(activeName) + 1) % TRANSPORT_ORDER.length];
  dom.transport.textContent = `전송: ${TRANSPORT_LABELS[activeName]}`;
  dom.transport.title = `${TRANSPORT_LABELS[next]}(으)로 바꾸기`;
  dom.transport.dataset.transport = activeName;
}

dom.transport.addEventListener('click', () => {
  abortActive();
  const next = TRANSPORT_ORDER[(TRANSPORT_ORDER.indexOf(activeName) + 1) % TRANSPORT_ORDER.length];
  setTransportName(next);
  renderTransport();
  announce(`${TRANSPORT_LABELS[next]} 전송으로 바꿨습니다.`);
});

onFallback(({ reason }) => {
  renderTransport();
  announce('WebSocket 연결에 실패해 SSE로 전환했습니다.');
  console.warn('[chat] transport fallback ws -> sse (%s)', reason);
});

dom.form.addEventListener('submit', (event) => { event.preventDefault(); submit(); });
dom.input.addEventListener('input', () => { updateCounter(); autoGrow(); });

let lastCompositionEnd = 0;
dom.input.addEventListener('compositionend', () => { lastCompositionEnd = Date.now(); });
dom.input.addEventListener('keydown', (event) => {
  if (event.key !== 'Enter' || event.shiftKey || event.isComposing) return;
  event.preventDefault();
  submit();
});
dom.input.addEventListener('keyup', (event) => {
  if (event.key === 'Enter' && !event.shiftKey && Date.now() - lastCompositionEnd < 100) {
    lastCompositionEnd = 0;
    submit();
  }
});

dom.stop.addEventListener('click', () => {
  abortActive();
  announce('생성을 중지했습니다.');
});
dom.reset.addEventListener('click', resetConversation);
dom.conversationNew.addEventListener('click', resetConversation);
dom.suggestions.addEventListener('click', (event) => {
  const button = event.target.closest('button[data-q]');
  if (!button) return;
  dom.input.value = button.dataset.q;
  updateCounter();
  autoGrow();
  submit();
});
window.addEventListener('pagehide', () => { abortActive(); resetTransports(); });

function asApiError(error) {
  return error instanceof ApiError
    ? error
    : new ApiError({ code: 'UNKNOWN', message: error?.message ?? String(error) });
}

function formatDateTime(value) {
  if (!value) return '';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '' : new Intl.DateTimeFormat('ko-KR', { dateStyle: 'medium' }).format(date);
}

boot();
