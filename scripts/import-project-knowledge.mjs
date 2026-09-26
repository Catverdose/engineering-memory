import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const manifestPath = path.join(projectRoot, 'seed', 'project-knowledge.json');
const manifest = JSON.parse(await fs.readFile(manifestPath, 'utf8'));
const dryRun = process.argv.includes('--dry-run');

if (manifest.schemaVersion !== 2 || !Array.isArray(manifest.documents)) {
  throw new Error('지식 데이터 양식(schemaVersion 2)이 올바르지 않습니다.');
}

const entries = [];
const titles = new Set();
const normalizedText = (value) => value.replace(/\r\n?/g, '\n').trim();
for (const entry of manifest.documents) {
  if (!entry.title || !entry.documentType || !entry.content || titles.has(entry.title)) {
    throw new Error(`제목·유형·본문이 없거나 제목이 중복되었습니다: ${entry.title ?? '(제목 없음)'}`);
  }
  if (Boolean(entry.sourcePath) === Boolean(entry.sourceUri)) {
    throw new Error(`로컬 경로와 원문 URL 중 하나만 있어야 합니다: ${entry.title}`);
  }
  if (entry.sourcePath) {
    const sourcePath = path.resolve(projectRoot, entry.sourcePath);
    if (!sourcePath.startsWith(projectRoot + path.sep)) {
      throw new Error(`프로젝트 바깥의 자료는 등록할 수 없습니다: ${entry.sourcePath}`);
    }
    await fs.access(sourcePath);
  } else if (new URL(entry.sourceUri).protocol !== 'https:') {
    throw new Error(`공식 원문 URL은 HTTPS여야 합니다: ${entry.title}`);
  }
  const content = `${entry.content.trim()}\n\n출처: ${entry.sourcePath || entry.sourceUri}`;
  titles.add(entry.title);
  entries.push({
    title: entry.title,
    documentType: entry.documentType,
    projects: entry.projects ?? [],
    technologies: entry.technologies ?? [],
    tags: entry.tags ?? [],
    occurredOn: entry.occurredOn ?? null,
    sourceUri: entry.sourceUri ?? null,
    content,
    sourcePath: entry.sourcePath ?? null,
    replacesLegacy: Boolean(entry.replacesLegacy),
  });
}

if (dryRun) {
  for (const entry of entries) {
    console.log(`${entry.documentType}\t${entry.title}\t${entry.content.length}자`);
  }
  console.log(`검증 완료: ${entries.length}건. 서버에는 등록하지 않았습니다.`);
  process.exit(0);
}

async function readLocalCredentials() {
  const values = {};
  try {
    const raw = await fs.readFile(path.join(projectRoot, '.env'), 'utf8');
    for (const line of raw.split(/\r?\n/)) {
      const match = /^\s*(ADMIN_USERNAME|ADMIN_PASSWORD)\s*=\s*(.*?)\s*$/.exec(line);
      if (!match) continue;
      let value = match[2];
      if ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith("'") && value.endsWith("'"))) {
        value = value.slice(1, -1);
      }
      values[match[1]] = value;
    }
  } catch (error) {
    if (error.code !== 'ENOENT') throw error;
  }
  return values;
}

const localCredentials = await readLocalCredentials();
const username = process.env.KNOWLEDGE_USERNAME || localCredentials.ADMIN_USERNAME || 'admin';
const password = process.env.KNOWLEDGE_PASSWORD || localCredentials.ADMIN_PASSWORD;
if (!password || password.startsWith('{')) {
  throw new Error('로그인 비밀번호가 필요합니다. KNOWLEDGE_PASSWORD 환경변수에 실제 로그인 비밀번호를 지정하세요.');
}

const baseUrl = new URL(process.env.KNOWLEDGE_BASE_URL || 'http://localhost');
if (!['http:', 'https:'].includes(baseUrl.protocol) || baseUrl.username || baseUrl.password || baseUrl.pathname !== '/') {
  throw new Error('KNOWLEDGE_BASE_URL은 경로·자격정보가 없는 http(s) 주소여야 합니다.');
}
if (baseUrl.protocol === 'http:' && !['localhost', '127.0.0.1', '[::1]'].includes(baseUrl.hostname)) {
  throw new Error('원격 서버에 비밀번호를 보낼 때는 HTTPS를 사용하세요.');
}

const cookies = new Map();
async function api(route, { method = 'GET', body, csrf = false } = {}) {
  const headers = { Accept: 'application/json' };
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (cookies.size) headers.Cookie = [...cookies].map(([key, value]) => `${key}=${value}`).join('; ');
  if (csrf) {
    const token = cookies.get('XSRF-TOKEN');
    if (!token) throw new Error('CSRF 토큰을 받지 못했습니다.');
    headers['X-XSRF-TOKEN'] = decodeURIComponent(token);
  }
  const response = await fetch(new URL(route, baseUrl), {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
    redirect: 'error',
  });
  for (const setCookie of response.headers.getSetCookie()) {
    const pair = setCookie.split(';', 1)[0];
    const divider = pair.indexOf('=');
    if (divider > 0) cookies.set(pair.slice(0, divider), pair.slice(divider + 1));
  }
  const text = await response.text();
  let payload;
  try { payload = text ? JSON.parse(text) : null; } catch { payload = null; }
  if (!response.ok) {
    throw new Error(`${method} ${route}: HTTP ${response.status}${payload?.code ? ` ${payload.code}` : ''}`);
  }
  return payload;
}

await api('/api/auth/me');
await api('/api/auth/login', { method: 'POST', body: { username, password }, csrf: true });

const existingDocuments = new Map();
for (let page = 0; ; page += 1) {
  const result = await api(`/api/knowledge/documents?page=${page}&size=100`);
  for (const document of result.content ?? []) existingDocuments.set(document.title, document);
  if (page + 1 >= (result.totalPages ?? 1)) break;
}

let created = 0;
let updated = 0;
let skipped = 0;
for (const entry of entries) {
  const existing = existingDocuments.get(entry.title);
  if (existing) {
    const detail = await api(`/api/knowledge/documents/${existing.id}`);
    if (detail.content === entry.content) {
      console.log(`유지: ${entry.title}`);
      skipped += 1;
      continue;
    }
    if (entry.replacesLegacy && entry.sourcePath && detail.version === 1) {
      const source = await fs.readFile(path.join(projectRoot, entry.sourcePath), 'utf8');
      const legacyContent = `원본 경로: ${entry.sourcePath}\n\n${source}`;
      if (detail.content === normalizedText(legacyContent)) {
        const { sourcePath, replacesLegacy, ...payload } = entry;
        const result = await api(`/api/knowledge/documents/${existing.id}`, {
          method: 'PUT', body: payload, csrf: true,
        });
        console.log(`갱신: ${entry.title} (id=${result.id}, 색인=${result.indexingStatus})`);
        updated += 1;
        continue;
      }
    }
    console.log(`건너뜀(기존 내용이 다름, 수동 확인 필요): ${entry.title}`);
    skipped += 1;
    continue;
  }
  const { sourcePath, replacesLegacy, ...payload } = entry;
  const result = await api('/api/knowledge/documents', { method: 'POST', body: payload, csrf: true });
  console.log(`등록: ${entry.title} (id=${result.id}, 색인=${result.indexingStatus})`);
  created += 1;
}
console.log(`완료: 신규 ${created}건, 갱신 ${updated}건, 유지·건너뜀 ${skipped}건. PENDING은 색인 완료 후 READY가 됩니다.`);
