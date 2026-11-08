#!/usr/bin/env node
// ledger-admin-server.mjs : Zero-dependency Admin Web Server for pg-ledger-mcp (Node >= 22.5).
// Implements Tier 1 (Owner Control Plane: Projects, Session Grants, Common Codes + DBHub sync)
// and Tier 3 (Ledger Observability Plane: Triage v_attention, Defects v_defect, Tasks/Conditions, History v_history).
// Tier 2 (Agent Workflow Plane: save, transition, record_fix, correct, review) is strictly excluded.

import { createServer } from 'node:http';
import { createHash, createHmac, timingSafeEqual } from 'node:crypto';
import { readFileSync, existsSync } from 'node:fs';
import { spawn } from 'node:child_process';
import { dirname, resolve, join, extname } from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = resolve(__dirname, '..');
const WEB_ROOT = join(REPO_ROOT, 'web');
const LEDGER_ADMIN_BIN = join(REPO_ROOT, 'bin', 'ledger-admin');

function parseEnvFile(filePath) {
  const out = {};
  if (!existsSync(filePath)) return out;
  const content = readFileSync(filePath, 'utf8');
  for (const rawLine of content.split(/\r?\n/)) {
    const line = rawLine.trim();
    if (!line || line.startsWith('#')) continue;
    const eq = line.indexOf('=');
    if (eq <= 0) continue;
    const key = line.slice(0, eq).trim();
    const val = line.slice(eq + 1).trim();
    out[key] = val;
  }
  return out;
}

function parseArgs(argv) {
  let name = 'pg-ledger-mcp';
  let envFile = '';
  let host = '127.0.0.1';
  let port = 0;
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--name') name = argv[++i] || '';
    else if (a === '--env-file') envFile = argv[++i] || '';
    else if (a === '--host') host = argv[++i] || '127.0.0.1';
    else if (a === '--port') port = Number(argv[++i] || 0);
    else if (a === '-h' || a === '--help') {
      console.log('Usage: ledger-admin-server.mjs [--name pg-ledger-mcp] [--env-file /etc/pg-ledger-mcp/env] [--host 127.0.0.1] [--port 8766]');
      process.exit(0);
    } else {
      console.error(`ledger-admin-server: unknown argument: ${a}`);
      process.exit(2);
    }
  }
  if (!/^[a-z][a-z0-9-]{0,40}$/.test(name)) {
    console.error(`ledger-admin-server: invalid --name: ${name}`);
    process.exit(2);
  }
  if (!envFile) envFile = `/etc/${name}/env`;
  return { name, envFile, host, port };
}

function sha256Buf(str) {
  return createHash('sha256').update(String(str ?? ''), 'utf8').digest();
}

function safeEqualStrings(a, b) {
  if (typeof a !== 'string' || typeof b !== 'string' || !a || !b) return false;
  return timingSafeEqual(sha256Buf(a), sha256Buf(b));
}

function makeSessionCookieValue(adminToken, expiresAtMs) {
  const exp = String(expiresAtMs);
  const sig = createHmac('sha256', adminToken).update(`pgledger-admin-session:${exp}`).digest('hex');
  return `${exp}.${sig}`;
}

function verifySessionCookieValue(cookieVal, adminToken) {
  if (typeof cookieVal !== 'string' || !cookieVal.includes('.')) return false;
  const [expStr, sig] = cookieVal.split('.', 2);
  const exp = Number(expStr);
  if (!Number.isFinite(exp) || exp <= Date.now()) return false;
  const expected = createHmac('sha256', adminToken).update(`pgledger-admin-session:${expStr}`).digest('hex');
  return safeEqualStrings(sig, expected);
}

function parseCookies(cookieHeader) {
  const out = {};
  if (!cookieHeader) return out;
  for (const part of cookieHeader.split(';')) {
    const idx = part.indexOf('=');
    if (idx <= 0) continue;
    const k = part.slice(0, idx).trim();
    const v = part.slice(idx + 1).trim();
    out[k] = decodeURIComponent(v);
  }
  return out;
}

function extractHostname(hostHeader) {
  if (!hostHeader || typeof hostHeader !== 'string') return '';
  const trimmed = hostHeader.trim().toLowerCase();
  if (trimmed.startsWith('[')) {
    const close = trimmed.indexOf(']');
    return close > 0 ? trimmed.slice(0, close + 1) : trimmed;
  }
  return trimmed.split(':')[0];
}

function applySecurityHeaders(res, isApi = true) {
  res.setHeader(
    'Content-Security-Policy',
    "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; object-src 'none'; base-uri 'self'; frame-ancestors 'none'"
  );
  res.setHeader('X-Frame-Options', 'DENY');
  res.setHeader('X-Content-Type-Options', 'nosniff');
  res.setHeader('Referrer-Policy', 'no-referrer');
  if (isApi) {
    res.setHeader('Cache-Control', 'no-store');
  }
}

function sendJson(res, status, payload) {
  applySecurityHeaders(res, true);
  const body = JSON.stringify(payload);
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(body)
  });
  res.end(body);
}

function readJsonBody(req, maxBytes = 65536) {
  return new Promise((resolvePromise, rejectPromise) => {
    const chunks = [];
    let total = 0;
    let done = false;
    req.on('data', (chunk) => {
      if (done) return;
      total += chunk.length;
      if (total > maxBytes) {
        done = true;
        const err = new Error('요청 본문이 허용 크기(64 KiB)를 초과했습니다.');
        err.statusCode = 413;
        err.code = 'PAYLOAD_TOO_LARGE';
        rejectPromise(err);
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });
    req.on('end', () => {
      if (done) return;
      done = true;
      const raw = Buffer.concat(chunks).toString('utf8').trim();
      if (!raw) {
        resolvePromise({});
        return;
      }
      try {
        const parsed = JSON.parse(raw);
        if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
          const err = new Error('JSON 객체 본문이 필요합니다.');
          err.statusCode = 400;
          err.code = 'INVALID_JSON_OBJECT';
          rejectPromise(err);
          return;
        }
        resolvePromise(parsed);
      } catch {
        const err = new Error('유효하지 않은 JSON 형식입니다.');
        err.statusCode = 400;
        err.code = 'INVALID_JSON';
        rejectPromise(err);
      }
    });
    req.on('error', (err) => {
      if (!done) {
        done = true;
        rejectPromise(err);
      }
    });
  });
}

