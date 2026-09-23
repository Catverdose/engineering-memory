import { ApiError, auth, knowledge } from './api.js';

const MAX_FILE_BYTES = 20 * 1024 * 1024;
const ALLOWED_EXTENSIONS = new Set(['txt', 'md', 'markdown', 'log', 'pdf']);
const FALLBACK_TYPES = [
	['DOCUMENT', '일반 문서'],
	['PROJECT', '프로젝트 자료'],
  ['TROUBLESHOOTING', '장애·트러블슈팅'],
  ['TECH_DOC', '기술 문서'],
  ['PROJECT_DOC', '프로젝트 문서'],
  ['NOTE', '개발·학습 노트'],
  ['RETROSPECTIVE', '회고'],
  ['EXPERIMENT', '실험·측정 결과'],
  ['DECISION', '기술 선택 근거'],
	['LOG', '로그'],
  ['OTHER', '기타'],
];

const $ = (id) => document.getElementById(id);
const dom = {
  authRequired: $('auth-required'), app: $('knowledge-app'), who: $('who'), logout: $('btn-logout'),
  flash: $('flash'), toggleCreate: $('toggle-create'), createPanel: $('create-panel'),
  createTitle: $('create-title'), sourceModeSwitch: $('source-mode-switch'),
  closeCreate: $('close-create'), cancelCreate: $('cancel-create'), form: $('document-form'),
  submit: $('submit-document'), title: $('doc-title'), type: $('doc-type'), occurredOn: $('doc-date'),
  projects: $('doc-projects'), technologies: $('doc-technologies'), tags: $('doc-tags'),
  sourceUri: $('doc-source-uri'), content: $('doc-content'), file: $('doc-file'),
  textSource: $('text-source'), fileSource: $('file-source'), projectOptions: $('project-options'),
  technologyOptions: $('technology-options'), tagOptions: $('tag-options'),
  filterForm: $('filter-form'), filterQ: $('filter-q'), filterType: $('filter-type'),
  filterProject: $('filter-project'), filterTechnology: $('filter-technology'),
  filterStatus: $('filter-status'), clearFilters: $('clear-filters'), refresh: $('refresh-documents'),
  list: $('document-list'), empty: $('document-empty'), emptyCreate: $('empty-create'),
  total: $('document-total'), pager: $('document-pager'), detail: $('detail-panel'),
  detailTitle: $('detail-title'), detailMeta: $('detail-meta'), detailContent: $('detail-content'),
  detailActions: $('detail-actions'), closeDetail: $('close-detail'),
};

let currentPage = 0;
let facets = null;
let loadingController = null;
let editingDocument = null;

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

async function boot() {
  try {
    const me = await auth.me();
    if (!me?.authenticated) return showAuthRequired();
    dom.who.textContent = me.username ?? '';
    dom.logout.classList.remove('hidden');
    dom.app.classList.remove('hidden');
    await loadFacets();
    await loadDocuments(0);
    const requested = new URLSearchParams(location.search).get('document');
    if (requested) await showDetail(requested);
  } catch (error) {
    if (error?.status === 401) showAuthRequired();
    else flash('error', asApiError(error).message);
  }
}

function showAuthRequired() {
  dom.app.classList.add('hidden');
  dom.logout.classList.add('hidden');
  dom.authRequired.classList.remove('hidden');
}

dom.logout.addEventListener('click', async () => {
  try { await auth.logout(); } catch {}
  location.replace('./index.html');
});

