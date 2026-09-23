const crypto = require('node:crypto');
const { EventEmitter } = require('node:events');

const GUID = '258EAFA5-E914-47DA-95CA-C5AB0DC85B11';

const OP_TEXT = 0x1;
const OP_CLOSE = 0x8;
const OP_PING = 0x9;
const OP_PONG = 0xa;

function attach(server, path, onConnection) {
  server.on('upgrade', (req, socket) => {
    const url = new URL(req.url, 'http://localhost');
    const key = req.headers['sec-websocket-key'];

    if (url.pathname !== path || !key) {
      socket.write('HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n');
      socket.destroy();
      return;
    }

    const accept = crypto.createHash('sha1').update(key + GUID).digest('base64');
    socket.write(
      'HTTP/1.1 101 Switching Protocols\r\n' +
      'Upgrade: websocket\r\n' +
      'Connection: Upgrade\r\n' +
      `Sec-WebSocket-Accept: ${accept}\r\n\r\n`,
    );

    onConnection(new Conn(socket), req);
  });
}

class Conn extends EventEmitter {
  constructor(socket) {
    super();
    this.socket = socket;
    this.buffer = Buffer.alloc(0);
    this.closed = false;

    socket.setNoDelay(true);
    socket.on('data', (chunk) => {
      this.buffer = Buffer.concat([this.buffer, chunk]);
      this.#drain();
    });
    socket.on('close', () => {
      this.closed = true;
      this.emit('close');
    });
    socket.on('error', () => {});
  }

  #drain() {
    for (;;) {
      const frame = decode(this.buffer);
      if (!frame) return;
      this.buffer = this.buffer.subarray(frame.size);

      if (!frame.fin) {
        console.warn('[mock-ws] 프래그먼트 프레임은 처리하지 않습니다. 메시지를 버립니다.');
        continue;
      }

      if (frame.opcode === OP_TEXT) {
        this.emit('message', frame.payload.toString('utf8'));
      } else if (frame.opcode === OP_CLOSE) {
        this.close();
        return;
      } else if (frame.opcode === OP_PING) {
        this.#write(frame.payload, OP_PONG);
      }
    }
  }

  send(text) {
    this.#write(Buffer.from(text, 'utf8'), OP_TEXT);
  }

  close(code = 1000) {
    if (this.closed) return;
    this.closed = true;
    const payload = Buffer.alloc(2);
    payload.writeUInt16BE(code, 0);
    this.#write(payload, OP_CLOSE);
    this.socket.end();
  }

  destroy() {
    this.closed = true;
    this.socket.destroy();
  }

  #write(payload, opcode) {
    if (this.socket.destroyed) return;
    this.socket.write(encode(payload, opcode));
  }
}

function decode(buf) {
  if (buf.length < 2) return null;

  const fin = (buf[0] & 0x80) !== 0;
  const opcode = buf[0] & 0x0f;
  const masked = (buf[1] & 0x80) !== 0;

  let len = buf[1] & 0x7f;
  let offset = 2;

  if (len === 126) {
    if (buf.length < offset + 2) return null;
    len = buf.readUInt16BE(offset);
    offset += 2;
  } else if (len === 127) {
    if (buf.length < offset + 8) return null;
    len = Number(buf.readBigUInt64BE(offset));
    offset += 8;
  }

  let mask = null;
  if (masked) {
    if (buf.length < offset + 4) return null;
    mask = buf.subarray(offset, offset + 4);
    offset += 4;
  }

  if (buf.length < offset + len) return null;

  const payload = Buffer.from(buf.subarray(offset, offset + len));
  if (mask) {
    for (let i = 0; i < payload.length; i++) payload[i] ^= mask[i % 4];
  }

  return { fin, opcode, payload, size: offset + len };
}

function encode(payload, opcode) {
  let header;

  if (payload.length < 126) {
    header = Buffer.alloc(2);
    header[1] = payload.length;
  } else if (payload.length < 65536) {
    header = Buffer.alloc(4);
    header[1] = 126;
    header.writeUInt16BE(payload.length, 2);
  } else {
    header = Buffer.alloc(10);
    header[1] = 127;
    header.writeBigUInt64BE(BigInt(payload.length), 2);
  }

  header[0] = 0x80 | opcode;
  return Buffer.concat([header, payload]);
}

module.exports = { attach };