function runCommand(cmd, args, { env = process.env, input = '', timeoutMs = 15000 } = {}) {
  return new Promise((resolvePromise, rejectPromise) => {
    const child = spawn(cmd, args, {
      env,
      stdio: ['pipe', 'pipe', 'pipe']
    });
    const outChunks = [];
    const errChunks = [];
    let settled = false;
    const timer = setTimeout(() => {
      if (settled) return;
      settled = true;
      child.kill('SIGKILL');
      const err = new Error(`명령 실행 제한 시간(${timeoutMs}ms)을 초과했습니다.`);
      err.code = 'COMMAND_TIMEOUT';
      rejectPromise(err);
    }, timeoutMs);

    child.stdout.on('data', (d) => outChunks.push(d));
    child.stderr.on('data', (d) => errChunks.push(d));
    child.on('error', (err) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      rejectPromise(err);
    });
    child.on('close', (code) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      const stdout = Buffer.concat(outChunks).toString('utf8').trim();
      const stderr = Buffer.concat(errChunks).toString('utf8').trim();
      if (code !== 0) {
        const err = new Error(stderr || stdout || `${cmd} exited with status ${code}`);
        err.code = 'COMMAND_FAILED';
        err.exitCode = code;
        err.stderr = stderr;
        rejectPromise(err);
        return;
      }
      resolvePromise({ stdout, stderr });
    });
    if (input) child.stdin.write(input, 'utf8');
    child.stdin.end();
  });
}

async function checkMcpHealth(mcpPort) {
  const url = `http://127.0.0.1:${mcpPort}/healthz`;
  try {
    const ctrl = new AbortController();
    const timer = setTimeout(() => ctrl.abort(), 2500);
    const resp = await fetch(url, { signal: ctrl.signal });
    clearTimeout(timer);
    return { ok: resp.ok, status: resp.status, url };
  } catch (e) {
    return { ok: false, status: 0, url, error: e.message };
  }
}