async function loadFacets() {
  try {
    facets = await knowledge.facets();
  } catch (error) {
    if (error?.status === 401) return showAuthRequired();
    facets = { documentTypes: [], projects: [], technologies: [], tags: [] };
  }

  const types = facets.documentTypes?.length
    ? facets.documentTypes.map((item) => [item.code ?? item.value, item.label ?? item.code ?? item.value])
    : FALLBACK_TYPES;
  fillSelect(dom.type, types, null);
  fillSelect(dom.filterType, types, '모든 유형');
  fillSelect(dom.filterProject, normalizeValues(facets.projects), '모든 프로젝트');
  fillSelect(dom.filterTechnology, normalizeValues(facets.technologies), '모든 기술');
  fillDataList(dom.projectOptions, normalizeValues(facets.projects));
  fillDataList(dom.technologyOptions, normalizeValues(facets.technologies));
  fillDataList(dom.tagOptions, normalizeValues(facets.tags));
}

function normalizeValues(values = []) {
  return values.map((item) => {
    if (typeof item === 'string') return [item, item];
    const value = item.value ?? item.name ?? item.code;
    return [value, item.label ?? value];
  }).filter(([value]) => value);
}

function fillSelect(select, values, allLabel) {
  const selected = select.value;
  select.replaceChildren();
  if (allLabel) select.append(el('option', { value: '', text: allLabel }));
  for (const [value, label] of values) select.append(el('option', { value, text: label }));
  if ([...select.options].some((option) => option.value === selected)) select.value = selected;
}

function fillDataList(list, values) {
  list.replaceChildren(...values.map(([value]) => el('option', { value })));
}

function sourceMode() {
  return dom.form.elements['source-mode'].value;
}

function syncSourceMode() {
  if (editingDocument?.mediaType === 'application/pdf') {
    dom.fileSource.classList.add('hidden');
    dom.textSource.classList.add('hidden');
    dom.file.required = false;
    dom.content.required = false;
    return;
  }
  const fileMode = sourceMode() === 'file';
  dom.fileSource.classList.toggle('hidden', !fileMode);
  dom.textSource.classList.toggle('hidden', fileMode);
  dom.file.required = fileMode;
  dom.content.required = !fileMode;
}

[...dom.form.querySelectorAll('input[name="source-mode"]')]
  .forEach((radio) => radio.addEventListener('change', syncSourceMode));

function showEditor() {
  dom.createPanel.classList.remove('hidden');
  dom.title.focus();
  dom.createPanel.scrollIntoView({ behavior: 'smooth', block: 'start' });
}

function openCreate() {
  resetCreateForm();
  showEditor();
}

function closeCreate() {
  dom.createPanel.classList.add('hidden');
  resetCreateForm();
}

[dom.toggleCreate, dom.emptyCreate].forEach((button) => button.addEventListener('click', openCreate));
[dom.closeCreate, dom.cancelCreate].forEach((button) => button.addEventListener('click', closeCreate));

dom.file.addEventListener('change', () => {
  const file = dom.file.files?.[0];
  if (!file || dom.title.value.trim()) return;
  dom.title.value = file.name.replace(/\.[^.]+$/, '');
});

dom.form.addEventListener('submit', async (event) => {
  event.preventDefault();
  clearFlash();
  const metadata = {
    title: dom.title.value.trim(),
    documentType: dom.type.value,
    projects: splitValues(dom.projects.value),
    technologies: splitValues(dom.technologies.value),
    tags: splitValues(dom.tags.value),
    occurredOn: dom.occurredOn.value || null,
    sourceUri: dom.sourceUri.value.trim() || null,
  };

  try {
    dom.submit.disabled = true;
    dom.submit.textContent = '저장 중…';
    if (editingDocument) {
      if (editingDocument.mediaType === 'application/pdf') {
        await knowledge.updateMetadata(editingDocument.id, metadata);
      } else {
        const content = dom.content.value.trim();
        if (!content) throw new ApiError({ code: 'INVALID_REQUEST', message: '수정할 내용을 입력해 주세요.' });
        await knowledge.update(editingDocument.id, { ...metadata, content });
      }
    } else if (sourceMode() === 'file') {
      const file = dom.file.files?.[0];
      validateFile(file);
      await knowledge.upload(metadata, file);
    } else {
      const content = dom.content.value.trim();
      if (!content) throw new ApiError({ code: 'INVALID_REQUEST', message: '등록할 내용을 입력해 주세요.' });
      await knowledge.createText({ ...metadata, content });
    }
    const savedId = editingDocument?.id;
    flash('success', editingDocument
      ? '자료를 수정했습니다. 새 버전의 색인이 끝나면 검색에 포함됩니다.'
      : '자료를 저장했습니다. 색인이 끝나면 대화 검색에 포함됩니다.');
    resetCreateForm();
    dom.createPanel.classList.add('hidden');
    await Promise.all([loadFacets(), loadDocuments(0)]);
    if (savedId != null) await showDetail(savedId);
  } catch (error) {
    const apiError = asApiError(error);
    if (apiError.status === 401) showAuthRequired();
    else flash('error', apiError.message);
  } finally {
    dom.submit.disabled = false;
    dom.submit.textContent = editingDocument ? '수정하고 재색인' : '저장하고 색인';
  }
});

