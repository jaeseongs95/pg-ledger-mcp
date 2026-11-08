// app.js : Zero-build Vanilla ES Module frontend for pg-ledger-mcp Admin Console.
// Security: DOM textContent rendering (no raw innerHTML on untrusted ledger strings),
// strict ^https?:// allowlist on clickable links, X-Requested-With CSRF header.
// UX: Korean IME isComposing guard, stale-response sequence guard, focus restoration on modals/drawer.

const ATTENTION_META = {
  OPEN_WITH_FIX: {
    label: 'OPEN_WITH_FIX',
    title: '수정 커밋 있으나 OPEN 방치',
    desc: 'defect_fix 커밋은 기록되었으나 상태가 SOURCE_FIXED/VERIFIED로 전이되지 않은 결함',
    entity: 'defect'
  },
  FIXED_WITHOUT_COMMIT: {
    label: 'FIXED_WITHOUT_COMMIT',
    title: '커밋 누락 완료 결함',
    desc: '상태가 SOURCE_FIXED 또는 VERIFIED이나 연결된 defect_fix 커밋이 없는 결함',
    entity: 'defect'
  },
  OPEN_NO_OWNER: {
    label: 'OPEN_NO_OWNER',
    title: '담당 세션 미지정 결함',
    desc: '상태가 OPEN이면서 담당 세션(owner_actor_id)이 비어 있는 결함',
    entity: 'defect'
  },
  DONE_NOT_VERIFIED: {
    label: 'DONE_NOT_VERIFIED',
    title: '독립 검수 대기 과업',
    desc: 'REPORTED_DONE 상태로 제출되어 리뷰어 세션의 VERIFIED 검수를 기다리는 과업',
    entity: 'task'
  },
  TASK_UNKNOWN: {
    label: 'TASK_UNKNOWN',
    title: '상태 미상(UNKNOWN) 과업',
    desc: '상태가 UNKNOWN으로 남아 있어 확인이 필요한 과업',
    entity: 'task'
  }
};

const state = {
  authenticated: false,
  instance: 'pg-ledger-mcp',
  mcpPort: 8765,
  adminPort: 8766,
  activeTab: 'overview',
  activeSubtab: 'defects',
  globalProject: '',
  overviewAttentionFilter: '',
  projects: [],
  codes: [],
  defectsPage: { limit: 50, offset: 0, total: 0 },
  tasksPage: { limit: 50, offset: 0, total: 0 },
  historyPage: { limit: 50, offset: 0, total: 0 },
  seq: {
    overview: 0,
    grants: 0,
    defects: 0,
    tasks: 0,
    history: 0,
    settings: 0
  },
  lastFocusBeforeDrawer: null,
  lastFocusBeforeModal: null,
  confirmAction: null
};

// ---------- DOM & Security Helpers ----------

function $(id) {
  return document.getElementById(id);
}

function el(tag, attrs = {}, ...children) {
  const node = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (v === null || v === undefined || v === false) continue;
    if (k === 'className') node.className = v;
    else if (k === 'textContent') node.textContent = String(v);
    else if (k === 'onclick' && typeof v === 'function') node.addEventListener('click', v);
    else if (k === 'dataset' && typeof v === 'object') {
      for (const [dk, dv] of Object.entries(v)) node.dataset[dk] = String(dv);
    } else if (v === true) node.setAttribute(k, '');
    else node.setAttribute(k, String(v));
  }
  for (const child of children.flat()) {
    if (child === null || child === undefined || child === false) continue;
    if (typeof child === 'string' || typeof child === 'number') {
      node.appendChild(document.createTextNode(String(child)));
    } else {
      node.appendChild(child);
    }
  }
  return node;
}

function clearNode(node) {
  while (node.firstChild) node.removeChild(node.firstChild);
}