export function createAdminApp(config) {
  const {
    name,
    dbName,
    pgHost = '127.0.0.1',
    pgPort = 5432,
    mcpPort = 8765,
    adminPort = 8766,
    readerPassword,
    adminToken,
    dbhubToken = '',
    allowedHosts = new Set(['127.0.0.1', 'localhost', '[::1]'])
  } = config;

  // Query helper using strictly read-only ledger_reader over TCP with psql -v bindings
  async function queryReaderJson(sql, vars = {}) {
    const varArgs = [];
    for (const [k, v] of Object.entries(vars)) {
      if (!/^[a-zA-Z_][a-zA-Z0-9_]*$/.test(k)) throw new Error(`Invalid psql variable name: ${k}`);
      varArgs.push('-v', `${k}=${v ?? ''}`);
    }
    const { stdout } = await runCommand(
      'psql',
      ['-X', '-q', '-v', 'ON_ERROR_STOP=1', '-h', pgHost, '-p', String(pgPort), '-U', 'ledger_reader', '-d', dbName, '-tA', ...varArgs],
      {
        env: {
          ...process.env,
          PGPASSWORD: readerPassword,
          PGOPTIONS: '-c default_transaction_read_only=on -c client_min_messages=warning'
        },
        input: sql,
        timeoutMs: 12000
      }
    );
    return stdout ? JSON.parse(stdout) : null;
  }

  // Serialized mutex for common_code sync so concurrent requests do not overlap render-tools.sh / systemctl restart
  let codeSyncChain = Promise.resolve();
  function runWithCodeSyncLock(fn) {
    const next = codeSyncChain.then(fn, fn);
    codeSyncChain = next.catch(() => {});
    return next;
  }

  async function runOwnerCommand(subArgs, timeoutMs = 15000) {
    return runCommand(LEDGER_ADMIN_BIN, ['--name', name, ...subArgs], {
      env: process.env,
      timeoutMs
    });
  }

  function verifyHostAndCsrf(req, res) {
    const hostHeader = req.headers.host || '';
    const hostname = extractHostname(hostHeader);
    if (!hostname || !allowedHosts.has(hostname)) {
      sendJson(res, 403, {
        error: 'DNS_REBINDING_BLOCKED',
        message: `허용되지 않은 Host 헤더(${hostHeader || '없음'})입니다.`
      });
      return false;
    }

    const method = (req.method || 'GET').toUpperCase();
    if (method !== 'GET' && method !== 'HEAD' && method !== 'OPTIONS') {
      const xrw = req.headers['x-requested-with'];
      if (xrw !== 'pg-ledger-admin') {
        sendJson(res, 403, {
          error: 'CSRF_HEADER_REQUIRED',
          message: '상태 변경 요청에는 X-Requested-With: pg-ledger-admin 헤더가 필수입니다.'
        });
        return false;
      }
      const secFetchSite = req.headers['sec-fetch-site'];
      if (secFetchSite && secFetchSite !== 'same-origin' && secFetchSite !== 'none') {
        sendJson(res, 403, {
          error: 'CSRF_CROSS_SITE_BLOCKED',
          message: `교차 사이트 요청(Sec-Fetch-Site: ${secFetchSite})이 차단되었습니다.`
        });
        return false;
      }
      const origin = req.headers.origin;
      if (origin) {
        let originHost = '';
        try {
          originHost = extractHostname(new URL(origin).host);
        } catch {
          originHost = '';
        }
        if (!originHost || !allowedHosts.has(originHost)) {
          sendJson(res, 403, {
            error: 'CSRF_ORIGIN_BLOCKED',
            message: `허용되지 않은 Origin(${origin})입니다.`
          });
          return false;
        }
      }
    }
    return true;
  }

  function checkAuth(req) {
    const authHeader = req.headers.authorization || '';
    if (authHeader.toLowerCase().startsWith('bearer ')) {
      const candidate = authHeader.slice(7).trim();
      if (dbhubToken && safeEqualStrings(candidate, dbhubToken)) {
        return { ok: false, reason: 'AGENT_TOKEN_FORBIDDEN' };
      }
      if (safeEqualStrings(candidate, adminToken)) {
        return { ok: true, mode: 'bearer' };
      }
      return { ok: false, reason: 'INVALID_TOKEN' };
    }
    const cookies = parseCookies(req.headers.cookie || '');
    if (cookies.pgledger_admin_session && verifySessionCookieValue(cookies.pgledger_admin_session, adminToken)) {
      return { ok: true, mode: 'cookie' };
    }
    return { ok: false, reason: 'UNAUTHENTICATED' };
  }

  function requireAuth(req, res) {
    const result = checkAuth(req);
    if (!result.ok) {
      if (result.reason === 'AGENT_TOKEN_FORBIDDEN') {
        sendJson(res, 401, {
          error: 'AGENT_TOKEN_FORBIDDEN',
          message: 'DBHUB_AUTH_TOKEN(에이전트용 MCP 공유 토큰)으로는 관리자 페이지에 접근할 수 없습니다. PGLEDGER_ADMIN_TOKEN을 사용하세요.'
        });
        return false;
      }
      sendJson(res, 401, {
        error: 'UNAUTHORIZED',
        message: '관리자 인증(PGLEDGER_ADMIN_TOKEN)이 필요합니다.'
      });
      return false;
    }
    return true;
  }

  const MIME_TYPES = {
    '.html': 'text/html; charset=utf-8',
    '.css': 'text/css; charset=utf-8',
    '.js': 'application/javascript; charset=utf-8',
    '.json': 'application/json; charset=utf-8',
    '.svg': 'image/svg+xml'
  };

  function serveStatic(pathname, res) {
    const rel = pathname === '/' ? 'index.html' : pathname.replace(/^\/+/, '');
    if (rel.includes('..') || rel.includes('\\')) {
      sendJson(res, 400, { error: 'INVALID_PATH', message: '잘못된 경로입니다.' });
      return;
    }
    const filePath = join(WEB_ROOT, rel);
    if (!existsSync(filePath)) {
      sendJson(res, 404, { error: 'NOT_FOUND', message: '요청한 리소스를 찾을 수 없습니다.' });
      return;
    }
    const ext = extname(filePath);
    const contentType = MIME_TYPES[ext] || 'application/octet-stream';
    const content = readFileSync(filePath);
    applySecurityHeaders(res, false);
    res.writeHead(200, {
      'Content-Type': contentType,
      'Content-Length': content.length
    });
    res.end(content);
  }

  const FORBIDDEN_TIER2_PATHS = new Set([
    '/api/save',
    '/api/transition',
    '/api/record_fix',
    '/api/record-fix',
    '/api/record_case',
    '/api/record-case',
    '/api/link',
    '/api/correct',
    '/api/batch',
    '/api/review',
    '/api/review_correct'
  ]);

  return async function handleRequest(req, res) {
    try {
      if (!verifyHostAndCsrf(req, res)) return;

      const urlObj = new URL(req.url || '/', `http://${req.headers.host || '127.0.0.1'}`);
      const pathname = urlObj.pathname;
      const method = (req.method || 'GET').toUpperCase();

      // Public liveness endpoints
      if (method === 'GET' && (pathname === '/healthz' || pathname === '/api/healthz')) {
        sendJson(res, 200, {
          ok: true,
          service: 'pg-ledger-admin',
          instance: name,
          adminPort,
          mcpPort
        });
        return;
      }

      // Static frontend assets
      if (method === 'GET' && !pathname.startsWith('/api/')) {
        serveStatic(pathname, res);
        return;
      }

      // Explicitly block Tier 2 Agent Workflow operations on Admin Server
      if (FORBIDDEN_TIER2_PATHS.has(pathname)) {
        sendJson(res, 403, {
          error: 'AGENT_WORKFLOW_FORBIDDEN',
          message: '결함·과업 상태 전이(VERIFIED 등), 수정 커밋 기록, 원장 정정(correct)은 호스트 훅(_hostAttestation) 및 세션 권한이 부여된 MCP 에이전트 전용 작업입니다. 관리자 웹페이지는 소유자 제어(Tier 1)와 읽기 전용 원장 관측(Tier 3)만 제공합니다.'
        });
        return;
      }

      // Auth endpoints
      if (pathname === '/api/auth/session' && method === 'GET') {
        const auth = checkAuth(req);
        sendJson(res, 200, {
          authenticated: auth.ok,
          instance: name,
          dbName,
          mcpPort,
          adminPort
        });
        return;
      }

      if (pathname === '/api/auth/login' && method === 'POST') {
        const body = await readJsonBody(req);
        const token = typeof body.token === 'string' ? body.token.trim() : '';
        if (!token) {
          sendJson(res, 400, { error: 'TOKEN_REQUIRED', message: 'PGLEDGER_ADMIN_TOKEN을 입력해 주세요.' });
          return;
        }
        if (dbhubToken && safeEqualStrings(token, dbhubToken)) {
          sendJson(res, 401, {
            error: 'AGENT_TOKEN_FORBIDDEN',
            message: 'DBHUB_AUTH_TOKEN(에이전트용 MCP 공유 토큰)으로는 관리자 페이지에 로그인할 수 없습니다. /etc/' + name + '/env의 PGLEDGER_ADMIN_TOKEN을 입력해 주세요.'
          });
          return;
        }
        if (!safeEqualStrings(token, adminToken)) {
          sendJson(res, 401, {
            error: 'INVALID_ADMIN_TOKEN',
            message: 'PGLEDGER_ADMIN_TOKEN이 일치하지 않습니다.'
          });
          return;
        }
        const expiresAtMs = Date.now() + 12 * 3600 * 1000;
        const cookieVal = makeSessionCookieValue(adminToken, expiresAtMs);
        res.setHeader(
          'Set-Cookie',
          `pgledger_admin_session=${encodeURIComponent(cookieVal)}; Path=/; HttpOnly; SameSite=Strict; Max-Age=43200`
        );
        sendJson(res, 200, {
          ok: true,
          authenticated: true,
          instance: name,
          expiresAt: new Date(expiresAtMs).toISOString()
        });
        return;
      }

      if (pathname === '/api/auth/logout' && method === 'POST') {
        res.setHeader('Set-Cookie', 'pgledger_admin_session=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0');
        sendJson(res, 200, { ok: true, authenticated: false });
        return;
      }

      // All remaining /api/* endpoints require PGLEDGER_ADMIN_TOKEN authentication
      if (!requireAuth(req, res)) return;

      // 1. Overview & Triage Dashboard (/api/overview)
      if (pathname === '/api/overview' && method === 'GET') {
        const projectFilter = urlObj.searchParams.get('project') || '';
        const [dbSummary, mcpHealth] = await Promise.all([
          queryReaderJson(
            `SELECT jsonb_build_object(
              'attention', COALESCE((
                SELECT jsonb_agg(jsonb_build_object(
                  'kind', a.kind,
                  'id', a.id,
                  'project_id', a.project_id,
                  'entity', CASE WHEN a.kind IN ('TASK_UNKNOWN', 'DONE_NOT_VERIFIED') THEN 'task' ELSE 'defect' END,
                  'title', COALESCE(d.title, t.title, ''),
                  'state', COALESCE(d.status, t.state, ''),
                  'severity', d.severity,
                  'owner_actor_id', COALESCE(d.owner_actor_id, t.owner_actor_id),
                  'updated_at', COALESCE(d.updated_at, t.updated_at)
                ) ORDER BY a.kind, a.project_id, a.id)
                FROM ledger.v_attention a
                LEFT JOIN ledger.v_defect d ON d.id = a.id AND a.kind NOT IN ('TASK_UNKNOWN', 'DONE_NOT_VERIFIED')
                LEFT JOIN ledger.task t ON t.id = a.id AND a.kind IN ('TASK_UNKNOWN', 'DONE_NOT_VERIFIED')
                WHERE (:'project' = '' OR a.project_id = :'project')
              ), '[]'::jsonb),
              'attentionCounts', COALESCE((
                SELECT jsonb_object_agg(kind, cnt) FROM (
                  SELECT kind, count(*)::int AS cnt FROM ledger.v_attention
                  WHERE (:'project' = '' OR project_id = :'project')
                  GROUP BY kind
                ) s
              ), '{}'::jsonb),
              'defectStatusCounts', COALESCE((
                SELECT jsonb_object_agg(status, cnt) FROM (
                  SELECT status, count(*)::int AS cnt FROM ledger.defect
                  WHERE (:'project' = '' OR project_id = :'project')
                  GROUP BY status
                ) s
              ), '{}'::jsonb),
              'defectSeverityCounts', COALESCE((
                SELECT jsonb_object_agg(severity, cnt) FROM (
                  SELECT severity, count(*)::int AS cnt FROM ledger.defect
                  WHERE (:'project' = '' OR project_id = :'project')
                  GROUP BY severity
                ) s
              ), '{}'::jsonb),
              'taskStateCounts', COALESCE((
                SELECT jsonb_object_agg(state, cnt) FROM (
                  SELECT state, count(*)::int AS cnt FROM ledger.task
                  WHERE (:'project' = '' OR project_id = :'project')
                  GROUP BY state
                ) s
              ), '{}'::jsonb),
              'activeGrantCount', (
                SELECT count(*)::int FROM ledger.session_grant
                WHERE revoked_at IS NULL AND (expires_at IS NULL OR expires_at > clock_timestamp())
                  AND (:'project' = '' OR project = :'project')
              ),
              'expiringSoonGrantCount', (
                SELECT count(*)::int FROM ledger.session_grant
                WHERE revoked_at IS NULL AND expires_at IS NOT NULL
                  AND expires_at > clock_timestamp()
                  AND expires_at <= clock_timestamp() + interval '1 hour'
                  AND (:'project' = '' OR project = :'project')
              ),
              'projectCount', (SELECT count(*)::int FROM ledger.project),
              'activeCodeCount', (SELECT count(*)::int FROM ledger.common_code WHERE active)
            );`,
            { project: projectFilter }
          ),
          checkMcpHealth(mcpPort)
        ]);
        sendJson(res, 200, { ...dbSummary, mcpHealth, instance: name });
        return;
      }

      // 2. Defects Explorer (/api/defects and /api/defects/:id)
      if (pathname === '/api/defects' && method === 'GET') {
        const project = urlObj.searchParams.get('project') || '';
        const status = urlObj.searchParams.get('status') || '';
        const category = urlObj.searchParams.get('category') || '';
        const severity = urlObj.searchParams.get('severity') || '';
        const attention = urlObj.searchParams.get('attention') || '';
        const q = (urlObj.searchParams.get('q') || '').trim();
        const limit = Math.min(100, Math.max(1, Number(urlObj.searchParams.get('limit') || 50)));
        const offset = Math.max(0, Number(urlObj.searchParams.get('offset') || 0));

        const data = await queryReaderJson(
          `WITH filtered AS (
            SELECT v.*, d.behavior, d.root_cause, d.disposition_reason, d.evidence_uri
            FROM ledger.v_defect v
            JOIN ledger.defect d ON d.id = v.id
            WHERE (:'project' = '' OR v.project_id = :'project')
              AND (:'status' = '' OR v.status = :'status')
              AND (:'category' = '' OR v.category = :'category')
              AND (:'severity' = '' OR v.severity = :'severity')
              AND (:'attention' = '' OR EXISTS (
                SELECT 1 FROM ledger.v_attention a WHERE a.id = v.id AND a.kind = :'attention'
              ))
              AND (:'q' = '' OR v.id ILIKE '%' || :'q' || '%'
                   OR v.title ILIKE '%' || :'q' || '%'
                   OR d.behavior ILIKE '%' || :'q' || '%'
                   OR coalesce(d.root_cause, '') ILIKE '%' || :'q' || '%'
                   OR coalesce(v.owner_actor_id, '') ILIKE '%' || :'q' || '%'
                   OR coalesce(v.fixed_by, '') ILIKE '%' || :'q' || '%')
          )
          SELECT jsonb_build_object(
            'total', (SELECT count(*)::int FROM filtered),
            'limit', (:'limit')::int,
            'offset', (:'offset')::int,
            'items', COALESCE((
              SELECT jsonb_agg(to_jsonb(f) ORDER BY f.updated_at DESC, f.id DESC)
              FROM (SELECT * FROM filtered ORDER BY updated_at DESC, id DESC LIMIT (:'limit')::int OFFSET (:'offset')::int) f
            ), '[]'::jsonb)
          );`,
          { project, status, category, severity, attention, q, limit: String(limit), offset: String(offset) }
        );
        sendJson(res, 200, data);
        return;
      }

      if (pathname.startsWith('/api/defects/') && method === 'GET') {
        const defectId = decodeURIComponent(pathname.slice('/api/defects/'.length));
        const detail = await queryReaderJson(
          `SELECT jsonb_build_object(
            'defect', (
              SELECT to_jsonb(v) || jsonb_build_object(
                'behavior', d.behavior,
                'root_cause', d.root_cause,
                'disposition_reason', d.disposition_reason,
                'evidence_uri', d.evidence_uri,
                'evidence_sha256', d.evidence_sha256,
                'created_at', d.created_at
              )
              FROM ledger.v_defect v
              JOIN ledger.defect d ON d.id = v.id
              WHERE v.id = :'id'
            ),
            'relations', COALESCE((
              SELECT jsonb_agg(jsonb_build_object(
                'defect_id', r.defect_id,
                'related_id', r.related_id,
                'kind', r.kind,
                'created_by', r.created_by,
                'created_at', r.created_at
              ) ORDER BY r.defect_id, r.related_id)
              FROM ledger.defect_relation r
              WHERE r.defect_id = :'id' OR r.related_id = :'id'
            ), '[]'::jsonb),
            'history', COALESCE((
              SELECT jsonb_agg(to_jsonb(h) ORDER BY h.at DESC, h.id DESC)
              FROM ledger.v_history h
              WHERE (h.entity = 'defect' AND h.entity_id = :'id')
                 OR (h.entity IN ('defect_fix', 'defect_case', 'defect_task', 'defect_relation')
                     AND (split_part(h.entity_id, '@', 1) = :'id' OR split_part(h.entity_id, '|', 1) = :'id'))
            ), '[]'::jsonb)
          );`,
          { id: defectId }
        );
        if (!detail || !detail.defect) {
          sendJson(res, 404, { error: 'DEFECT_NOT_FOUND', message: `결함(${defectId})을 찾을 수 없습니다.` });
          return;
        }
        sendJson(res, 200, detail);
        return;
      }

      // 3. Tasks & Conditions Explorer (/api/tasks)
      if (pathname === '/api/tasks' && method === 'GET') {
        const project = urlObj.searchParams.get('project') || '';
        const state = urlObj.searchParams.get('state') || '';
        const q = (urlObj.searchParams.get('q') || '').trim();
        const limit = Math.min(100, Math.max(1, Number(urlObj.searchParams.get('limit') || 50)));
        const offset = Math.max(0, Number(urlObj.searchParams.get('offset') || 0));

        const data = await queryReaderJson(
          `WITH filtered_tasks AS (
            SELECT t.*,
              COALESCE((
                SELECT jsonb_agg(dt.defect_id ORDER BY dt.defect_id)
                FROM ledger.defect_task dt WHERE dt.task_id = t.id
              ), '[]'::jsonb) AS linked_defects,
              COALESCE((
                SELECT jsonb_agg(cl.condition_id ORDER BY cl.condition_id)
                FROM ledger.condition_link cl WHERE cl.task_id = t.id
              ), '[]'::jsonb) AS linked_conditions
            FROM ledger.task t
            WHERE (:'project' = '' OR t.project_id = :'project')
              AND (:'state' = '' OR t.state = :'state')
              AND (:'q' = '' OR t.id ILIKE '%' || :'q' || '%'
                   OR t.title ILIKE '%' || :'q' || '%'
                   OR t.purpose ILIKE '%' || :'q' || '%'
                   OR coalesce(t.owner_actor_id, '') ILIKE '%' || :'q' || '%')
          )
          SELECT jsonb_build_object(
            'totalTasks', (SELECT count(*)::int FROM filtered_tasks),
            'limit', (:'limit')::int,
            'offset', (:'offset')::int,
            'tasks', COALESCE((
              SELECT jsonb_agg(to_jsonb(ft) ORDER BY ft.updated_at DESC, ft.id DESC)
              FROM (SELECT * FROM filtered_tasks ORDER BY updated_at DESC, id DESC LIMIT (:'limit')::int OFFSET (:'offset')::int) ft
            ), '[]'::jsonb),
            'conditions', COALESCE((
              SELECT jsonb_agg(jsonb_build_object(
                'id', c.id,
                'project_id', c.project_id,
                'release', c.release,
                'goal_label', c.goal_label,
                'code', c.code,
                'condition_text', c.condition_text,
                'state', c.state,
                'version', c.version,
                'created_by', c.created_by,
                'updated_at', c.updated_at,
                'links', COALESCE((
                  SELECT jsonb_agg(jsonb_build_object('task_id', cl.task_id, 'defect_id', cl.defect_id) ORDER BY cl.task_id, cl.defect_id)
                  FROM ledger.condition_link cl WHERE cl.condition_id = c.id
                ), '[]'::jsonb)
              ) ORDER BY c.project_id, c.release, c.code, c.id)
              FROM ledger.condition c
              WHERE (:'project' = '' OR c.project_id = :'project')
            ), '[]'::jsonb)
          );`,
          { project, state, q, limit: String(limit), offset: String(offset) }
        );
        sendJson(res, 200, data);
        return;
      }

      // 4. Audit History Explorer (/api/history)
      if (pathname === '/api/history' && method === 'GET') {
        const entity = urlObj.searchParams.get('entity') || '';
        const op = urlObj.searchParams.get('op') || '';
        const author = (urlObj.searchParams.get('author') || '').trim();
        const entityId = (urlObj.searchParams.get('entity_id') || '').trim();
        const q = (urlObj.searchParams.get('q') || '').trim();
        const limit = Math.min(100, Math.max(1, Number(urlObj.searchParams.get('limit') || 50)));
        const offset = Math.max(0, Number(urlObj.searchParams.get('offset') || 0));

        const data = await queryReaderJson(
          `WITH filtered_hist AS (
            SELECT * FROM ledger.v_history h
            WHERE (:'entity' = '' OR h.entity = :'entity')
              AND (:'op' = '' OR h.op = :'op')
              AND (:'author' = '' OR h.author_id ILIKE '%' || :'author' || '%')
              AND (:'entity_id' = '' OR h.entity_id ILIKE '%' || :'entity_id' || '%')
              AND (:'q' = '' OR h.entity_id ILIKE '%' || :'q' || '%'
                   OR h.reason ILIKE '%' || :'q' || '%'
                   OR h.author_id ILIKE '%' || :'q' || '%'
                   OR (h.changes::text) ILIKE '%' || :'q' || '%')
          )
          SELECT jsonb_build_object(
            'total', (SELECT count(*)::int FROM filtered_hist),
            'limit', (:'limit')::int,
            'offset', (:'offset')::int,
            'items', COALESCE((
              SELECT jsonb_agg(to_jsonb(fh) ORDER BY fh.at DESC, fh.id DESC)
              FROM (SELECT * FROM filtered_hist ORDER BY at DESC, id DESC LIMIT (:'limit')::int OFFSET (:'offset')::int) fh
            ), '[]'::jsonb)
          );`,
          { entity, op, author, entity_id: entityId, q, limit: String(limit), offset: String(offset) }
        );
        sendJson(res, 200, data);
        return;
      }

      // 5. Projects Management (/api/projects)
      if (pathname === '/api/projects' && method === 'GET') {
        const projects = await queryReaderJson(
          `SELECT COALESCE(jsonb_agg(jsonb_build_object(
            'id', p.id,
            'code', p.code,
            'title', p.title,
            'created_by', p.created_by,
            'created_at', p.created_at,
            'defect_count', (SELECT count(*)::int FROM ledger.defect d WHERE d.project_id = p.id),
            'open_defect_count', (SELECT count(*)::int FROM ledger.defect d WHERE d.project_id = p.id AND d.status = 'OPEN'),
            'task_count', (SELECT count(*)::int FROM ledger.task t WHERE t.project_id = p.id),
            'active_grant_count', (
              SELECT count(*)::int FROM ledger.session_grant g
              WHERE g.project = p.id AND g.revoked_at IS NULL AND (g.expires_at IS NULL OR g.expires_at > clock_timestamp())
            )
          ) ORDER BY p.id), '[]'::jsonb)
          FROM ledger.project p;`
        );
        sendJson(res, 200, { items: projects });
        return;
      }

      if (pathname === '/api/projects' && method === 'POST') {
        const body = await readJsonBody(req);
        const id = String(body.id ?? '').trim();
        const code = String(body.code ?? '').trim();
        const title = String(body.title ?? '').trim();

        if (!/^[A-Za-z0-9_-]{1,64}$/.test(id)) {
          sendJson(res, 400, { error: 'INVALID_PROJECT_ID', message: '프로젝트 ID는 영문·숫자·밑줄·하이픈(1~64자)이어야 합니다.' });
          return;
        }
        if (!/^[A-Z][A-Z0-9_]{0,31}$/.test(code)) {
          sendJson(res, 400, { error: 'INVALID_PROJECT_CODE', message: '프로젝트 코드는 대문자 영문으로 시작하는 ^[A-Z][A-Z0-9_]{0,31}$ 형식이어야 합니다.' });
          return;
        }
        if (title.length < 1 || title.length > 200) {
          sendJson(res, 400, { error: 'INVALID_PROJECT_TITLE', message: '프로젝트 제목은 1~200자여야 합니다.' });
          return;
        }

        await runOwnerCommand(['project-add', id, code, title]);
        sendJson(res, 201, { ok: true, project: { id, code, title } });
        return;
      }

      // 6. Session Grants Management (/api/grants & /api/grants/revoke)
      if (pathname === '/api/grants' && method === 'GET') {
        const statusFilter = urlObj.searchParams.get('status') || 'live';
        const project = urlObj.searchParams.get('project') || '';
        const scope = urlObj.searchParams.get('scope') || '';
        const q = (urlObj.searchParams.get('q') || '').trim();

        const grants = await queryReaderJson(
          `SELECT COALESCE(jsonb_agg(jsonb_build_object(
            'id', g.id,
            'author_id', g.author_id,
            'project', g.project,
            'scope', g.scope,
            'granted_by', g.granted_by,
            'granted_at', g.granted_at,
            'expires_at', g.expires_at,
            'revoked_at', g.revoked_at,
            'note', g.note,
            'status', CASE
              WHEN g.revoked_at IS NOT NULL THEN 'revoked'
              WHEN g.expires_at IS NOT NULL AND g.expires_at <= clock_timestamp() THEN 'expired'
              ELSE 'live'
            END,
            'expiring_soon', (g.revoked_at IS NULL AND g.expires_at IS NOT NULL AND g.expires_at > clock_timestamp() AND g.expires_at <= clock_timestamp() + interval '1 hour')
          ) ORDER BY g.granted_at DESC, g.id DESC), '[]'::jsonb)
          FROM ledger.session_grant g
          WHERE (:'project' = '' OR g.project = :'project')
            AND (:'scope' = '' OR g.scope = :'scope')
            AND (:'q' = '' OR g.author_id ILIKE '%' || :'q' || '%' OR g.project ILIKE '%' || :'q' || '%' OR coalesce(g.note, '') ILIKE '%' || :'q' || '%')
            AND (
              :'status' = 'all'
              OR (:'status' = 'live' AND g.revoked_at IS NULL AND (g.expires_at IS NULL OR g.expires_at > clock_timestamp()))
              OR (:'status' = 'expired' AND g.revoked_at IS NULL AND g.expires_at IS NOT NULL AND g.expires_at <= clock_timestamp())
              OR (:'status' = 'revoked' AND g.revoked_at IS NOT NULL)
            )
          LIMIT 300;`,
          { status: statusFilter, project, scope, q }
        );
        sendJson(res, 200, { items: grants });
        return;
      }

      if (pathname === '/api/grants' && method === 'POST') {
        const body = await readJsonBody(req);
        const authorId = String(body.author_id ?? '').trim();
        const project = String(body.project ?? '').trim();
        const scopes = Array.isArray(body.scopes) ? body.scopes.map((s) => String(s).trim()) : [];
        const expiresAt = body.expires_at ? String(body.expires_at).trim() : '';
        const note = body.note ? String(body.note).trim() : '';

        if (!(authorId === 'user:owner' || (/^[a-z][a-z0-9-]{0,31}:[A-Za-z0-9._-]{1,128}$/.test(authorId) && !authorId.startsWith('user:')))) {
          sendJson(res, 400, {
            error: 'INVALID_AUTHOR_ID',
            message: '작성자 ID(author_id)는 <vendor>:<session-id> 형식(예: claude:abc1234, codex:01a1..., gemini:e28d...)이어야 합니다.'
          });
          return;
        }
        if (!/^[A-Za-z0-9_-]{1,64}$/.test(project)) {
          sendJson(res, 400, { error: 'INVALID_PROJECT', message: '유효한 프로젝트를 선택해 주세요.' });
          return;
        }
        const allowedScopes = new Set(['defect', 'task', 'fix', 'verify']);
        const uniqueScopes = [...new Set(scopes)];
        if (uniqueScopes.length === 0 || uniqueScopes.some((s) => !allowedScopes.has(s))) {
          sendJson(res, 400, {
            error: 'INVALID_SCOPES',
            message: '최소 1개 이상의 유효한 권한 범위(defect, task, fix, verify)를 선택해야 합니다.'
          });
          return;
        }
        if (expiresAt && Number.isNaN(Date.parse(expiresAt))) {
          sendJson(res, 400, { error: 'INVALID_EXPIRES_AT', message: '만료 시각(expires_at)이 올바른 ISO-8601 형식이 아닙니다.' });
          return;
        }
        if (note.length > 500) {
          sendJson(res, 400, { error: 'INVALID_NOTE', message: '비고(note)는 500자 이내여야 합니다.' });
          return;
        }

        const cmdArgs = ['grant', authorId, project, ...uniqueScopes];
        if (expiresAt) cmdArgs.push('--expires', new Date(expiresAt).toISOString());
        if (note) cmdArgs.push('--note', note);

        await runOwnerCommand(cmdArgs);
        sendJson(res, 201, {
          ok: true,
          granted: { author_id: authorId, project, scopes: uniqueScopes, expires_at: expiresAt || null, note: note || null }
        });
        return;
      }

      if (pathname === '/api/grants/revoke' && method === 'POST') {
        const body = await readJsonBody(req);
        const authorId = String(body.author_id ?? '').trim();
        const project = body.project ? String(body.project).trim() : '';
        const scope = body.scope ? String(body.scope).trim() : '';

        if (!authorId) {
          sendJson(res, 400, { error: 'AUTHOR_ID_REQUIRED', message: '회수할 작성자 ID(author_id)가 필요합니다.' });
          return;
        }
        if (scope && !project) {
          sendJson(res, 400, { error: 'PROJECT_REQUIRED_FOR_SCOPE', message: '특정 scope만 회수할 때는 project도 함께 지정해야 합니다.' });
          return;
        }
        const cmdArgs = ['revoke', authorId];
        if (project) cmdArgs.push(project);
        if (project && scope) cmdArgs.push(scope);

        const { stdout } = await runOwnerCommand(cmdArgs);
        sendJson(res, 200, {
          ok: true,
          revoked: { author_id: authorId, project: project || null, scope: scope || null, raw: stdout }
        });
        return;
      }

      // 7. Common Codes Management (/api/codes)
      if (pathname === '/api/codes' && method === 'DELETE') {
        sendJson(res, 405, {
          error: 'COMMON_CODE_DELETE_FORBIDDEN',
          message: '공통 코드는 과거 원장 참조 무결성(ledger._code_keep 트리거)을 위해 삭제할 수 없습니다. active=false(비활성화/Retire)를 사용하세요.'
        });
        return;
      }

      if (pathname === '/api/codes' && method === 'GET') {
        const group = urlObj.searchParams.get('group') || '';
        const data = await queryReaderJson(
          `SELECT jsonb_build_object(
            'items', COALESCE((
              SELECT jsonb_agg(to_jsonb(c) ORDER BY c.code_group, c.sort_order, c.code)
              FROM ledger.common_code c
              WHERE (:'group' = '' OR c.code_group = :'group')
            ), '[]'::jsonb),
            'uses', COALESCE((
              SELECT jsonb_agg(to_jsonb(u) ORDER BY u.code_group, u.table_name, u.column_name)
              FROM ledger.common_code_use u
            ), '[]'::jsonb)
          );`,
          { group }
        );
        sendJson(res, 200, data);
        return;
      }

      if (pathname === '/api/codes' && method === 'POST') {
        const body = await readJsonBody(req);
        // Explicitly reject any attempt to delete or rename common codes (ledger._code_keep invariant)
        if (body.action === 'delete') {
          sendJson(res, 400, {
            error: 'COMMON_CODE_DELETE_FORBIDDEN',
            message: '공통 코드는 과거 원장 참조 무결성(ledger._code_keep 트리거)을 위해 삭제할 수 없습니다. active=false(비활성화/Retire)를 사용하세요.'
          });
          return;
        }

        const codeGroup = String(body.code_group ?? '').trim();
        const code = String(body.code ?? '').trim();
        const nameKo = String(body.name_ko ?? '').trim();
        const descKo = String(body.description_ko ?? '').trim();
        const nameEn = String(body.name_en ?? nameKo).trim() || nameKo;
        const descEn = String(body.description_en ?? descKo).trim() || descKo;
        const sortOrder = Number.isFinite(Number(body.sort_order)) ? Math.trunc(Number(body.sort_order)) : 0;
        const active = body.active !== false;

        const allowedGroups = new Set(['DEFECT_CATEGORY', 'DEFECT_SEVERITY', 'RELATION_KIND']);
        if (!allowedGroups.has(codeGroup)) {
          sendJson(res, 400, {
            error: 'INVALID_CODE_GROUP',
            message: 'code_group은 DEFECT_CATEGORY, DEFECT_SEVERITY, RELATION_KIND 중 하나여야 합니다.'
          });
          return;
        }
        if (!/^[A-Za-z][A-Za-z0-9_]{0,31}$/.test(code)) {
          sendJson(res, 400, {
            error: 'INVALID_CODE',
            message: '코드(code)는 영문으로 시작하는 1~32자(영문·숫자·밑줄)여야 합니다.'
          });
          return;
        }
        if (nameKo.length < 1 || nameKo.length > 200 || descKo.length < 1 || descKo.length > 500) {
          sendJson(res, 400, {
            error: 'INVALID_CODE_TEXT',
            message: '한국어 이름(1~200자)과 설명(1~500자)은 필수입니다.'
          });
          return;
        }
        if (nameEn.length < 1 || nameEn.length > 200 || descEn.length < 1 || descEn.length > 500) {
          sendJson(res, 400, {
            error: 'INVALID_CODE_TEXT_EN',
            message: '영어 이름(1~200자)과 설명(1~500자)은 필수입니다.'
          });
          return;
        }

        const cmdArgs = [
          'code-set',
          codeGroup,
          code,
          nameKo,
          descKo,
          '--name-en',
          nameEn,
          '--description-en',
          descEn,
          '--sort',
          String(sortOrder)
        ];
        if (!active) cmdArgs.push('--inactive');

        const startedAt = Date.now();
        await runWithCodeSyncLock(() => runOwnerCommand(cmdArgs, 40000));
        const durationMs = Date.now() - startedAt;
        const mcpHealth = await checkMcpHealth(mcpPort);

        sendJson(res, 200, {
          ok: true,
          code: {
            code_group: codeGroup,
            code,
            name_ko: nameKo,
            name_en: nameEn,
            description_ko: descKo,
            description_en: descEn,
            sort_order: sortOrder,
            active
          },
          sync: {
            steps: ['DB_UPDATED', 'TOOLS_RENDERED', 'DBHUB_RESTARTED', 'HEALTHZ_VERIFIED'],
            durationMs,
            mcpHealth
          }
        });
        return;
      }

      sendJson(res, 404, { error: 'API_NOT_FOUND', message: `존재하지 않는 API 경로(${pathname})입니다.` });
    } catch (err) {
      const status = err.statusCode || 500;
      sendJson(res, status, {
        error: err.code || 'INTERNAL_ERROR',
        message: err.message || '요청을 처리하는 중 서버 오류가 발생했습니다.'
      });
    }
  };
}

