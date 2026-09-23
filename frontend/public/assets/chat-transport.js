import { sseTransport } from './chat-sse.js';
import { wsTransport } from './chat-ws.js';
import { networkError } from './api.js';

export const TRANSPORTS = {
  [sseTransport.name]: sseTransport,
  [wsTransport.name]: wsTransport,
};

const DEFAULT_NAME = 'sse';
const STORAGE_KEY = 'engineering-assistant.transport';
const QUERY_KEY = 'transport';

let forcedName = null;

const fallbackListeners = [];

function readStorage() {
  try {
    return localStorage.getItem(STORAGE_KEY);
  } catch {
    return null;
  }
}

function writeStorage(name) {
  try {
    localStorage.setItem(STORAGE_KEY, name);
  } catch {
  }
}

export function selectedName() {
  const fromQuery = new URLSearchParams(location.search).get(QUERY_KEY);
  if (fromQuery && TRANSPORTS[fromQuery]) {
    if (readStorage() !== fromQuery) writeStorage(fromQuery);
    return fromQuery;
  }

  const stored = readStorage();
  if (stored && TRANSPORTS[stored]) return stored;

  const fromMeta = document.querySelector('meta[name="chat-transport"]')?.content?.trim();
  if (fromMeta && TRANSPORTS[fromMeta]) return fromMeta;

  return DEFAULT_NAME;
}

export function activeTransport() {
  return TRANSPORTS[forcedName ?? selectedName()];
}

export function setTransportName(name) {
  if (!TRANSPORTS[name]) throw new Error('모르는 전송 방식: ' + name);
  forcedName = null;
  writeStorage(name);
  for (const t of Object.values(TRANSPORTS)) t.reset?.();
  return TRANSPORTS[name];
}

export function onFallback(listener) {
  fallbackListeners.push(listener);
}

export async function askChat(payload, handlers, options = {}) {
  const transport = activeTransport();
  const result = await transport.ask(payload, handlers, options);

  if (result?.ok !== false) return transport.name;

  if (transport.name !== 'ws') {
    console.error('[transport] %s 가 계약에 없는 ok:false 를 돌려줬습니다', transport.name);
    handlers.onError(networkError(new Error(result.reason)));
    return transport.name;
  }

  console.warn('[transport] WebSocket 연결 실패(%s). SSE 로 내려갑니다.', result.reason);
  forcedName = 'sse';
  for (const listener of fallbackListeners) listener({ from: 'ws', to: 'sse', reason: result.reason });

  if (options.signal?.aborted) return 'sse';
  await sseTransport.ask(payload, handlers, options);
  return 'sse';
}

export function resetTransports() {
  for (const t of Object.values(TRANSPORTS)) t.reset?.();
}
