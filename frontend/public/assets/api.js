export const BASE = document.querySelector('meta[name="api-base"]')?.content ?? '';

const CSRF_COOKIE = 'XSRF-TOKEN';
const CSRF_HEADER = 'X-XSRF-TOKEN';

export class ApiError extends Error {
  constructor({
    code, message, retryable = false, status = 0, retryAfterSeconds = null,
    requestId = null, conversationId = null, cause = null,
  }) {
    super(message || '요청을 처리하지 못했습니다.', { cause });
    this.name = 'ApiError';
    this.code = code || 'UNKNOWN';
    this.retryable = retryable;
    this.status = status;
    this.retryAfterSeconds = retryAfterSeconds;
    this.requestId = requestId;
    this.conversationId = conversationId;
  }

  get isAborted() {
    return this.code === 'ABORTED';
  }
}

export function networkError(cause) {
  return new ApiError({
    code: 'NETWORK_ERROR',
    message: '서버에 연결할 수 없습니다. 네트워크를 확인해 주세요.',
    retryable: true,
    cause,
  });
}

function readCookie(name) {
  const row = document.cookie.split('; ').find((item) => item.startsWith(name + '='));
  return row ? decodeURIComponent(row.slice(name.length + 1)) : null;
}

export async function ensureCsrf() {
  if (readCookie(CSRF_COOKIE)) return;
  try {
    await fetch(BASE + '/api/auth/me', {
      credentials: 'same-origin',
      headers: { Accept: 'application/json' },
    });
  } catch {
  }
}

export async function csrfHeaders() {
  await ensureCsrf();
  const token = readCookie(CSRF_COOKIE);
  return token ? { [CSRF_HEADER]: token } : {};
}

async function request(path, { method = 'GET', body, csrf = false, signal, headers = {} } = {}) {
  const multipart = typeof FormData !== 'undefined' && body instanceof FormData;
  const outboundHeaders = { Accept: 'application/json', ...headers };

  if (body !== undefined && !multipart && !outboundHeaders['Content-Type']) {
    outboundHeaders['Content-Type'] = 'application/json';
  }
  if (csrf) Object.assign(outboundHeaders, await csrfHeaders());

  let response;
  try {
    response = await fetch(BASE + path, {
      method,
      headers: outboundHeaders,
      credentials: 'same-origin',
      body: body === undefined ? undefined : multipart ? body : JSON.stringify(body),
      signal,
    });
  } catch (error) {
    if (error?.name === 'AbortError') {
      throw new ApiError({ code: 'ABORTED', message: '요청이 취소되었습니다.' });
    }
    throw networkError(error);
  }

  if (!response.ok) throw await toApiError(response);
  if (response.status === 204) return null;

  const text = await response.text();
  if (!text) return null;
  try {
    return JSON.parse(text);
  } catch (cause) {
    throw new ApiError({
      code: 'INVALID_RESPONSE',
      message: '서버 응답을 해석할 수 없습니다.',
      status: response.status,
      cause,
    });
  }
}

export async function toApiError(response) {
  const retryAfterSeconds = Number(response.headers.get('Retry-After')) || null;
  let payload = null;
  try {
    const text = await response.text();
    if (text) payload = JSON.parse(text);
  } catch {
  }

  if (payload && typeof payload.code === 'string') {
    return new ApiError({
      code: payload.code,
      message: payload.message,
      retryable: Boolean(payload.retryable),
      status: response.status,
      retryAfterSeconds,
      requestId: payload.requestId ?? null,
      conversationId: payload.conversationId ?? null,
    });
  }

  return new ApiError({
    code: `HTTP_${response.status}`,
    message: statusMessage(response.status),
    retryable: response.status === 429 || response.status >= 500,
    status: response.status,
    retryAfterSeconds,
  });
}

function statusMessage(status) {
  if (status === 400) return '입력값을 확인해 주세요.';
  if (status === 401) return '로그인이 필요합니다.';
  if (status === 403) return '이 작업을 수행할 권한이 없습니다.';
  if (status === 404) return '요청한 데이터를 찾을 수 없습니다.';
  if (status === 409) return '같은 자료가 이미 있거나 다른 변경과 충돌했습니다.';
  if (status === 413) return '파일이 너무 큽니다. 20MB 이하 파일을 선택해 주세요.';
  if (status === 415) return '지원하지 않는 파일 형식입니다.';
  if (status === 429) return '요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.';
  if (status >= 500) return '서버 처리 중 오류가 발생했습니다.';
  return '요청을 처리하지 못했습니다.';
}

export const auth = {
  me({ signal } = {}) {
    return request('/api/auth/me', { signal });
  },

  login(username, password, { signal } = {}) {
    return request('/api/auth/login', {
      method: 'POST', body: { username, password }, csrf: true, signal,
    });
  },

  logout({ signal } = {}) {
    return request('/api/auth/logout', { method: 'POST', csrf: true, signal });
  },
};