// Entrypoint when executed directly
if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const cli = parseArgs(process.argv.slice(2));
  const fileEnv = parseEnvFile(cli.envFile);
  const getEnv = (key, fallback = '') => process.env[key] || fileEnv[key] || fallback;

  const dbName = getEnv('PGLEDGER_DB');
  const pgHost = getEnv('PGLEDGER_PGHOST', '127.0.0.1');
  const pgPort = Number(getEnv('PGLEDGER_PGPORT', '5432'));
  const mcpPort = Number(getEnv('PGLEDGER_MCP_PORT', '8765'));
  const adminPort = cli.port || Number(getEnv('PGLEDGER_ADMIN_PORT', String(mcpPort + 1)));
  const readerPassword = getEnv('PGLEDGER_READER_PASSWORD');
  const adminToken = getEnv('PGLEDGER_ADMIN_TOKEN');
  const dbhubToken = getEnv('DBHUB_AUTH_TOKEN');
  const rawAllowedHosts = [
    getEnv('PGLEDGER_ADMIN_ALLOWED_HOSTS'),
    getEnv('DBHUB_ALLOWED_HOSTS'),
    '127.0.0.1',
    'localhost',
    '[::1]',
    cli.host
  ]
    .filter(Boolean)
    .join(',');

  const allowedHosts = new Set(
    rawAllowedHosts
      .split(',')
      .map((h) => extractHostname(h))
      .filter(Boolean)
  );

  if (!dbName || !readerPassword || !adminToken) {
    console.error(
      `ledger-admin-server: missing required settings (PGLEDGER_DB, PGLEDGER_READER_PASSWORD, PGLEDGER_ADMIN_TOKEN) in ${cli.envFile}`
    );
    process.exit(1);
  }

  const app = createAdminApp({
    name: cli.name,
    dbName,
    pgHost,
    pgPort,
    mcpPort,
    adminPort,
    readerPassword,
    adminToken,
    dbhubToken,
    allowedHosts
  });

  const server = createServer(app);
  server.listen(adminPort, cli.host, () => {
    console.log(
      `pg-ledger-admin (${cli.name}) listening on http://${cli.host}:${adminPort} (DB=${dbName}@${pgHost}:${pgPort}, MCP=${mcpPort})`
    );
  });

  const shutdown = () => {
    server.close(() => process.exit(0));
    setTimeout(() => process.exit(0), 3000).unref();
  };
  process.on('SIGINT', shutdown);
  process.on('SIGTERM', shutdown);
}
