const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const ws = require('./ws.js');

const ROOT = path.join(__dirname, '..', 'public');
const KNOWLEDGE_SEED = require('../../seed/project-knowledge.json');
const PORT = Number(process.env.PORT || process.argv[2] || 5173);
const CSRF = 'mock-csrf-token';

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.txt': 'text/plain; charset=utf-8',
};

const TYPE_LABELS = {
	DOCUMENT: '일반 문서', PROJECT: '프로젝트 자료',
  TROUBLESHOOTING: '장애·트러블슈팅', TECH_DOC: '기술 문서', PROJECT_DOC: '프로젝트 문서',
  NOTE: '개발·학습 노트', RETROSPECTIVE: '회고', EXPERIMENT: '실험·측정 결과',
	DECISION: '기술 선택 근거', LOG: '로그', OTHER: '기타',
};

let loggedIn = false;
const seededAt = new Date().toISOString();
const documents = KNOWLEDGE_SEED.documents.map((entry, index) => ({
  id: index + 1,
  title: entry.title,
  documentType: entry.documentType,
  documentTypeLabel: TYPE_LABELS[entry.documentType],
  projects: entry.projects,
  technologies: entry.technologies,
  tags: entry.tags,
  sourceName: null,
  sourceUri: entry.sourceUri,
  mediaType: 'text/plain',
  occurredOn: entry.occurredOn,
  content: `${entry.content.trim()}\n\n출처: ${entry.sourcePath || entry.sourceUri}`,
  version: 1,
  indexingStatus: 'READY',
  createdAt: seededAt,
  updatedAt: seededAt,
}));
let nextDocumentId = documents.length + 1;
let nextConversationId = 1;
const conversations = [];

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function sourceHits(items) {
  return items.map((document, index) => ({
    documentId: document.id,
    chunkId: document.id * 10 + index,
    title: document.title,
    documentType: document.documentType,
    documentTypeLabel: document.documentTypeLabel,
    projects: document.projects,
    technologies: document.technologies,
    tags: document.tags,
    occurredOn: document.occurredOn,
    chunkIndex: index,
    heading: index === 0 ? '핵심 기록' : null,
    similarity: 0.84 - index * 0.07,
    snippet: document.content.replace(/\s+/g, ' ').slice(0, 200),
  }));
}

async function readRaw(req) {
  const chunks = [];
  for await (const chunk of req) chunks.push(chunk);
  return Buffer.concat(chunks);
}

async function readJson(req) {
  const raw = await readRaw(req);
  try { return JSON.parse(raw.toString('utf8') || '{}'); } catch { return {}; }
}

async function readDocumentPayload(req) {
  const contentType = req.headers['content-type'] || '';
  if (!contentType.startsWith('multipart/form-data')) return { metadata: await readJson(req), fileName: null };
  const boundary = /boundary=(?:"([^"]+)"|([^;]+))/.exec(contentType)?.slice(1).find(Boolean);
  if (!boundary) return { metadata: {}, fileName: null };
  const raw = (await readRaw(req)).toString('latin1');
  let metadata = {};
  let fileName = null;
  for (const part of raw.split(`--${boundary}`)) {
    const split = part.indexOf('\r\n\r\n');
    if (split < 0) continue;
    const headers = part.slice(0, split);
    const body = part.slice(split + 4).replace(/\r\n$/, '');
    const name = /name="([^"]+)"/.exec(headers)?.[1];
    if (name === 'metadata') {
      try { metadata = JSON.parse(Buffer.from(body, 'latin1').toString('utf8')); } catch { metadata = {}; }
    } else if (name === 'file') {
      fileName = /filename="([^"]*)"/.exec(headers)?.[1] || 'uploaded-file';
    }
  }
  return { metadata, fileName };
}

function json(res, status, payload, headers = {}) {
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'no-store',
    ...headers,
  });
  res.end(JSON.stringify(payload));
}

