import { ApiError, BASE } from './api.js';

const PATH = '/api/chat/ws';

let socket = null;

let current = null;

export const wsTransport = {
  name: 'ws',
  label: 'WebSocket',

  async ask(payload, handlers, { signal } = {}) {
    if (signal?.aborted) return { ok: true };

    let ws;
    try {
      ws = await ensureSocket();
    } catch (e) {
      return { ok: false, reason: e?.reason ?? 'CONNECT_FAILED' };
    }
    if (signal?.aborted) return { ok: true };

    return new Promise((resolve) => {
      const exchange = {
        handlers,
        terminated: false,
        settle(result) {
          if (current === exchange) current = null;
          signal?.removeEventListener('abort', onAbort);
          resolve(result);
        },
      };
      current = exchange;

      function onAbort() {
        closeSocket(1000, 'aborted');
        exchange.settle({ ok: true });
      }
      signal?.addEventListener('abort', onAbort, { once: true });

      try {
        ws.send(JSON.stringify(payload));
      } catch (e) {
        closeSocket(1011, 'send failed');
        exchange.settle({ ok: false, reason: 'SEND_FAILED' });
      }
    });
  },

  reset() {
    closeSocket(1000, 'reset');
  },
};

function ensureSocket() {
  if (socket && socket.readyState === WebSocket.OPEN) return Promise.resolve(socket);

  closeSocket(1000, 'stale');

  return new Promise((resolve, reject) => {
    let ws;
    try {
      ws = new WebSocket(socketUrl());
    } catch (e) {
      reject({ reason: 'CONNECT_FAILED', cause: e });
      return;
    }

    let settled = false;

    ws.addEventListener('open', () => {
      settled = true;
      socket = ws;
      wireMessages(ws);
      resolve(ws);
    }, { once: true });

    ws.addEventListener('close', (e) => {
      if (settled) return;
      settled = true;
      console.warn('[ws] 핸드셰이크 실패 code=%s. 상태코드는 브라우저가 알려주지 않는다.', e.code);
      reject({ reason: 'CONNECT_FAILED' });
    }, { once: true });
  });
}

function wireMessages(ws) {
  ws.addEventListener('message', (ev) => handleFrame(ev.data));

  ws.addEventListener('close', () => {
    if (socket === ws) socket = null;

    const ex = current;
    if (ex && !ex.terminated) {
      ex.handlers.onIncomplete();
      ex.settle({ ok: true });
    }
  });

  ws.addEventListener('error', () => {});
}

function closeSocket(code, reason) {
  const ws = socket;
  socket = null;
  if (!ws) return;
  try {
    ws.close(code, reason);
  } catch {
  }
}

function handleFrame(raw) {
  const ex = current;
  if (!ex) {
    return;
  }

  let frame;
  try {
    frame = JSON.parse(raw);
  } catch {
    console.warn('[ws] JSON 이 아닌 프레임을 받았습니다:', raw);
    return;
  }

  const data = frame.data ?? {};

  switch (frame.type) {
    case 'meta':
      ex.handlers.onMeta(data);
      break;

    case 'delta':
      if (data.text) ex.handlers.onDelta(data.text);
      break;

    case 'done':
      ex.terminated = true;
      ex.handlers.onDone(data.reason ?? 'COMPLETED');
      ex.settle({ ok: true });
      break;

    case 'error':
      ex.terminated = true;
      ex.handlers.onError(
        new ApiError({
          code: data.code,
          message: data.message,
          retryable: Boolean(data.retryable),
          requestId: data.requestId ?? null,
          conversationId: data.conversationId ?? null,
          status: 200,
        }),
      );
      ex.settle({ ok: true });
      break;

    default:
      console.warn('[ws] 모르는 프레임 type:', frame.type);
  }
}

function socketUrl() {
  const u = new URL(PATH, BASE || location.href);
  u.protocol = u.protocol === 'https:' ? 'wss:' : 'ws:';
  return u.toString();
}