function validateFile(file) {
  if (!file) throw new ApiError({ code: 'INVALID_REQUEST', message: '업로드할 파일을 선택해 주세요.' });
  const extension = file.name.split('.').pop()?.toLowerCase() ?? '';
  const isExtensionlessReadme = file.name.toLowerCase() === 'readme';
  if (!ALLOWED_EXTENSIONS.has(extension) && !isExtensionlessReadme) {
    throw new ApiError({ code: 'UNSUPPORTED_FILE_TYPE', message: 'README, TXT, Markdown, LOG, PDF 파일만 등록할 수 있습니다.' });
  }
  if (file.size > MAX_FILE_BYTES) {
    throw new ApiError({ code: 'FILE_TOO_LARGE', message: '파일은 20MB를 넘을 수 없습니다.' });
  }
}

function resetCreateForm() {
  editingDocument = null;
  dom.form.reset();
  dom.createTitle.textContent = '자료 등록';
  dom.submit.textContent = '저장하고 색인';
  dom.sourceModeSwitch.classList.remove('hidden');
  for (const radio of dom.form.querySelectorAll('input[name="source-mode"]')) radio.disabled = false;
  dom.form.elements['source-mode'].value = 'text';
  syncSourceMode();
}

function editDocument(document) {
  editingDocument = document;
  dom.form.reset();
  dom.createTitle.textContent = '자료 수정';
  dom.submit.textContent = '수정하고 재색인';
  dom.title.value = document.title ?? '';
  dom.type.value = document.documentType ?? '';
  dom.occurredOn.value = document.occurredOn ?? '';
  dom.projects.value = (document.projects ?? []).join(', ');
  dom.technologies.value = (document.technologies ?? []).join(', ');
  dom.tags.value = (document.tags ?? []).join(', ');
  dom.sourceUri.value = document.sourceUri ?? '';
  dom.content.value = document.content ?? '';
  dom.form.elements['source-mode'].value = 'text';
  dom.sourceModeSwitch.classList.add('hidden');
  for (const radio of dom.form.querySelectorAll('input[name="source-mode"]')) radio.disabled = true;
  syncSourceMode();
  showEditor();
}

function splitValues(raw) {
  return [...new Set(raw.split(',').map((value) => value.trim()).filter(Boolean))];
}

function currentFilters(page = currentPage) {
  return {
    q: dom.filterQ.value.trim(),
    documentType: dom.filterType.value,
    project: dom.filterProject.value,
    technology: dom.filterTechnology.value,
    indexingStatus: dom.filterStatus.value,
    page,
    size: 20,
  };
}