export function isSafeHttpUrl(raw) {
  if (typeof raw !== 'string') return false;
  const trimmed = raw.trim();
  if (!/^https?:\/\//i.test(trimmed)) return false;
  try {
    const u = new URL(trimmed);
    return u.protocol === 'http:' || u.protocol === 'https:';
  } catch {
    return false;
  }
}

function renderSafeLinkOrCode(uri) {
  if (!uri) return el('span', { className: 'badge badge-muted', textContent: '-' });
  if (isSafeHttpUrl(uri)) {
    return el('a', {
      href: uri.trim(),
      target: '_blank',
      rel: 'noopener noreferrer',
      textContent: uri.trim()
    });
  }
  return el('code', { textContent: String(uri) });
}

function formatDateTime(iso) {
  if (!iso) return '-';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return String(iso);
  return d.toLocaleString('ko-KR', { hour12: false });
}

function severityBadge(sev) {
  if (!sev) return el('span', { className: 'badge badge-muted', textContent: '-' });
  const cls =
    sev === 'P1'
      ? 'badge-p1'
      : sev === 'P2'
        ? 'badge-p2'
        : sev === 'P3'
          ? 'badge-p3'
          : 'badge-warn';
  return el('span', { className: `badge ${cls}`, textContent: sev });
}

function statusBadge(st) {
  if (!st) return el('span', { className: 'badge badge-muted', textContent: '-' });
  let cls = 'badge-muted';
  if (st === 'OPEN' || st === 'BLOCKED' || st === 'NOT_MET' || st === 'UNKNOWN') cls = 'badge-open';
  else if (st === 'SOURCE_FIXED' || st === 'REPORTED_DONE' || st === 'ACTIVE') cls = 'badge-fixed';
  else if (st === 'VERIFIED' || st === 'MET' || st === 'live') cls = 'badge-verified';
  else if (st === 'PLANNED') cls = 'badge-info';
  return el('span', { className: `badge ${cls}`, textContent: st });
}

function showToast(message, isError = false) {
  const region = $('toast-region');
  if (!region) return;
  const item = el('div', {
    className: `toast-item${isError ? ' toast-error' : ''}`,
    textContent: message
  });
  region.appendChild(item);
  setTimeout(() => {
    if (item.parentNode) item.parentNode.removeChild(item);
  }, 4500);
}

// Korean IME composition guard helper
function bindImeSafeEnter(inputEl, onEnter) {
  if (!inputEl) return;
  let composing = false;
  inputEl.addEventListener('compositionstart', () => {
    composing = true;
  });
  inputEl.addEventListener('compositionend', () => {
    composing = false;
  });
  inputEl.addEventListener('keydown', (e) => {
    if (e.isComposing || composing || e.keyCode === 229) return;
    if (e.key === 'Enter') {
      e.preventDefault();
      onEnter();
    }
  });
}

// ---------- API Client ----------

async function apiRequest(path, { method = 'GET', body = null } = {}) {
  const headers = { Accept: 'application/json' };
  if (method !== 'GET' && method !== 'HEAD') {
    headers['X-Requested-With'] = 'pg-ledger-admin';
    headers['Content-Type'] = 'application/json';
  }
  const resp = await fetch(path, {
    method,
    headers,
    credentials: 'same-origin',
    body: body ? JSON.stringify(body) : undefined
  });
  const data = await resp.json().catch(() => ({}));
  if (!resp.ok) {
    if (resp.status === 401 && path !== '/api/auth/login' && path !== '/api/auth/session') {
      showAuthView(data.message || '세션이 만료되었거나 인증이 필요합니다.');
    }
    const err = new Error(data.message || `요청 실패 (HTTP ${resp.status})`);
    err.status = resp.status;
    err.code = data.error || 'HTTP_ERROR';
    throw err;
  }
  return data;
}

// ---------- View Switching & Navigation ----------

function showAuthView(errorMsg = '') {
  state.authenticated = false;
  $('app-view').hidden = true;
  $('auth-view').hidden = false;
  const errEl = $('login-error-msg');
  if (errorMsg) {
    errEl.textContent = errorMsg;
    errEl.hidden = false;
  } else {
    errEl.hidden = true;
  }
  $('login-token-input').focus();
}

function showAppView() {
  state.authenticated = true;
  $('auth-view').hidden = true;
  $('app-view').hidden = false;
}

function switchTab(tabName, { updateHash = true } = {}) {
  const validTabs = ['overview', 'grants', 'explorer', 'settings'];
  if (!validTabs.includes(tabName)) tabName = 'overview';
  state.activeTab = tabName;

  for (const t of validTabs) {
    const btn = $(`tab-btn-${t}`);
    const panel = $(`panel-${t}`);
    const isSelected = t === tabName;
    if (btn) {
      btn.classList.toggle('active', isSelected);
      btn.setAttribute('aria-selected', isSelected ? 'true' : 'false');
      btn.setAttribute('tabindex', isSelected ? '0' : '-1');
    }
    if (panel) panel.hidden = !isSelected;
  }

  if (updateHash) {
    history.replaceState(null, '', `#${tabName}`);
  }

  refreshActiveTab();
}

function switchSubtab(subtabName) {
  const valid = ['defects', 'tasks', 'history'];
  if (!valid.includes(subtabName)) subtabName = 'defects';
  state.activeSubtab = subtabName;

  document.querySelectorAll('.subtab-btn').forEach((btn) => {
    const active = btn.dataset.subtab === subtabName;
    btn.classList.toggle('active', active);
    btn.setAttribute('aria-selected', active ? 'true' : 'false');
  });

  for (const s of valid) {
    const panel = $(`subpanel-${s}`);
    if (panel) panel.hidden = s !== subtabName;
  }

  if (subtabName === 'defects') loadDefects();
  else if (subtabName === 'tasks') loadTasksAndConditions();
  else if (subtabName === 'history') loadHistory();
}

async function refreshActiveTab() {
  await loadProjectsAndCodesMeta();
  if (state.activeTab === 'overview') await loadOverview();
  else if (state.activeTab === 'grants') await loadGrants();
  else if (state.activeTab === 'explorer') switchSubtab(state.activeSubtab);
  else if (state.activeTab === 'settings') await loadSettingsTab();
}

// ---------- Shared Metadata (Projects & Categories) ----------

async function loadProjectsAndCodesMeta() {
  try {
    const [projRes, codesRes] = await Promise.all([
      apiRequest('/api/projects'),
      apiRequest('/api/codes')
    ]);
    state.projects = projRes.items || [];
    state.codes = codesRes.items || [];
    populateProjectSelectors();
    populateCategoryFilter();
  } catch (e) {
    console.error('Failed to load metadata:', e);
  }
}

function populateProjectSelectors() {
  const globalSel = $('global-project-select');
  const currentGlobal = globalSel.value;
  clearNode(globalSel);
  globalSel.appendChild(el('option', { value: '', textContent: '전체 프로젝트' }));
  for (const p of state.projects) {
    globalSel.appendChild(
      el('option', { value: p.id, textContent: `${p.id} (${p.code}) — ${p.title}` })
    );
  }
  globalSel.value = currentGlobal;

  const grantProjSel = $('grant-project-select');
  const currentGrantProj = grantProjSel.value;
  clearNode(grantProjSel);
  grantProjSel.appendChild(el('option', { value: '', textContent: '프로젝트 선택...' }));
  for (const p of state.projects) {
    grantProjSel.appendChild(
      el('option', { value: p.id, textContent: `${p.id} (${p.code})` })
    );
  }
  if (currentGrantProj) grantProjSel.value = currentGrantProj;
  else if (state.globalProject) grantProjSel.value = state.globalProject;
  else if (state.projects.length === 1) grantProjSel.value = state.projects[0].id;
}

function populateCategoryFilter() {
  const catSel = $('defects-category-filter');
  const prev = catSel.value;
  clearNode(catSel);
  catSel.appendChild(el('option', { value: '', textContent: '모든 분류(Category)' }));
  for (const c of state.codes.filter((x) => x.code_group === 'DEFECT_CATEGORY')) {
    catSel.appendChild(
      el('option', {
        value: c.code,
        textContent: `${c.code} — ${c.name_ko}${c.active ? '' : ' (비활성)'}`
      })
    );
  }
  catSel.value = prev;
}

// ---------- Tab 1: Overview & Attention Hub ----------

function setStateBox(boxId, tableWrapId, mode, message, onRetry = null) {
  const box = $(boxId);
  const wrap = tableWrapId ? $(tableWrapId) : null;
  if (!box) return;
  if (mode === 'none') {
    box.hidden = true;
    if (wrap) wrap.hidden = false;
    return;
  }
  clearNode(box);
  box.hidden = false;
  if (wrap) wrap.hidden = true;
  box.appendChild(el('p', { textContent: message }));
  if (onRetry) {
    box.appendChild(
      el('button', {
        type: 'button',
        className: 'btn btn-secondary btn-sm mt-16',
        textContent: '다시 시도',
        onclick: onRetry
      })
    );
  }
}

async function loadOverview() {
  const reqId = ++state.seq.overview;
  setStateBox('overview-attention-state', 'overview-attention-table-wrap', 'loading', '트리아지 및 원장 현황을 불러오는 중입니다...');
  try {
    const q = state.globalProject ? `?project=${encodeURIComponent(state.globalProject)}` : '';
    const data = await apiRequest(`/api/overview${q}`);
    if (reqId !== state.seq.overview) return;

    // Header badges
    const mcpBadge = $('mcp-health-badge');
    if (data.mcpHealth && data.mcpHealth.ok) {
      mcpBadge.className = 'status-pill status-ok';
      mcpBadge.textContent = `MCP 정상 (${state.mcpPort})`;
    } else {
      mcpBadge.className = 'status-pill status-danger';
      mcpBadge.textContent = `MCP 응답 없음 (${state.mcpPort})`;
    }

    const allAttention = data.attention || [];
    const totalAttention = allAttention.length;
    $('header-attention-count').textContent = String(totalAttention);
    const headerAttBtn = $('header-attention-btn');
    headerAttBtn.className = totalAttention > 0 ? 'status-pill status-warn' : 'status-pill status-ok';

    const tabAttBadge = $('tab-badge-attention');
    tabAttBadge.textContent = String(totalAttention);
    tabAttBadge.hidden = totalAttention === 0;
    tabAttBadge.classList.toggle('badge-alert', totalAttention > 0);

    $('tab-badge-grants').textContent = String(data.activeGrantCount ?? 0);

    // Render 5 Attention Cards
    const grid = $('attention-cards-grid');
    clearNode(grid);
    const counts = data.attentionCounts || {};
    for (const [kind, meta] of Object.entries(ATTENTION_META)) {
      const cnt = Number(counts[kind] || 0);
      const isSelected = state.overviewAttentionFilter === kind;
      const card = el(
        'button',
        {
          type: 'button',
          className: `attention-card${cnt > 0 ? ' has-items' : ''}${isSelected ? ' active-filter' : ''}`,
          onclick: () => {
            state.overviewAttentionFilter = state.overviewAttentionFilter === kind ? '' : kind;
            loadOverview();
          }
        },
        el(
          'div',
          { className: 'attention-card-top' },
          el('span', { className: `badge ${cnt > 0 ? 'badge-warn' : 'badge-muted'}`, textContent: meta.label }),
          el('span', { className: 'attention-count', textContent: String(cnt) })
        ),
        el('strong', { textContent: meta.title }),
        el('span', { className: 'card-subtitle', textContent: meta.desc })
      );
      grid.appendChild(card);
    }

    // Filter attention items
    const filteredAttention = state.overviewAttentionFilter
      ? allAttention.filter((x) => x.kind === state.overviewAttentionFilter)
      : allAttention;

    $('attention-filter-indicator').textContent = state.overviewAttentionFilter
      ? `선택된 이상 징후 필터: ${state.overviewAttentionFilter} (${filteredAttention.length}건)`
      : `전체 이상 징후 (${filteredAttention.length}건)`;
    $('clear-attention-filter-btn').hidden = !state.overviewAttentionFilter;

    if (filteredAttention.length === 0) {
      setStateBox(
        'overview-attention-state',
        'overview-attention-table-wrap',
        'empty',
        state.overviewAttentionFilter
          ? `선택한 분류(${state.overviewAttentionFilter})에 해당하는 이상 항목이 없습니다.`
          : '주의가 필요한 이상 항목이 없습니다. 모든 결함과 과업이 정합성 규칙을 충족합니다.'
      );
    } else {
      setStateBox('overview-attention-state', 'overview-attention-table-wrap', 'none', '');
      const tbody = $('overview-attention-tbody');
      clearNode(tbody);
      for (const item of filteredAttention) {
        const tr = el(
          'tr',
          { className: item.entity === 'defect' ? 'clickable-row' : '' },
          el('td', {}, el('span', { className: 'badge badge-warn', textContent: item.kind })),
          el('td', { textContent: item.entity === 'task' ? '과업 (Task)' : '결함 (Defect)' }),
          el('td', {}, el('code', { textContent: item.id })),
          el('td', { textContent: item.project_id }),
          el('td', {}, statusBadge(item.state), ' ', item.severity ? severityBadge(item.severity) : null),
          el('td', { textContent: item.title || '-' }),
          el('td', {}, item.owner_actor_id ? el('code', { textContent: item.owner_actor_id }) : el('span', { className: 'badge badge-muted', textContent: '미지정' })),
          el('td', { textContent: formatDateTime(item.updated_at) }),
          el(
            'td',
            {},
            item.entity === 'defect'
              ? el('button', {
                  type: 'button',
                  className: 'btn btn-secondary btn-sm',
                  textContent: '결함 상세',
                  onclick: (ev) => {
                    ev.stopPropagation();
                    openDefectDrawer(item.id, ev.currentTarget);
                  }
                })
              : el('button', {
                  type: 'button',
                  className: 'btn btn-secondary btn-sm',
                  textContent: '과업 탐색기로 이동',
                  onclick: () => {
                    switchTab('explorer');
                    switchSubtab('tasks');
                  }
                })
          )
        );
        if (item.entity === 'defect') {
          tr.addEventListener('click', () => openDefectDrawer(item.id, tr));
        }
        tbody.appendChild(tr);
      }
    }

    // Render summary metrics
    renderMetricPills('metric-defect-status', data.defectStatusCounts || {}, ['OPEN', 'SOURCE_FIXED', 'VERIFIED', 'MERGED', 'NOT_DEFECT', 'DISPUTED']);
    renderMetricPills('metric-defect-severity', data.defectSeverityCounts || {}, ['P1', 'P2', 'P3', 'UNRATED']);
    renderMetricPills('metric-task-state', data.taskStateCounts || {}, ['PLANNED', 'ACTIVE', 'BLOCKED', 'REPORTED_DONE', 'VERIFIED', 'CANCELLED']);

    const ownerSummary = $('metric-owner-summary');
    clearNode(ownerSummary);
    ownerSummary.appendChild(
      el('div', { className: 'metric-item' }, el('span', { textContent: '활성 세션 권한' }), el('strong', { textContent: String(data.activeGrantCount ?? 0) }))
    );
    ownerSummary.appendChild(
      el('div', { className: 'metric-item' }, el('span', { textContent: '1시간 내 만료 임박' }), el('strong', { textContent: String(data.expiringSoonGrantCount ?? 0) }))
    );
    ownerSummary.appendChild(
      el('div', { className: 'metric-item' }, el('span', { textContent: '등록 프로젝트' }), el('strong', { textContent: String(data.projectCount ?? 0) }))
    );
    ownerSummary.appendChild(
      el('div', { className: 'metric-item' }, el('span', { textContent: '활성 공통코드' }), el('strong', { textContent: String(data.activeCodeCount ?? 0) }))
    );
  } catch (e) {
    if (reqId !== state.seq.overview) return;
    setStateBox('overview-attention-state', 'overview-attention-table-wrap', 'error', e.message, loadOverview);
  }
}

function renderMetricPills(containerId, countsObj, keys) {
  const container = $(containerId);
  if (!container) return;
  clearNode(container);
  for (const k of keys) {
    const val = Number(countsObj[k] || 0);
    container.appendChild(
      el('div', { className: 'metric-item' }, statusBadge(k), el('strong', { textContent: String(val) }))
    );
  }
}

// ---------- Tab 2: Session Grants ----------

async function loadGrants() {
  const reqId = ++state.seq.grants;
  setStateBox('grants-state-box', 'grants-table-wrap', 'loading', '세션 권한 목록을 조회하는 중입니다...');
  try {
    const status = $('grants-status-filter').value;
    const scope = $('grants-scope-filter').value;
    const q = $('grants-search-input').value.trim();
    const project = state.globalProject;

    const params = new URLSearchParams();
    if (status) params.set('status', status);
    if (scope) params.set('scope', scope);
    if (q) params.set('q', q);
    if (project) params.set('project', project);

    const data = await apiRequest(`/api/grants?${params.toString()}`);
    if (reqId !== state.seq.grants) return;

    const items = data.items || [];
    if (items.length === 0) {
      setStateBox(
        'grants-state-box',
        'grants-table-wrap',
        'empty',
        '조건에 해당하는 세션 권한이 없습니다. 좌측 폼에서 새 세션 권한을 부여하거나 필터를 변경해 보세요.'
      );
      return;
    }

    setStateBox('grants-state-box', 'grants-table-wrap', 'none', '');
    const tbody = $('grants-tbody');
    clearNode(tbody);

    for (const g of items) {
      const statusNode = statusBadge(g.status);
      const expSoonNode = g.expiring_soon
        ? el('span', { className: 'badge badge-warn', textContent: '만료 임박 (<1h)' })
        : null;

      const actionsCell = el('td', {});
      if (g.status === 'live') {
        actionsCell.appendChild(
          el('button', {
            type: 'button',
            className: 'btn btn-danger btn-sm',
            textContent: '권한 회수',
            onclick: (ev) =>
              openConfirmModal({
                title: '세션 권한 회수 (Revoke) 확인',
                desc: '회수 즉시 해당 세션의 진행 중인 쓰기 트랜잭션이 SESSION_NOT_GRANTED 오류로 차단됩니다. 계속하시겠습니까?',
                detail: `작성자: ${g.author_id}\n프로젝트: ${g.project}\n회수 범위: ${g.scope}`,
                triggerEl: ev.currentTarget,
                onConfirm: async () => {
                  await apiRequest('/api/grants/revoke', {
                    method: 'POST',
                    body: { author_id: g.author_id, project: g.project, scope: g.scope }
                  });
                  showToast(`권한 회수 완료: ${g.author_id} (${g.project}/${g.scope})`);
                  await loadGrants();
                  await loadOverview();
                }
              })
          })
        );
      } else {
        actionsCell.appendChild(el('span', { className: 'badge badge-muted', textContent: '종료됨' }));
      }

      tbody.appendChild(
        el(
          'tr',
          {},
          el('td', {}, el('code', { textContent: String(g.id) })),
          el('td', {}, el('code', { textContent: g.author_id })),
          el('td', { textContent: g.project }),
          el('td', {}, el('span', { className: 'badge badge-info', textContent: g.scope })),
          el('td', {}, statusNode, expSoonNode ? ' ' : null, expSoonNode),
          el(
            'td',
            {},
            el('div', { textContent: `부여: ${formatDateTime(g.granted_at)}` }),
            el('div', {
              className: 'field-hint',
              textContent: g.expires_at ? `만료: ${formatDateTime(g.expires_at)}` : '만료: 무기한'
            })
          ),
          el('td', { textContent: g.note || '-' }),
          actionsCell
        )
      );
    }
  } catch (e) {
    if (reqId !== state.seq.grants) return;
    setStateBox('grants-state-box', 'grants-table-wrap', 'error', e.message, loadGrants);
  }
}

// ---------- Tab 3-1: Defects Explorer & Detail Drawer ----------

async function loadDefects() {
  const reqId = ++state.seq.defects;
  setStateBox('defects-state-box', 'defects-table-wrap', 'loading', '결함 원장을 불러오는 중입니다...');
  try {
    const params = new URLSearchParams();
    if (state.globalProject) params.set('project', state.globalProject);
    const status = $('defects-status-filter').value;
    const severity = $('defects-severity-filter').value;
    const category = $('defects-category-filter').value;
    const attention = $('defects-attention-filter').value;
    const q = $('defects-search-input').value.trim();

    if (status) params.set('status', status);
    if (severity) params.set('severity', severity);
    if (category) params.set('category', category);
    if (attention) params.set('attention', attention);
    if (q) params.set('q', q);
    params.set('limit', String(state.defectsPage.limit));
    params.set('offset', String(state.defectsPage.offset));

    const data = await apiRequest(`/api/defects?${params.toString()}`);
    if (reqId !== state.seq.defects) return;

    const items = data.items || [];
    state.defectsPage.total = Number(data.total || 0);
    const start = state.defectsPage.total === 0 ? 0 : state.defectsPage.offset + 1;
    const end = Math.min(state.defectsPage.total, state.defectsPage.offset + items.length);
    $('defects-page-info').textContent = `전체 ${state.defectsPage.total}건 중 ${start}–${end}건 표시`;
    $('defects-prev-btn').disabled = state.defectsPage.offset <= 0;
    $('defects-next-btn').disabled = state.defectsPage.offset + state.defectsPage.limit >= state.defectsPage.total;

    if (items.length === 0) {
      setStateBox('defects-state-box', 'defects-table-wrap', 'empty', '검색·필터 조건에 일치하는 결함이 없습니다.');
      return;
    }

    setStateBox('defects-state-box', 'defects-table-wrap', 'none', '');
    const tbody = $('defects-tbody');
    clearNode(tbody);

    for (const d of items) {
      const casesCount = Array.isArray(d.cases) ? d.cases.length : 0;
      const fixesCount = Array.isArray(d.fixes) ? d.fixes.length : 0;
      const tr = el(
        'tr',
        { className: 'clickable-row' },
        el('td', {}, el('code', { textContent: d.id })),
        el('td', { textContent: d.project_id }),
        el('td', {}, severityBadge(d.severity)),
        el('td', {}, el('span', { className: 'badge badge-info', textContent: d.category })),
        el('td', {}, statusBadge(d.status), d.merged_into ? el('div', { className: 'field-hint', textContent: `→ ${d.merged_into}` }) : null),
        el('td', { textContent: d.title }),
        el(
          'td',
          {},
          el('div', { textContent: `담당: ${d.owner_actor_id || '-'}` }),
          d.fixed_by ? el('div', { className: 'field-hint', textContent: `수정: ${d.fixed_by}` }) : null
        ),
        el('td', { textContent: `케이스 ${casesCount} / 픽스 ${fixesCount}` }),
        el('td', { textContent: formatDateTime(d.updated_at) }),
        el(
          'td',
          {},
          el('button', {
            type: 'button',
            className: 'btn btn-secondary btn-sm',
            textContent: '상세 보기',
            onclick: (ev) => {
              ev.stopPropagation();
              openDefectDrawer(d.id, ev.currentTarget);
            }
          })
        )
      );
      tr.addEventListener('click', () => openDefectDrawer(d.id, tr));
      tbody.appendChild(tr);
    }
  } catch (e) {
    if (reqId !== state.seq.defects) return;
    setStateBox('defects-state-box', 'defects-table-wrap', 'error', e.message, loadDefects);
  }
}

async function openDefectDrawer(defectId, triggerEl = null) {
  state.lastFocusBeforeDrawer = triggerEl || document.activeElement;
  $('drawer-backdrop').hidden = false;
  const drawer = $('detail-drawer');
  drawer.hidden = false;
  $('drawer-title').textContent = `${defectId} 불러오는 중...`;
  clearNode($('drawer-badges'));
  const body = $('drawer-body');
  clearNode(body);
  body.appendChild(el('div', { className: 'state-box', textContent: '결함 상세 정보를 불러오는 중입니다...' }));
  $('drawer-close-btn').focus();

  try {
    const data = await apiRequest(`/api/defects/${encodeURIComponent(defectId)}`);
    const d = data.defect;
    $('drawer-title').textContent = `${d.id}: ${d.title}`;
    const badges = $('drawer-badges');
    clearNode(badges);
    badges.appendChild(severityBadge(d.severity));
    badges.appendChild(document.createTextNode(' '));
    badges.appendChild(el('span', { className: 'badge badge-info', textContent: d.category }));
    badges.appendChild(document.createTextNode(' '));
    badges.appendChild(statusBadge(d.status));

    clearNode(body);

    // 1. Metadata section
    const metaSec = el(
      'div',
      { className: 'detail-section' },
      el('h3', { textContent: '기본 메타데이터 및 작성자/수정자 추적' }),
      el(
        'div',
        { className: 'kv-grid' },
        kvItem('프로젝트', d.project_id),
        kvItem('버전 (version)', String(d.version)),
        kvItem('최초 등록 세션 (created_by)', d.created_by),
        kvItem('현재 담당 세션 (owner_actor_id)', d.owner_actor_id || '-'),
        kvItem('최종 수정 세션 (fixed_by)', d.fixed_by || '-'),
        kvItem('최종 수정 시각 (fixed_at)', formatDateTime(d.fixed_at)),
        kvItem('병합 대상 (merged_into)', d.merged_into || '-'),
        kvItem('최종 변경 시각', formatDateTime(d.updated_at))
      )
    );
    body.appendChild(metaSec);

    // 2. Behavior & Root Cause
    const descSec = el(
      'div',
      { className: 'detail-section' },
      el('h3', { textContent: '결함 현상 (Behavior) 및 근본 원인 (Root Cause)' }),
      el('div', { className: 'timeline-item' }, el('strong', { textContent: '현상 (behavior): ' }), el('p', { textContent: d.behavior || '-' })),
      el('div', { className: 'timeline-item' }, el('strong', { textContent: '원인 (root_cause): ' }), el('p', { textContent: d.root_cause || '-' }))
    );
    if (d.evidence_uri) {
      descSec.appendChild(
        el('div', { className: 'timeline-item' }, el('strong', { textContent: '증빙 URI: ' }), renderSafeLinkOrCode(d.evidence_uri))
      );
    }
    body.appendChild(descSec);

    // 3. Fixes
    const fixes = Array.isArray(d.fixes) ? d.fixes : [];
    const fixSec = el('div', { className: 'detail-section' }, el('h3', { textContent: `수정 커밋 내역 (fixes: ${fixes.length}건)` }));
    if (fixes.length === 0) {
      fixSec.appendChild(el('p', { className: 'card-subtitle', textContent: '기록된 수정 커밋이 없습니다.' }));
    } else {
      for (const f of fixes) {
        fixSec.appendChild(
          el(
            'div',
            { className: 'timeline-item' },
            el('div', {}, el('code', { textContent: f.commit || '-' }), ' · ', el('span', { textContent: `${f.repo || ''} (${f.branch || '-'})` })),
            el('div', { className: 'field-hint', textContent: `기록 세션: ${f.by || '-'} · CI 결론: ${f.ci_conclusion || '없음'}` }),
            f.ci ? el('div', {}, 'CI 링크: ', renderSafeLinkOrCode(f.ci)) : null
          )
        );
      }
    }
    body.appendChild(fixSec);

    // 4. Cases (including recursive merged_behavior)
    const cases = Array.isArray(d.cases) ? d.cases : [];
    const caseSec = el('div', { className: 'detail-section' }, el('h3', { textContent: `관측 케이스 및 병합 사례 (cases: ${cases.length}건)` }));
    if (cases.length === 0) {
      caseSec.appendChild(el('p', { className: 'card-subtitle', textContent: '추가 관측 케이스가 없습니다.' }));
    } else {
      for (const c of cases) {
        caseSec.appendChild(
          el(
            'div',
            { className: 'timeline-item' },
            el(
              'div',
              {},
              el('span', { className: 'badge badge-info', textContent: c.source || 'case' }),
              ' ',
              el('code', { textContent: c.defect || d.id }),
              ' · ',
              el('span', { className: 'field-hint', textContent: `${c.by || '-'} (${formatDateTime(c.at)})` })
            ),
            el('p', { textContent: c.observation || '-' }),
            c.user_quote ? el('div', { className: 'field-hint', textContent: `인용: ${c.user_quote}` }) : null,
            c.evidence ? el('div', { className: 'field-hint', textContent: `증거: ${c.evidence}` }) : null
          )
        );
      }
    }
    body.appendChild(caseSec);

    // 5. History
    const hist = Array.isArray(data.history) ? data.history : [];
    const histSec = el('div', { className: 'detail-section' }, el('h3', { textContent: `감사 이력 타임라인 (${hist.length}건)` }));
    for (const h of hist) {
      histSec.appendChild(
        el(
          'div',
          { className: 'timeline-item' },
          el(
            'div',
            {},
            el('code', { textContent: `#${h.id}` }),
            ' ',
            el('span', { className: 'badge badge-info', textContent: h.op }),
            ' ',
            h.from_state || h.to_state ? el('span', { textContent: `${h.from_state || '∅'} → ${h.to_state || '∅'}` }) : null,
            ' · ',
            el('span', { className: 'field-hint', textContent: `${h.author_id} (${h.login}) · ${formatDateTime(h.at)}` })
          ),
          el('div', { textContent: h.reason || '-' }),
          h.evidence_uri ? el('div', {}, '증빙: ', renderSafeLinkOrCode(h.evidence_uri)) : null
        )
      );
    }
    body.appendChild(histSec);
  } catch (e) {
    clearNode(body);
    body.appendChild(el('div', { className: 'error-banner', textContent: e.message }));
  }
}

function kvItem(label, val) {
  return el(
    'div',
    { className: 'kv-item' },
    el('div', { className: 'kv-label', textContent: label }),
    el('div', { className: 'kv-value', textContent: val ?? '-' })
  );
}

function closeDefectDrawer() {
  $('detail-drawer').hidden = true;
  $('drawer-backdrop').hidden = true;
  if (state.lastFocusBeforeDrawer && typeof state.lastFocusBeforeDrawer.focus === 'function') {
    state.lastFocusBeforeDrawer.focus();
  }
  state.lastFocusBeforeDrawer = null;
}

// ---------- Tab 3-2: Tasks & Conditions ----------

async function loadTasksAndConditions() {
  const reqId = ++state.seq.tasks;
  setStateBox('tasks-state-box', 'tasks-table-wrap', 'loading', '과업 및 조건 목록을 불러오는 중입니다...');
  try {
    const params = new URLSearchParams();
    if (state.globalProject) params.set('project', state.globalProject);
    const st = $('tasks-state-filter').value;
    const q = $('tasks-search-input').value.trim();
    if (st) params.set('state', st);
    if (q) params.set('q', q);
    params.set('limit', String(state.tasksPage.limit));
    params.set('offset', String(state.tasksPage.offset));

    const data = await apiRequest(`/api/tasks?${params.toString()}`);
    if (reqId !== state.seq.tasks) return;

    const tasks = data.tasks || [];
    state.tasksPage.total = Number(data.totalTasks || 0);
    const start = state.tasksPage.total === 0 ? 0 : state.tasksPage.offset + 1;
    const end = Math.min(state.tasksPage.total, state.tasksPage.offset + tasks.length);
    $('tasks-page-info').textContent = `전체 ${state.tasksPage.total}건 중 ${start}–${end}건 표시`;
    $('tasks-prev-btn').disabled = state.tasksPage.offset <= 0;
    $('tasks-next-btn').disabled = state.tasksPage.offset + state.tasksPage.limit >= state.tasksPage.total;

    if (tasks.length === 0) {
      setStateBox('tasks-state-box', 'tasks-table-wrap', 'empty', '조건에 해당하는 과업이 없습니다.');
    } else {
      setStateBox('tasks-state-box', 'tasks-table-wrap', 'none', '');
      const tbody = $('tasks-tbody');
      clearNode(tbody);
      for (const t of tasks) {
        const linkedDefects = Array.isArray(t.linked_defects) ? t.linked_defects.join(', ') : '';
        const linkedConds = Array.isArray(t.linked_conditions) ? t.linked_conditions.join(', ') : '';
        tbody.appendChild(
          el(
            'tr',
            {},
            el('td', {}, el('code', { textContent: t.id })),
            el('td', { textContent: t.project_id }),
            el('td', {}, statusBadge(t.state)),
            el(
              'td',
              {},
              el('strong', { textContent: t.title }),
              el('div', { className: 'field-hint', textContent: t.purpose })
            ),
            el('td', { textContent: t.success_condition }),
            el(
              'td',
              {},
              el('div', { textContent: `담당: ${t.owner_actor_id || '-'}` }),
              el('div', { className: 'field-hint', textContent: `생성: ${t.created_by}` })
            ),
            el(
              'td',
              {},
              el('div', { textContent: `결함: ${linkedDefects || '-'}` }),
              el('div', { className: 'field-hint', textContent: `조건: ${linkedConds || '-'}` })
            ),
            el('td', { textContent: formatDateTime(t.updated_at) })
          )
        );
      }
    }

    const conds = data.conditions || [];
    if (conds.length === 0) {
      setStateBox('conditions-state-box', 'conditions-table-wrap', 'empty', '등록된 릴리스 조건이 없습니다.');
    } else {
      setStateBox('conditions-state-box', 'conditions-table-wrap', 'none', '');
      const ctbody = $('conditions-tbody');
      clearNode(ctbody);
      for (const c of conds) {
        const linksText = Array.isArray(c.links)
          ? c.links.map((l) => l.task_id || l.defect_id).filter(Boolean).join(', ')
          : '';
        ctbody.appendChild(
          el(
            'tr',
            {},
            el('td', {}, el('code', { textContent: c.id })),
            el('td', { textContent: c.project_id }),
            el('td', { textContent: `${c.release}${c.goal_label ? ` (${c.goal_label})` : ''}` }),
            el('td', {}, el('code', { textContent: c.code })),
            el('td', {}, statusBadge(c.state)),
            el('td', { textContent: c.condition_text }),
            el('td', { textContent: linksText || '-' }),
            el('td', { textContent: formatDateTime(c.updated_at) })
          )
        );
      }
    }
  } catch (e) {
    if (reqId !== state.seq.tasks) return;
    setStateBox('tasks-state-box', 'tasks-table-wrap', 'error', e.message, loadTasksAndConditions);
  }
}

// ---------- Tab 3-3: Audit History ----------

async function loadHistory() {
  const reqId = ++state.seq.history;
  setStateBox('history-state-box', 'history-table-wrap', 'loading', '감사 이력을 조회하는 중입니다...');
  try {
    const params = new URLSearchParams();
    if (state.globalProject) params.set('project', state.globalProject);
    const entity = $('history-entity-filter').value;
    const op = $('history-op-filter').value;
    const entityId = $('history-entity-id-input').value.trim();
    const author = $('history-author-input').value.trim();
    const q = $('history-search-input').value.trim();

    if (entity) params.set('entity', entity);
    if (op) params.set('op', op);
    if (entityId) params.set('entity_id', entityId);
    if (author) params.set('author', author);
    if (q) params.set('q', q);
    params.set('limit', String(state.historyPage.limit));
    params.set('offset', String(state.historyPage.offset));

    const data = await apiRequest(`/api/history?${params.toString()}`);
    if (reqId !== state.seq.history) return;

    const items = data.items || [];
    state.historyPage.total = Number(data.total || 0);
    const start = state.historyPage.total === 0 ? 0 : state.historyPage.offset + 1;
    const end = Math.min(state.historyPage.total, state.historyPage.offset + items.length);
    $('history-page-info').textContent = `전체 ${state.historyPage.total}건 중 ${start}–${end}건 표시`;
    $('history-prev-btn').disabled = state.historyPage.offset <= 0;
    $('history-next-btn').disabled = state.historyPage.offset + state.historyPage.limit >= state.historyPage.total;

    if (items.length === 0) {
      setStateBox('history-state-box', 'history-table-wrap', 'empty', '검색 조건에 일치하는 감사 이력이 없습니다.');
      return;
    }

    setStateBox('history-state-box', 'history-table-wrap', 'none', '');
    const tbody = $('history-tbody');
    clearNode(tbody);

    for (const h of items) {
      const changesStr = h.changes && Object.keys(h.changes).length > 0 ? JSON.stringify(h.changes, null, 2) : '';
      tbody.appendChild(
        el(
          'tr',
          {},
          el('td', {}, el('code', { textContent: `#${h.id}` }), h.corrects_id ? el('div', { className: 'field-hint', textContent: `정정 대상: #${h.corrects_id}` }) : null),
          el('td', { textContent: formatDateTime(h.at) }),
          el('td', {}, el('span', { className: 'badge badge-muted', textContent: h.entity }), ' ', el('code', { textContent: h.entity_id })),
          el('td', {}, el('span', { className: 'badge badge-info', textContent: h.op })),
          el('td', { textContent: h.from_state || h.to_state ? `${h.from_state || '∅'} → ${h.to_state || '∅'}` : '-' }),
          el(
            'td',
            {},
            el('code', { textContent: h.author_id }),
            el('div', { className: 'field-hint', textContent: `${h.login} (${h.actor_assurance})` })
          ),
          el(
            'td',
            {},
            el('div', { textContent: h.reason }),
            h.evidence_uri ? el('div', { className: 'mt-16' }, '증빙: ', renderSafeLinkOrCode(h.evidence_uri)) : null
          ),
          el('td', {}, changesStr ? el('pre', { className: 'json-pre', textContent: changesStr }) : '-')
        )
      );
    }
  } catch (e) {
    if (reqId !== state.seq.history) return;
    setStateBox('history-state-box', 'history-table-wrap', 'error', e.message, loadHistory);
  }
}

// ---------- Tab 4: Projects & Common Codes ----------

async function loadSettingsTab() {
  const reqId = ++state.seq.settings;
  await loadProjectsAndCodesMeta();
  if (reqId !== state.seq.settings) return;
  renderProjectsTable();
  renderCodesTable();
}

function renderProjectsTable() {
  const items = state.projects || [];
  if (items.length === 0) {
    setStateBox('projects-state-box', 'projects-table-wrap', 'empty', '등록된 프로젝트가 없습니다. 좌측 폼에서 첫 프로젝트를 추가하세요.');
    return;
  }
  setStateBox('projects-state-box', 'projects-table-wrap', 'none', '');
  const tbody = $('projects-tbody');
  clearNode(tbody);
  for (const p of items) {
    tbody.appendChild(
      el(
        'tr',
        {},
        el('td', {}, el('code', { textContent: p.id })),
        el('td', {}, el('span', { className: 'badge badge-info', textContent: p.code })),
        el('td', { textContent: p.title }),
        el('td', { textContent: `${p.open_defect_count} / ${p.defect_count}` }),
        el('td', { textContent: String(p.task_count) }),
        el('td', { textContent: String(p.active_grant_count) }),
        el('td', {}, el('code', { textContent: p.created_by }))
      )
    );
  }
}

function renderCodesTable() {
  const groupFilter = $('codes-group-filter').value;
  const items = (state.codes || []).filter((c) => !groupFilter || c.code_group === groupFilter);
  if (items.length === 0) {
    setStateBox('codes-state-box', 'codes-table-wrap', 'empty', '등록된 공통 코드가 없습니다.');
    return;
  }
  setStateBox('codes-state-box', 'codes-table-wrap', 'none', '');
  const tbody = $('codes-tbody');
  clearNode(tbody);
  for (const c of items) {
    tbody.appendChild(
      el(
        'tr',
        {},
        el('td', {}, el('code', { textContent: c.code_group })),
        el('td', {}, el('strong', { textContent: c.code })),
        el(
          'td',
          {},
          el('div', { textContent: c.name_ko }),
          el('div', { className: 'field-hint', textContent: c.name_en })
        ),
        el(
          'td',
          {},
          el('div', { textContent: c.description_ko }),
          el('div', { className: 'field-hint', textContent: c.description_en })
        ),
        el('td', { textContent: String(c.sort_order) }),
        el(
          'td',
          {},
          c.active
            ? el('span', { className: 'badge badge-verified', textContent: '활성 (Active)' })
            : el('span', { className: 'badge badge-muted', textContent: '비활성 (Retired)' })
        ),
        el(
          'td',
          {},
          el(
            'div',
            { className: 'form-actions' },
            el('button', {
              type: 'button',
              className: 'btn btn-secondary btn-sm',
              textContent: '편집',
              onclick: () => populateCodeForm(c)
            }),
            el('button', {
              type: 'button',
              className: `btn ${c.active ? 'btn-danger' : 'btn-primary'} btn-sm`,
              textContent: c.active ? '비활성화(Retire)' : '재활성화',
              onclick: (ev) => {
                if (c.active) {
                  openConfirmModal({
                    title: '공통 코드 비활성화 (Retire) 확인',
                    desc: '공통 코드는 삭제되지 않으며 비활성화(active = false)됩니다. 기존 행은 유지되나 신규 작성 시 선택할 수 없으며, 즉시 DBHub 도구 설명이 재생성 및 재시작됩니다.',
                    detail: `그룹: ${c.code_group}\n코드: ${c.code} (${c.name_ko})`,
                    triggerEl: ev.currentTarget,
                    onConfirm: () => submitCodeChange({ ...c, active: false })
                  });
                } else {
                  openConfirmModal({
                    title: '공통 코드 재활성화 (Activate) 확인',
                    desc: '비활성화된 공통 코드를 다시 활성(active = true) 상태로 전환합니다. 신규 결함·링크 작성 시 선택할 수 있게 되며, 즉시 DBHub 도구 설명이 재생성 및 재시작됩니다.',
                    detail: `그룹: ${c.code_group}\n코드: ${c.code} (${c.name_ko})`,
                    triggerEl: ev.currentTarget,
                    onConfirm: () => submitCodeChange({ ...c, active: true })
                  });
                }
              }
            })
          )
        )
      )
    );
  }
}

function populateCodeForm(c) {
  $('code-group-select').value = c.code_group;
  $('code-value-input').value = c.code;
  $('code-sort-input').value = String(c.sort_order ?? 0);
  $('code-name-ko-input').value = c.name_ko || '';
  $('code-desc-ko-input').value = c.description_ko || '';
  $('code-name-en-input').value = c.name_en || '';
  $('code-desc-en-input').value = c.description_en || '';
  $('code-active-checkbox').checked = Boolean(c.active);
  $('code-value-input').focus();
}

async function submitCodeChange(payload) {
  const banner = $('code-sync-banner');
  const titleEl = $('code-sync-title');
  const s1 = $('sync-step-1');
  const s2 = $('sync-step-2');
  const s3 = $('sync-step-3');
  banner.hidden = false;
  s1.className = 'active';
  s2.className = 'active';
  s3.className = '';
  titleEl.textContent = `공통 코드(${payload.code_group}/${payload.code}) 반영 및 DBHub 재시작 동기화 요청 중...`;

  const submitBtn = $('code-submit-btn');
  submitBtn.disabled = true;
  $('code-form-error').hidden = true;

  try {
    const res = await apiRequest('/api/codes', { method: 'POST', body: payload });
    const steps = Array.isArray(res.sync?.steps) ? res.sync.steps : [];
    const healthOk = res.sync?.mcpHealth?.ok !== false;
    s1.className = steps.includes('DB_UPDATED') ? 'done' : '';
    s2.className = steps.includes('DBHUB_RESTARTED') ? 'done' : '';
    s3.className = healthOk ? 'done' : 'failed';
    titleEl.textContent = healthOk
      ? `동기화 완료 (${res.sync?.durationMs ?? 0}ms) — DBHub /healthz 정상 확인됨`
      : `동기화 경고 (${res.sync?.durationMs ?? 0}ms) — DBHub /healthz 응답을 확인하세요`;
    showToast(`공통 코드 ${payload.code_group}/${payload.code} 반영 및 DBHub 동기화 완료`);
    await loadSettingsTab();
    await loadOverview();
  } catch (e) {
    s1.className = 'failed';
    s2.className = 'failed';
    s3.className = 'failed';
    titleEl.textContent = `동기화 실패: ${e.message}`;
    const errBanner = $('code-form-error');
    errBanner.textContent = e.message;
    errBanner.hidden = false;
    showToast(e.message, true);
  } finally {
    submitBtn.disabled = false;
  }
}

// ---------- 2-Step Confirmation Modal ----------

function openConfirmModal({ title, desc, detail, triggerEl, onConfirm }) {
  state.lastFocusBeforeModal = triggerEl || document.activeElement;
  state.confirmAction = onConfirm;
  $('confirm-modal-title').textContent = title;
  $('confirm-modal-desc').textContent = desc;
  $('confirm-modal-detail').textContent = detail || '';
  $('confirm-modal-backdrop').hidden = false;
  $('confirm-cancel-btn').focus();
}

function closeConfirmModal() {
  $('confirm-modal-backdrop').hidden = true;
  state.confirmAction = null;
  if (state.lastFocusBeforeModal && typeof state.lastFocusBeforeModal.focus === 'function') {
    state.lastFocusBeforeModal.focus();
  }
  state.lastFocusBeforeModal = null;
}

// ---------- Event Listeners & Initialization ----------

function initEvents() {
  // Login form
  $('toggle-token-visibility').addEventListener('click', () => {
    const inp = $('login-token-input');
    const isPw = inp.type === 'password';
    inp.type = isPw ? 'text' : 'password';
    $('toggle-token-visibility').textContent = isPw ? '숨김' : '표시';
  });

  $('login-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    const token = $('login-token-input').value.trim();
    const btn = $('login-submit-btn');
    btn.disabled = true;
    $('login-error-msg').hidden = true;
    try {
      const res = await apiRequest('/api/auth/login', { method: 'POST', body: { token } });
      $('login-token-input').value = '';
      showAppView();
      const hashTab = location.hash.replace(/^#/, '');
      switchTab(hashTab || 'overview');
      showToast(`${res.instance} 관리자 콘솔에 로그인되었습니다.`);
    } catch (err) {
      const errEl = $('login-error-msg');
      errEl.textContent = err.message;
      errEl.hidden = false;
    } finally {
      btn.disabled = false;
    }
  });

  $('logout-btn').addEventListener('click', async () => {
    await apiRequest('/api/auth/logout', { method: 'POST' }).catch(() => {});
    showAuthView('로그아웃되었습니다.');
  });

  $('global-refresh-btn').addEventListener('click', () => refreshActiveTab());
  $('header-attention-btn').addEventListener('click', () => switchTab('overview'));

  $('global-project-select').addEventListener('change', (e) => {
    state.globalProject = e.target.value;
    state.defectsPage.offset = 0;
    state.tasksPage.offset = 0;
    state.historyPage.offset = 0;
    refreshActiveTab();
  });

  // Primary Tabs + Keyboard Arrow Navigation
  const tabBtns = Array.from(document.querySelectorAll('.tab-btn'));
  tabBtns.forEach((btn, idx) => {
    btn.addEventListener('click', () => switchTab(btn.dataset.tab));
    btn.addEventListener('keydown', (e) => {
      let nextIdx = -1;
      if (e.key === 'ArrowRight') nextIdx = (idx + 1) % tabBtns.length;
      else if (e.key === 'ArrowLeft') nextIdx = (idx - 1 + tabBtns.length) % tabBtns.length;
      else if (e.key === 'Home') nextIdx = 0;
      else if (e.key === 'End') nextIdx = tabBtns.length - 1;
      if (nextIdx >= 0) {
        e.preventDefault();
        tabBtns[nextIdx].focus();
        switchTab(tabBtns[nextIdx].dataset.tab);
      }
    });
  });

  // Subtabs
  document.querySelectorAll('.subtab-btn').forEach((btn) => {
    btn.addEventListener('click', () => switchSubtab(btn.dataset.subtab));
  });

  $('clear-attention-filter-btn').addEventListener('click', () => {
    state.overviewAttentionFilter = '';
    loadOverview();
  });

  // Grants Form & Quick Pills
  document.querySelectorAll('[data-prefix]').forEach((btn) => {
    btn.addEventListener('click', () => {
      const inp = $('grant-author-input');
      const prefix = btn.dataset.prefix;
      const cur = inp.value.trim();
      const withoutVendor = cur.includes(':') ? cur.split(':').slice(1).join(':') : cur;
      inp.value = `${prefix}${withoutVendor}`;
      inp.focus();
    });
  });

  document.querySelectorAll('[data-expiry-hours]').forEach((btn) => {
    btn.addEventListener('click', () => {
      const hrs = Number(btn.dataset.expiryHours);
      const inp = $('grant-expires-input');
      if (!hrs) {
        inp.value = '';
        return;
      }
      const d = new Date(Date.now() + hrs * 3600 * 1000);
      const pad = (n) => String(n).padStart(2, '0');
      inp.value = `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
    });
  });

  $('grant-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    const errEl = $('grant-form-error');
    errEl.hidden = true;
    const authorId = $('grant-author-input').value.trim();
    const project = $('grant-project-select').value;
    const scopes = Array.from(document.querySelectorAll('input[name="grant_scope"]:checked')).map((el) => el.value);
    const expiresRaw = $('grant-expires-input').value;
    const expiresAt = expiresRaw ? new Date(expiresRaw).toISOString() : '';
    const note = $('grant-note-input').value.trim();

    const btn = $('grant-submit-btn');
    btn.disabled = true;
    try {
      await apiRequest('/api/grants', {
        method: 'POST',
        body: { author_id: authorId, project, scopes, expires_at: expiresAt, note }
      });
      showToast(`세션 권한 부여 완료: ${authorId} (${project}: ${scopes.join(', ')})`);
      $('grant-note-input').value = '';
      await loadGrants();
      await loadOverview();
    } catch (err) {
      errEl.textContent = err.message;
      errEl.hidden = false;
    } finally {
      btn.disabled = false;
    }
  });

  $('grants-status-filter').addEventListener('change', loadGrants);
  $('grants-scope-filter').addEventListener('change', loadGrants);
  bindImeSafeEnter($('grants-search-input'), loadGrants);
  $('grants-reset-filter-btn').addEventListener('click', () => {
    $('grants-status-filter').value = 'live';
    $('grants-scope-filter').value = '';
    $('grants-search-input').value = '';
    loadGrants();
  });

  // Defects Filters & Pagination
  const resetDefectsOffsetAndLoad = () => {
    state.defectsPage.offset = 0;
    loadDefects();
  };
  $('defects-status-filter').addEventListener('change', resetDefectsOffsetAndLoad);
  $('defects-severity-filter').addEventListener('change', resetDefectsOffsetAndLoad);
  $('defects-category-filter').addEventListener('change', resetDefectsOffsetAndLoad);
  $('defects-attention-filter').addEventListener('change', resetDefectsOffsetAndLoad);
  $('defects-search-btn').addEventListener('click', resetDefectsOffsetAndLoad);
  bindImeSafeEnter($('defects-search-input'), resetDefectsOffsetAndLoad);
  $('defects-reset-btn').addEventListener('click', () => {
    $('defects-status-filter').value = '';
    $('defects-severity-filter').value = '';
    $('defects-category-filter').value = '';
    $('defects-attention-filter').value = '';
    $('defects-search-input').value = '';
    resetDefectsOffsetAndLoad();
  });
  $('defects-limit-select').addEventListener('change', (e) => {
    state.defectsPage.limit = Number(e.target.value) || 50;
    state.defectsPage.offset = 0;
    loadDefects();
  });
  $('defects-prev-btn').addEventListener('click', () => {
    state.defectsPage.offset = Math.max(0, state.defectsPage.offset - state.defectsPage.limit);
    loadDefects();
  });
  $('defects-next-btn').addEventListener('click', () => {
    state.defectsPage.offset += state.defectsPage.limit;
    loadDefects();
  });

  // Tasks Filters & Pagination
  const resetTasksOffsetAndLoad = () => {
    state.tasksPage.offset = 0;
    loadTasksAndConditions();
  };
  $('tasks-state-filter').addEventListener('change', resetTasksOffsetAndLoad);
  $('tasks-search-btn').addEventListener('click', resetTasksOffsetAndLoad);
  bindImeSafeEnter($('tasks-search-input'), resetTasksOffsetAndLoad);
  $('tasks-reset-btn').addEventListener('click', () => {
    $('tasks-state-filter').value = '';
    $('tasks-search-input').value = '';
    resetTasksOffsetAndLoad();
  });
  $('tasks-limit-select').addEventListener('change', (e) => {
    state.tasksPage.limit = Number(e.target.value) || 50;
    state.tasksPage.offset = 0;
    loadTasksAndConditions();
  });
  $('tasks-prev-btn').addEventListener('click', () => {
    state.tasksPage.offset = Math.max(0, state.tasksPage.offset - state.tasksPage.limit);
    loadTasksAndConditions();
  });
  $('tasks-next-btn').addEventListener('click', () => {
    state.tasksPage.offset += state.tasksPage.limit;
    loadTasksAndConditions();
  });

  // History Filters & Pagination
  const resetHistoryOffsetAndLoad = () => {
    state.historyPage.offset = 0;
    loadHistory();
  };
  $('history-entity-filter').addEventListener('change', resetHistoryOffsetAndLoad);
  $('history-op-filter').addEventListener('change', resetHistoryOffsetAndLoad);
  $('history-search-btn').addEventListener('click', resetHistoryOffsetAndLoad);
  bindImeSafeEnter($('history-entity-id-input'), resetHistoryOffsetAndLoad);
  bindImeSafeEnter($('history-author-input'), resetHistoryOffsetAndLoad);
  bindImeSafeEnter($('history-search-input'), resetHistoryOffsetAndLoad);
  $('history-reset-btn').addEventListener('click', () => {
    $('history-entity-filter').value = '';
    $('history-op-filter').value = '';
    $('history-entity-id-input').value = '';
    $('history-author-input').value = '';
    $('history-search-input').value = '';
    resetHistoryOffsetAndLoad();
  });
  $('history-prev-btn').addEventListener('click', () => {
    state.historyPage.offset = Math.max(0, state.historyPage.offset - state.historyPage.limit);
    loadHistory();
  });
  $('history-next-btn').addEventListener('click', () => {
    state.historyPage.offset += state.historyPage.limit;
    loadHistory();
  });

  // Project Form
  $('project-code-input').addEventListener('input', (e) => {
    const val = e.target.value.trim().toUpperCase();
    $('project-code-preview').innerHTML = '';
    $('project-code-preview').appendChild(document.createTextNode('채번 예시: '));
    $('project-code-preview').appendChild(el('code', { textContent: `${val || 'CODE'}-D-0001` }));
  });

  $('project-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    const errEl = $('project-form-error');
    errEl.hidden = true;
    const id = $('project-id-input').value.trim();
    const code = $('project-code-input').value.trim();
    const title = $('project-title-input').value.trim();
    const btn = $('project-submit-btn');
    btn.disabled = true;
    try {
      await apiRequest('/api/projects', { method: 'POST', body: { id, code, title } });
      showToast(`프로젝트 등록 완료: ${id} (${code})`);
      $('project-form').reset();
      await loadSettingsTab();
      await loadOverview();
    } catch (err) {
      errEl.textContent = err.message;
      errEl.hidden = false;
    } finally {
      btn.disabled = false;
    }
  });

  // Common Code Form
  $('codes-group-filter').addEventListener('change', renderCodesTable);
  $('code-reset-btn').addEventListener('click', () => {
    $('code-form').reset();
    $('code-active-checkbox').checked = true;
    $('code-form-error').hidden = true;
  });

  $('code-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    await submitCodeChange({
      code_group: $('code-group-select').value,
      code: $('code-value-input').value.trim(),
      sort_order: Number($('code-sort-input').value || 0),
      name_ko: $('code-name-ko-input').value.trim(),
      description_ko: $('code-desc-ko-input').value.trim(),
      name_en: $('code-name-en-input').value.trim(),
      description_en: $('code-desc-en-input').value.trim(),
      active: $('code-active-checkbox').checked
    });
  });

  // Drawer & Confirm Modal Close + Escape Key
  $('drawer-close-btn').addEventListener('click', closeDefectDrawer);
  $('drawer-backdrop').addEventListener('click', closeDefectDrawer);
  $('confirm-cancel-btn').addEventListener('click', closeConfirmModal);
  $('confirm-accept-btn').addEventListener('click', async () => {
    const fn = state.confirmAction;
    closeConfirmModal();
    if (typeof fn === 'function') {
      try {
        await fn();
      } catch (e) {
        showToast(e.message, true);
      }
    }
  });

  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') {
      if (!$('confirm-modal-backdrop').hidden) {
        e.preventDefault();
        closeConfirmModal();
      } else if (!$('detail-drawer').hidden) {
        e.preventDefault();
        closeDefectDrawer();
      }
    }
  });
}

async function bootstrap() {
  initEvents();
  try {
    const sess = await apiRequest('/api/auth/session');
    state.instance = sess.instance || 'pg-ledger-mcp';
    state.mcpPort = sess.mcpPort || 8765;
    state.adminPort = sess.adminPort || 8766;
    $('instance-badge').textContent = `${state.instance} (${sess.dbName || 'ledger'})`;
    if (sess.authenticated) {
      showAppView();
      const hashTab = location.hash.replace(/^#/, '');
      switchTab(hashTab || 'overview', { updateHash: false });
    } else {
      showAuthView();
    }
  } catch {
    showAuthView();
  }
}

bootstrap();
