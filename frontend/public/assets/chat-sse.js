import { ApiError, BASE, csrfHeaders, toApiError, networkError } from './api.js';

export const sseTransport = {
  name: 'sse',
  label: 'SSE',

  async ask(payload, handlers, { signal } = {}) {
    const { onMeta, onDelta, onDone, onError, onIncomplete } = handlers;

    let res;
    try {
      res = await fetch(BASE + '/api/chat/messages/stream', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Accept: 'text/event-stream',
          ...await csrfHeaders(),
        },
        credentials: 'same-origin',
        body: JSON.stringify(payload),
        signal,
      });
    } catch (e) {
      if (e?.name === 'AbortError') return { ok: true };
      onError(networkError(e));
      return { ok: true };
    }

    if (!res.ok) {
      onError(await toApiError(res));
      return { ok: true };
    }
    if (!res.body) {
      onError(new ApiError({ code: 'INVALID_RESPONSE', message: '스트림을 열 수 없습니다.' }));
      return { ok: true };
    }

    let terminated = false;

    const reader = res.body.pipeThrough(new TextDecoderStream()).getReader();
    let buffer = '';

    try {
      for (;;) {
        const { value, done } = await reader.read();
        if (done) break;

        buffer += value;

        let sep;
        while ((sep = findEventBoundary(buffer)) !== -1) {
          const raw = buffer.slice(0, sep.index);
          buffer = buffer.slice(sep.index + sep.length);

          const evt = parseEvent(raw);
          if (!evt) continue;

          if (evt.name === 'meta') {
            onMeta(evt.data);
          } else if (evt.name === 'delta') {
            if (evt.data?.text) onDelta(evt.data.text);
          } else if (evt.name === 'done') {
            terminated = true;
            onDone(evt.data?.reason ?? 'COMPLETED');
          } else if (evt.name === 'error') {
            terminated = true;
            onError(
              new ApiError({
                code: evt.data?.code,
                message: evt.data?.message,
                retryable: Boolean(evt.data?.retryable),
                requestId: evt.data?.requestId ?? null,
                conversationId: evt.data?.conversationId ?? null,
                status: 200,
              }),
            );
          }
        }
      }
    } catch (e) {
      if (e?.name === 'AbortError') return { ok: true };
      if (!terminated) {
        onIncomplete();
        return { ok: true };
      }
    } finally {
      reader.releaseLock?.();
    }

    if (!terminated) onIncomplete();
    return { ok: true };
  },

  reset() {},
};

function findEventBoundary(buffer) {
  const lf = buffer.indexOf('\n\n');
  const crlf = buffer.indexOf('\r\n\r\n');
  if (lf === -1 && crlf === -1) return -1;
  if (crlf !== -1 && (lf === -1 || crlf < lf)) return { index: crlf, length: 4 };
  return { index: lf, length: 2 };
}

function parseEvent(raw) {
  let name = 'message';
  const dataLines = [];

  for (const line of raw.split(/\r?\n/)) {
    if (!line || line.startsWith(':')) continue;

    const colon = line.indexOf(':');
    const field = colon === -1 ? line : line.slice(0, colon);
    let value = colon === -1 ? '' : line.slice(colon + 1);
    if (value.startsWith(' ')) value = value.slice(1);

    if (field === 'event') name = value;
    else if (field === 'data') dataLines.push(value);
  }

  if (dataLines.length === 0) return null;

  const payload = dataLines.join('\n');
  try {
    return { name, data: JSON.parse(payload) };
  } catch {
    console.warn('[sse] JSON 이 아닌 data 를 받았습니다:', payload);
    return null;
  }
}