async function loadDocuments(page = currentPage) {
  loadingController?.abort();
  const controller = new AbortController();
  loadingController = controller;
  dom.list.setAttribute('aria-busy', 'true');
  try {
    const result = await knowledge.list(currentFilters(page), { signal: controller.signal });
    if (controller.signal.aborted) return;
    currentPage = result.page;
    renderDocuments(result.content ?? []);
    renderPager(result);
    dom.total.textContent = `${Number(result.totalElements ?? 0).toLocaleString('ko-KR')} DOCUMENTS`;
  } catch (error) {
    if (error?.isAborted) return;
    if (error?.status === 401) showAuthRequired();
    else flash('error', asApiError(error).message);
  } finally {
    if (loadingController === controller) loadingController = null;
    dom.list.removeAttribute('aria-busy');
  }
}

function renderDocuments(items) {
  dom.list.replaceChildren();
  dom.empty.classList.toggle('hidden', items.length > 0);
  if (!items.length) return;

  for (const document of items) {
    const type = document.documentTypeLabel ?? document.documentType ?? '문서';
    const metadata = [
      ...(document.projects ?? []),
      ...(document.technologies ?? []),
      ...(document.tags ?? []),
    ];
    const status = document.indexingStatus ?? 'PENDING';
	const needsReindex = Boolean(document.needsReindex) || status !== 'READY';
    const actions = el('div', { class: 'document-card__actions' });
    const detailButton = el('button', { type: 'button', class: 'btn btn-ghost-sm', text: '보기' });
    detailButton.addEventListener('click', () => showDetail(document.id));
    actions.append(detailButton);
    if (needsReindex) {
      const reindexButton = el('button', { type: 'button', class: 'btn btn-ghost-sm', text: '재색인' });
      reindexButton.addEventListener('click', () => reindexDocument(document.id, reindexButton));
      actions.append(reindexButton);
    }
    const deleteButton = el('button', { type: 'button', class: 'btn btn-danger-sm', text: '삭제' });
    deleteButton.addEventListener('click', () => deleteDocument(document));
    actions.append(deleteButton);

    dom.list.append(el('article', { class: 'document-card' }, [
      el('div', { class: 'document-card__main' }, [
        el('div', { class: 'document-card__title-row' }, [
          el('span', { class: 'badge', text: type }),
          el('button', { type: 'button', class: 'document-card__title', text: document.title ?? '제목 없는 문서', 'data-id': document.id }),
        ]),
        metadata.length ? el('div', { class: 'document-card__tags', text: metadata.join(' · ') }) : null,
        el('div', { class: 'document-card__sub' }, [
          document.sourceName ? el('span', { text: document.sourceName }) : null,
          document.occurredOn ? el('time', { datetime: document.occurredOn, text: document.occurredOn }) : null,
          el('span', { text: `v${document.version ?? 1}` }),
        ]),
      ]),
      el('span', { class: `index-status index-status--${status.toLowerCase()}`, text: statusLabel(status, needsReindex) }),
      actions,
    ]));
  }

  dom.list.querySelectorAll('.document-card__title').forEach((button) => {
    button.addEventListener('click', () => showDetail(button.dataset.id));
  });
}

function statusLabel(status, needsReindex = false) {
  if (needsReindex && status === 'READY') return '모델 변경 · 재색인 필요';
  return { READY: '검색 가능', PENDING: '색인 대기', FAILED: '색인 실패' }[status] ?? status;
}

async function reindexDocument(id, button) {
  button.disabled = true;
  try {
    await knowledge.reindex(id);
    flash('success', '재색인을 요청했습니다. 잠시 후 상태를 다시 확인해 주세요.');
    await loadDocuments();
  } catch (error) {
    flash('error', asApiError(error).message);
  } finally {
    button.disabled = false;
  }
}

async function deleteDocument(document) {
  if (!confirm(`“${document.title}” 자료와 검색 데이터를 삭제할까요? 이 작업은 되돌릴 수 없습니다.`)) return;
  try {
    await knowledge.remove(document.id);
    flash('success', '자료를 삭제했습니다.');
    dom.detail.classList.add('hidden');
    await Promise.all([loadFacets(), loadDocuments(currentPage)]);
  } catch (error) {
    flash('error', asApiError(error).message);
  }
}