function page(items, query) {
  const size = Math.max(1, Number(query.get('size') || 20));
  const number = Math.max(0, Number(query.get('page') || 0));
  return {
    content: items.slice(number * size, number * size + size),
    number,
    size,
    totalElements: items.length,
    totalPages: Math.max(1, Math.ceil(items.length / size)),
  };
}

function denied(res) {
  json(res, 401, { code: 'UNAUTHORIZED', message: '로그인이 필요합니다.', retryable: false });
}

function csrfOk(req) {
  return req.headers['x-xsrf-token'] === CSRF;
}

function requireMutationCsrf(req, res) {
  if (csrfOk(req)) return true;
  json(res, 403, { code: 'FORBIDDEN', message: 'CSRF 토큰이 없습니다.', retryable: false });
  return false;
}

function sseChannel(res) {
  let opened = false;
  const open = () => {
    if (opened) return;
    opened = true;
    res.writeHead(200, {
      'Content-Type': 'text/event-stream; charset=utf-8',
      'Cache-Control': 'no-cache, no-transform',
      Connection: 'keep-alive',
      'X-Accel-Buffering': 'no',
    });
  };
  return {
    reject(status, payload, headers = {}) { json(res, status, payload, headers); },
    send(event, data) { open(); res.write(`event:${event}\ndata:${JSON.stringify(data)}\n\n`); },
    end() { res.end(); },
    destroy() { res.destroy(); },
  };
}

function wsChannel(conn) {
  return {
    reject(status, payload) { conn.send(JSON.stringify({ type: 'error', data: payload })); },
    send(type, data) { conn.send(JSON.stringify({ type, data })); },
    end() {},
    destroy() { conn.destroy(); },
  };
}

function applyScope(items, scope) {
  if (!scope) return items;
  return items.filter((document) => {
    if (scope.projects?.length && !scope.projects.some((value) => document.projects.includes(value))) return false;
    if (scope.technologies?.length && !scope.technologies.some((value) => document.technologies.includes(value))) return false;
    if (scope.documentTypes?.length && !scope.documentTypes.includes(document.documentType)) return false;
    if (scope.from && document.occurredOn && document.occurredOn < scope.from) return false;
    if (scope.to && document.occurredOn && document.occurredOn > scope.to) return false;
    return document.indexingStatus === 'READY';
  });
}

function describeScope(scope) {
  if (!scope) return '전체';
  const parts = [];
  if (scope.projects?.length) parts.push(`프로젝트=${scope.projects.join(',')}`);
  if (scope.technologies?.length) parts.push(`기술=${scope.technologies.join(',')}`);
  if (scope.documentTypes?.length) parts.push(`유형=${scope.documentTypes.join(',')}`);
  if (scope.from || scope.to) parts.push(`기간=${scope.from || ''}~${scope.to || ''}`);
  return parts.join(' · ') || '전체';
}