export const chat = {
  send({ message, conversationId = null, scope = null }, { signal } = {}) {
    return request('/api/chat/messages', {
      method: 'POST',
      body: { message, conversationId, scope },
      csrf: true,
      signal,
    });
  },
};

export const knowledge = {
  facets({ signal } = {}) {
    return request('/api/knowledge/facets', { signal });
  },

  list(filters = {}, { signal } = {}) {
    const query = new URLSearchParams();
    setIfPresent(query, 'q', filters.q);
    appendAll(query, 'documentType', filters.documentTypes ?? filters.documentType);
    appendAll(query, 'project', filters.projects ?? filters.project);
    appendAll(query, 'technology', filters.technologies ?? filters.technology);
    appendAll(query, 'tag', filters.tags ?? filters.tag);
    appendAll(query, 'indexingStatus', filters.indexingStatuses ?? filters.indexingStatus);
    setIfPresent(query, 'from', filters.from);
    setIfPresent(query, 'to', filters.to);
    query.set('page', String(filters.page ?? 0));
    query.set('size', String(filters.size ?? 20));
    query.set('sort', filters.sort ?? 'updatedAt,desc');
    return request(`/api/knowledge/documents?${query}`, { signal }).then(normalizePage);
  },

  detail(id, { signal } = {}) {
    return request(`/api/knowledge/documents/${encodeURIComponent(id)}`, { signal });
  },

  createText(metadata, { signal } = {}) {
    return request('/api/knowledge/documents', {
      method: 'POST', body: metadata, csrf: true, signal,
    });
  },

  upload(metadata, file, { signal } = {}) {
    const form = new FormData();
    form.append('metadata', new Blob([JSON.stringify(metadata)], { type: 'application/json' }));
    form.append('file', file, file.name);
    return request('/api/knowledge/documents', {
      method: 'POST', body: form, csrf: true, signal,
    });
  },

  update(id, payload, { signal } = {}) {
    return request(`/api/knowledge/documents/${encodeURIComponent(id)}`, {
      method: 'PUT', body: payload, csrf: true, signal,
    });
  },

  updateMetadata(id, payload, { signal } = {}) {
    return request(`/api/knowledge/documents/${encodeURIComponent(id)}`, {
      method: 'PATCH', body: payload, csrf: true, signal,
    });
  },

  remove(id, { signal } = {}) {
    return request(`/api/knowledge/documents/${encodeURIComponent(id)}`, {
      method: 'DELETE', csrf: true, signal,
    });
  },

  reindex(id, { signal } = {}) {
    return request(`/api/knowledge/documents/${encodeURIComponent(id)}/reindex`, {
      method: 'POST', csrf: true, signal,
    });
  },

  originalUrl(id) {
    return `${BASE}/api/knowledge/documents/${encodeURIComponent(id)}/original`;
  },
};

export const conversations = {
  list({ page = 0, size = 30, signal } = {}) {
    const query = new URLSearchParams({ page: String(page), size: String(size), sort: 'updatedAt,desc' });
    return request(`/api/conversations?${query}`, { signal }).then(normalizePage);
  },

  detail(id, { beforeSequence = null, limit = 100, signal } = {}) {
	const query = new URLSearchParams({ limit: String(limit) });
	setIfPresent(query, 'beforeSequence', beforeSequence);
	return request(`/api/conversations/${encodeURIComponent(id)}?${query}`, { signal });
  },

  rename(id, title, { signal } = {}) {
    return request(`/api/conversations/${encodeURIComponent(id)}`, {
      method: 'PATCH', body: { title }, csrf: true, signal,
    });
  },

  remove(id, { signal } = {}) {
    return request(`/api/conversations/${encodeURIComponent(id)}`, {
      method: 'DELETE', csrf: true, signal,
    });
  },
};

function setIfPresent(query, name, value) {
  if (value !== undefined && value !== null && String(value).trim() !== '') {
    query.set(name, String(value));
  }
}

function appendAll(query, name, values) {
  if (values === undefined || values === null || values === '') return;
  const list = Array.isArray(values) || values instanceof Set ? [...values] : [values];
  for (const value of list) {
    if (value !== undefined && value !== null && String(value).trim() !== '') {
      query.append(name, String(value));
    }
  }
}

function normalizePage(raw) {
  if (!raw) return { content: [], page: 0, size: 0, totalElements: 0, totalPages: 0 };
  if (Array.isArray(raw)) {
    return { content: raw, page: 0, size: raw.length, totalElements: raw.length, totalPages: 1 };
  }
  const meta = raw.page && typeof raw.page === 'object' ? raw.page : raw;
  return {
    content: raw.content ?? raw.items ?? [],
    page: meta.number ?? meta.page ?? 0,
    size: meta.size ?? 0,
    totalElements: meta.totalElements ?? meta.total ?? 0,
    totalPages: meta.totalPages ?? 0,
  };
}