async function showDetail(id) {
  try {
    const document = await knowledge.detail(id);
    dom.detailTitle.textContent = document.title ?? '제목 없는 문서';
    dom.detailMeta.replaceChildren();
    const values = [
      document.documentTypeLabel ?? document.documentType,
      ...(document.projects ?? []),
      ...(document.technologies ?? []),
      ...(document.tags ?? []),
      document.occurredOn,
      statusLabel(document.indexingStatus, document.needsReindex),
    ].filter(Boolean);
    for (const value of values) dom.detailMeta.append(el('span', { class: 'badge', text: value }));
    dom.detailContent.textContent = document.content ?? '원문 미리보기를 제공하지 않는 파일입니다.';
    dom.detailActions.replaceChildren();
    if (document.sourceName) {
      dom.detailActions.append(el('a', {
        class: 'btn btn-ghost-sm', href: knowledge.originalUrl(document.id), text: '원본 다운로드', download: true,
      }));
    }
    const sourceUrl = safeExternalUrl(document.sourceUri);
    if (sourceUrl) {
      dom.detailActions.append(el('a', {
        class: 'btn btn-ghost-sm', href: sourceUrl, target: '_blank', rel: 'noopener noreferrer', text: '원본 위치 열기',
      }));
    }
    const edit = el('button', { type: 'button', class: 'btn btn-primary-sm', text: '수정' });
    edit.addEventListener('click', () => editDocument(document));
    dom.detailActions.append(edit);
    const reindex = el('button', { type: 'button', class: 'btn btn-ghost-sm', text: '재색인' });
    reindex.addEventListener('click', () => reindexDocument(document.id, reindex));
    dom.detailActions.append(reindex);
    dom.detail.classList.remove('hidden');
    dom.detail.scrollIntoView({ behavior: 'smooth', block: 'start' });
    const url = new URL(location.href);
    url.searchParams.set('document', document.id);
    history.replaceState(null, '', url);
  } catch (error) {
    flash('error', asApiError(error).message);
  }
}

function renderPager({ page, totalPages }) {
  dom.pager.replaceChildren();
  if (totalPages <= 1) return;
  const previous = el('button', { type: 'button', class: 'btn btn-ghost-sm', text: '이전', disabled: page <= 0 });
  previous.addEventListener('click', () => loadDocuments(page - 1));
  const next = el('button', { type: 'button', class: 'btn btn-ghost-sm', text: '다음', disabled: page >= totalPages - 1 });
  next.addEventListener('click', () => loadDocuments(page + 1));
  dom.pager.append(previous, el('span', { class: 't-mono', text: `${page + 1} / ${totalPages}` }), next);
}

dom.filterForm.addEventListener('submit', (event) => { event.preventDefault(); loadDocuments(0); });
dom.clearFilters.addEventListener('click', () => { dom.filterForm.reset(); loadDocuments(0); });
dom.refresh.addEventListener('click', () => loadDocuments());
dom.closeDetail.addEventListener('click', () => {
  dom.detail.classList.add('hidden');
  const url = new URL(location.href);
  url.searchParams.delete('document');
  history.replaceState(null, '', url);
});

function safeExternalUrl(value) {
  if (!value) return null;
  try {
    const url = new URL(value);
    return url.protocol === 'http:' || url.protocol === 'https:' ? url.href : null;
  } catch {
    return null;
  }
}

function flash(tone, message) {
  const className = tone === 'error' ? 'notice notice--error'
    : tone === 'success' ? 'notice notice--success' : 'notice';
  dom.flash.replaceChildren(el('div', { class: className, text: message }));
  dom.flash.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
}

function clearFlash() {
  dom.flash.replaceChildren();
}

function asApiError(error) {
  return error instanceof ApiError
    ? error
    : new ApiError({ code: 'UNKNOWN', message: error?.message ?? String(error) });
}

syncSourceMode();
boot();