async function runScenario(body, channel) {
  const message = String(body.message || '').trim();
  if (!message || message.length > 1000) {
    channel.reject(400, { code: 'INVALID_REQUEST', message: '질문은 1자 이상 1,000자 이하여야 합니다.', retryable: false });
    return;
  }
  if (message.includes('!busy')) {
    channel.reject(503, { code: 'MODEL_BUSY', message: 'AI 생성 요청이 많습니다. 잠시 후 다시 시도해 주세요.', retryable: true });
    return;
  }
  if (message.includes('!limit')) {
    channel.reject(429, { code: 'TOO_MANY_REQUESTS', message: '요청이 너무 많습니다.', retryable: true }, { 'Retry-After': '6' });
    return;
  }

  let conversation = body.conversationId == null
    ? null
    : conversations.find((item) => String(item.id) === String(body.conversationId));
  if (!conversation) {
    conversation = {
      id: nextConversationId++, title: message.slice(0, 40), messages: [],
      createdAt: new Date().toISOString(), updatedAt: new Date().toISOString(),
    };
    conversations.unshift(conversation);
  }

  const candidates = message.includes('!nocontext') ? [] : applyScope(documents, body.scope).slice(0, 3);
  const sources = sourceHits(candidates);
  const requestId = `mock-${Math.random().toString(16).slice(2, 10)}`;
  channel.send('meta', {
    requestId,
    conversationId: conversation.id,
    scope: { ...(body.scope || {}), description: describeScope(body.scope) },
    sources,
    model: sources.length ? 'exaone3.5:7.8b' : null,
  });

  if (!sources.length) {
    const answer = '선택한 범위의 개인 지식에서 관련 근거를 찾지 못했습니다.';
    await sleep(100);
    channel.send('delta', { text: answer });
    channel.send('done', { reason: 'NO_CONTEXT' });
    conversation.messages.push({ role: 'USER', content: message }, { role: 'ASSISTANT', content: answer, sources: [] });
    conversation.updatedAt = new Date().toISOString();
    channel.end();
    return;
  }

  const answer = `목 서버의 예시 응답입니다. 선택된 자료: ${sources.map((source) => source.title).join(', ')}. 실제 근거 검색과 답변은 백엔드에서 확인해 주세요.`;
  const pieces = answer.match(/.{1,7}/gu) || [];
  for (let index = 0; index < pieces.length; index++) {
    if (message.includes('!cut') && index === 5) return channel.destroy();
    if (message.includes('!fail') && index === 5) {
      channel.send('error', {
        code: 'MODEL_UNAVAILABLE', message: 'AI 모델을 사용할 수 없습니다.', retryable: true,
        requestId, conversationId: conversation.id,
      });
      return channel.end();
    }
    channel.send('delta', { text: pieces[index] });
    await sleep(35);
  }
  channel.send('done', { reason: 'COMPLETED' });
  conversation.messages.push({ role: 'USER', content: message }, { role: 'ASSISTANT', content: answer, sources });
  conversation.updatedAt = new Date().toISOString();
  channel.end();
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://localhost:${PORT}`);
  const pathname = url.pathname;
  res.setHeader('Set-Cookie', `XSRF-TOKEN=${CSRF}; Path=/; SameSite=Lax`);

  if (pathname === '/api/auth/me' && req.method === 'GET') {
    return json(res, 200, { authenticated: loggedIn, ownerId: loggedIn ? 1 : null, username: loggedIn ? 'owner' : null, roles: loggedIn ? ['ADMIN', 'USER'] : [] });
  }
  if (pathname === '/api/auth/login' && req.method === 'POST') {
    if (!requireMutationCsrf(req, res)) return;
    const body = await readJson(req);
    if (!body.username || !body.password) return json(res, 401, { code: 'INVALID_CREDENTIALS', message: '아이디 또는 비밀번호가 올바르지 않습니다.', retryable: false });
    loggedIn = true;
    return json(res, 200, { authenticated: true, ownerId: 1, username: body.username, roles: ['ADMIN', 'USER'] });
  }
  if (pathname === '/api/auth/logout' && req.method === 'POST') {
    if (!requireMutationCsrf(req, res)) return;
    loggedIn = false;
    return json(res, 204, null);
  }

  if (pathname.startsWith('/api/') && !loggedIn) return denied(res);

  if (pathname === '/api/knowledge/facets' && req.method === 'GET') {
    const count = (type) => documents.filter((document) => document.documentType === type).length;
    return json(res, 200, {
      documentTypes: Object.entries(TYPE_LABELS).map(([code, label]) => ({ code, label, count: count(code) })),
      projects: [...new Set(documents.flatMap((document) => document.projects))],
      technologies: [...new Set(documents.flatMap((document) => document.technologies))],
      tags: [...new Set(documents.flatMap((document) => document.tags))],
    });
  }

  if (pathname === '/api/knowledge/documents' && req.method === 'GET') {
    let result = [...documents].sort((a, b) => String(b.updatedAt).localeCompare(String(a.updatedAt)));
    const q = (url.searchParams.get('q') || '').toLowerCase();
    if (q) result = result.filter((document) => `${document.title} ${document.content}`.toLowerCase().includes(q));
    for (const [key, field, array] of [
      ['documentType', 'documentType', false], ['project', 'projects', true],
      ['technology', 'technologies', true], ['tag', 'tags', true], ['indexingStatus', 'indexingStatus', false],
    ]) {
      const values = url.searchParams.getAll(key).filter(Boolean);
      if (values.length) result = result.filter((document) => array
        ? values.some((value) => document[field].includes(value)) : values.includes(document[field]));
    }
    return json(res, 200, page(result, url.searchParams));
  }

  if (pathname === '/api/knowledge/documents' && req.method === 'POST') {
    if (!requireMutationCsrf(req, res)) return;
    const { metadata, fileName } = await readDocumentPayload(req);
    const now = new Date().toISOString();
    const document = {
      id: nextDocumentId++, title: metadata.title, documentType: metadata.documentType,
      documentTypeLabel: TYPE_LABELS[metadata.documentType] || metadata.documentType,
      projects: metadata.projects || [], technologies: metadata.technologies || [], tags: metadata.tags || [],
      sourceName: fileName, sourceUri: metadata.sourceUri || null,
      mediaType: fileName?.toLowerCase().endsWith('.pdf') ? 'application/pdf' : 'text/plain',
      occurredOn: metadata.occurredOn || null,
      content: metadata.content || (fileName ? `${fileName}에서 추출한 목업 텍스트입니다.` : ''),
      version: 1, indexingStatus: 'PENDING', createdAt: now, updatedAt: now,
    };
    documents.unshift(document);
    return json(res, 202, document, { Location: `/api/knowledge/documents/${document.id}` });
  }

  let match = pathname.match(/^\/api\/knowledge\/documents\/(\d+)\/reindex$/);
  if (match && req.method === 'POST') {
    if (!requireMutationCsrf(req, res)) return;
    const document = documents.find((item) => item.id === Number(match[1]));
    if (!document) return json(res, 404, { code: 'NOT_FOUND', message: '자료를 찾을 수 없습니다.', retryable: false });
    document.indexingStatus = 'READY';
    document.updatedAt = new Date().toISOString();
    return json(res, 202, document);
  }

  match = pathname.match(/^\/api\/knowledge\/documents\/(\d+)\/original$/);
  if (match && req.method === 'GET') {
    const document = documents.find((item) => item.id === Number(match[1]));
    if (!document) return json(res, 404, { code: 'NOT_FOUND', message: '원본을 찾을 수 없습니다.', retryable: false });
    res.writeHead(200, { 'Content-Type': document.mediaType || 'text/plain', 'Content-Disposition': `attachment; filename="${document.sourceName || 'document.txt'}"`, 'Cache-Control': 'no-store' });
    return res.end(document.content);
  }

  match = pathname.match(/^\/api\/knowledge\/documents\/(\d+)$/);
  if (match) {
    const index = documents.findIndex((item) => item.id === Number(match[1]));
    if (index < 0) return json(res, 404, { code: 'NOT_FOUND', message: '자료를 찾을 수 없습니다.', retryable: false });
    if (req.method === 'GET') return json(res, 200, documents[index]);
    if (req.method === 'DELETE') {
      if (!requireMutationCsrf(req, res)) return;
      documents.splice(index, 1);
      return json(res, 204, null);
    }
    if (req.method === 'PUT') {
      if (!requireMutationCsrf(req, res)) return;
      Object.assign(documents[index], await readJson(req), {
        version: documents[index].version + 1,
        indexingStatus: 'PENDING',
        updatedAt: new Date().toISOString(),
      });
      return json(res, 200, documents[index]);
    }
    if (req.method === 'PATCH') {
      if (!requireMutationCsrf(req, res)) return;
      Object.assign(documents[index], await readJson(req), {
        version: documents[index].version + 1,
        indexingStatus: 'PENDING',
        updatedAt: new Date().toISOString(),
      });
      documents[index].documentTypeLabel = TYPE_LABELS[documents[index].documentType]
        || documents[index].documentType;
      return json(res, 200, documents[index]);
    }
  }

  if (pathname === '/api/conversations' && req.method === 'GET') {
    return json(res, 200, page(conversations, url.searchParams));
  }
  match = pathname.match(/^\/api\/conversations\/(\d+)$/);
  if (match) {
    const index = conversations.findIndex((item) => item.id === Number(match[1]));
    if (index < 0) return json(res, 404, { code: 'NOT_FOUND', message: '대화를 찾을 수 없습니다.', retryable: false });
    if (req.method === 'GET') return json(res, 200, conversations[index]);
    if (req.method === 'PATCH') {
      if (!requireMutationCsrf(req, res)) return;
      conversations[index].title = (await readJson(req)).title || conversations[index].title;
      return json(res, 200, conversations[index]);
    }
    if (req.method === 'DELETE') {
      if (!requireMutationCsrf(req, res)) return;
      conversations.splice(index, 1);
      return json(res, 204, null);
    }
  }

  if (pathname === '/api/chat/messages/stream' && req.method === 'POST') {
    if (!requireMutationCsrf(req, res)) return;
    return runScenario(await readJson(req), sseChannel(res));
  }
  if (pathname === '/api/chat/messages' && req.method === 'POST') {
    if (!requireMutationCsrf(req, res)) return;
    const body = await readJson(req);
    const chunks = [];
    let meta = {};
    let reason = 'COMPLETED';
    await runScenario(body, {
      reject(status, payload) { chunks.push(payload.message); reason = 'FAILED'; },
      send(type, data) { if (type === 'meta') meta = data; else if (type === 'delta') chunks.push(data.text); else if (type === 'done') reason = data.reason; },
      end() {}, destroy() {},
    });
    return json(res, 200, { ...meta, status: reason, answer: chunks.join(''), sources: meta.sources || [] });
  }

  if (pathname.startsWith('/api/')) return json(res, 404, { code: 'NOT_FOUND', message: '목 서버에 없는 경로입니다.', retryable: false });

  const relative = pathname === '/' ? 'index.html' : decodeURIComponent(pathname).replace(/^\/+/, '');
  let file = path.resolve(ROOT, relative);
  if (!file.startsWith(path.resolve(ROOT))) { res.writeHead(403); return res.end('forbidden'); }
  if (!fs.existsSync(file) || fs.statSync(file).isDirectory()) file = path.join(ROOT, 'index.html');
  res.writeHead(200, { 'Content-Type': MIME[path.extname(file)] || 'application/octet-stream' });
  fs.createReadStream(file).pipe(res);
});

ws.attach(server, '/api/chat/ws', (connection) => {
  let generating = false;
  connection.on('message', async (raw) => {
    if (!loggedIn) {
      connection.send(JSON.stringify({ type: 'error', data: { code: 'UNAUTHORIZED', message: '로그인이 필요합니다.', retryable: false } }));
      return;
    }
    let body;
    try { body = JSON.parse(raw); } catch {
      connection.send(JSON.stringify({ type: 'error', data: { code: 'INVALID_REQUEST', message: '요청 값이 올바르지 않습니다.', retryable: false } }));
      return;
    }
    if (generating) {
      connection.send(JSON.stringify({ type: 'error', data: { code: 'TOO_MANY_REQUESTS', message: '이 연결에서 이미 답변을 생성하고 있습니다.', retryable: true } }));
      return;
    }
    generating = true;
    try { await runScenario(body, wsChannel(connection)); } finally { generating = false; }
  });
});

server.listen(PORT, () => console.log(`mock server on http://localhost:${PORT}`));
