#!/usr/bin/env node
// tests/admin-ui.mjs : E2E verification for the pg-ledger-mcp Admin Web Console and security boundary.
// Usage: node tests/admin-ui.mjs <admin-url> <mcp-url> <project> <code>
import assert from "node:assert/strict";
import { connect } from "../bin/mcp-call.mjs";

const [adminUrl, mcpUrl, project, code] = process.argv.slice(2);
const adminToken = process.env.PGLEDGER_ADMIN_TOKEN;
const mcpToken = process.env.DBHUB_AUTH_TOKEN;

if (!adminUrl || !mcpUrl || !project || !code || !adminToken || !mcpToken) {
  console.error("usage: PGLEDGER_ADMIN_TOKEN=... DBHUB_AUTH_TOKEN=... node tests/admin-ui.mjs <admin-url> <mcp-url> <project> <code>");
  process.exit(2);
}

let passed = 0;
async function check(name, fn) {
  try {
    await fn();
    console.log(`PASS  admin-ui: ${name}`);
    passed++;
  } catch (err) {
    console.error(`FAIL  admin-ui: ${name}\n  ${err?.stack || err}`);
    process.exitCode = 1;
  }
}

async function rawRequest(path, options = {}) {
  const res = await fetch(`${adminUrl}${path}`, {
    redirect: "manual",
    ...options,
  });
  const text = await res.text();
  let json = null;
  try {
    json = JSON.parse(text);
  } catch {
    // non-json response
  }
  return { status: res.status, headers: res.headers, text, json };
}

await check("public /healthz and static assets include CSP and security headers", async () => {
  const health = await rawRequest("/healthz");
  assert.equal(health.status, 200);
  assert.equal(health.json?.ok, true);
  assert.equal(health.json?.service, "pg-ledger-admin");
  assert.equal(health.headers.get("x-frame-options"), "DENY");
  assert.equal(health.headers.get("x-content-type-options"), "nosniff");
  assert.match(health.headers.get("content-security-policy") || "", /default-src 'self'/);
  assert.match(health.headers.get("content-security-policy") || "", /frame-ancestors 'none'/);

  const html = await rawRequest("/");
  assert.equal(html.status, 200);
  assert.match(html.text, /pg-ledger-mcp/i);
  assert.match(html.text, /skip-link/);

  const css = await rawRequest("/styles.css");
  assert.equal(css.status, 200);
  assert.match(css.text, /:focus-visible/);

  const js = await rawRequest("/app.js");
  assert.equal(js.status, 200);
  assert.match(js.text, /isComposing/);
  assert.match(js.text, /isSafeHttpUrl/);
});

await check("unauthenticated /api/overview is rejected with 401", async () => {
  const res = await rawRequest("/api/overview");
  assert.equal(res.status, 401);
  assert.equal(res.json?.error, "UNAUTHORIZED");
});

await check("DBHUB_AUTH_TOKEN is explicitly rejected on login and Bearer auth (AGENT_TOKEN_FORBIDDEN)", async () => {
  const bearerRes = await rawRequest("/api/overview", {
    headers: { Authorization: `Bearer ${mcpToken}` },
  });
  assert.equal(bearerRes.status, 401);
  assert.equal(bearerRes.json?.error, "AGENT_TOKEN_FORBIDDEN");

  const loginRes = await rawRequest("/api/auth/login", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "X-Requested-With": "pg-ledger-admin",
    },
    body: JSON.stringify({ token: mcpToken }),
  });
  assert.equal(loginRes.status, 401);
  assert.equal(loginRes.json?.error, "AGENT_TOKEN_FORBIDDEN");
});

await check("CSRF defenses reject missing X-Requested-With or cross-site Sec-Fetch-Site", async () => {
  const noHeader = await rawRequest("/api/projects", {
    method: "POST",
    headers: {
      Authorization: `Bearer ${adminToken}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({ id: "csrf_test", code: "CSRF", title: "Should fail" }),
  });
  assert.equal(noHeader.status, 403);
  assert.equal(noHeader.json?.error, "CSRF_HEADER_REQUIRED");

  const crossSite = await rawRequest("/api/projects", {
    method: "POST",
    headers: {
      Authorization: `Bearer ${adminToken}`,
      "Content-Type": "application/json",
      "X-Requested-With": "pg-ledger-admin",
      "Sec-Fetch-Site": "cross-site",
    },
    body: JSON.stringify({ id: "csrf_test", code: "CSRF", title: "Should fail" }),
  });
  assert.equal(crossSite.status, 403);
  assert.equal(crossSite.json?.error, "CSRF_CROSS_SITE_BLOCKED");
});

await check("Tier 2 agent workflow endpoints are refused with 403 AGENT_WORKFLOW_FORBIDDEN", async () => {
  for (const path of ["/api/save", "/api/transition", "/api/correct", "/api/record_fix", "/api/review"]) {
    const res = await rawRequest(path, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${adminToken}`,
        "Content-Type": "application/json",
        "X-Requested-With": "pg-ledger-admin",
      },
      body: JSON.stringify({}),
    });
    assert.equal(res.status, 403, `expected 403 for ${path}`);
    assert.equal(res.json?.error, "AGENT_WORKFLOW_FORBIDDEN");
  }
});

let sessionCookie = "";
await check("login with PGLEDGER_ADMIN_TOKEN issues HttpOnly SameSite=Strict session cookie", async () => {
  const loginRes = await rawRequest("/api/auth/login", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "X-Requested-With": "pg-ledger-admin",
    },
    body: JSON.stringify({ token: adminToken }),
  });
  assert.equal(loginRes.status, 200);
  assert.equal(loginRes.json?.ok, true);
  const setCookie = loginRes.headers.get("set-cookie") || "";
  assert.match(setCookie, /pgledger_admin_session=/);
  assert.match(setCookie, /HttpOnly/i);
  assert.match(setCookie, /SameSite=Strict/i);
  sessionCookie = setCookie.split(";")[0];

  const statusRes = await rawRequest("/api/auth/session", {
    headers: { Cookie: sessionCookie },
  });
  assert.equal(statusRes.status, 200);
  assert.equal(statusRes.json?.authenticated, true);
});

await check("read-only explorer endpoints return overview, defects, defect detail, tasks, history, grants, codes", async () => {
  const headers = { Cookie: sessionCookie };

  const overview = await rawRequest(`/api/overview?project=${encodeURIComponent(project)}`, { headers });
  assert.equal(overview.status, 200);
  assert.ok(Array.isArray(overview.json?.attention));
  assert.equal(overview.json?.mcpHealth?.ok, true);

  const projects = await rawRequest("/api/projects", { headers });
  assert.equal(projects.status, 200);
  assert.ok(projects.json?.items?.some((p) => p.id === project));

  const defects = await rawRequest(`/api/defects?project=${encodeURIComponent(project)}`, { headers });
  assert.equal(defects.status, 200);
  assert.ok(defects.json?.items?.length >= 1, "expected at least 1 defect in e2e project");

  const firstId = defects.json.items[0].id;
  const detail = await rawRequest(`/api/defects/${encodeURIComponent(firstId)}`, { headers });
  assert.equal(detail.status, 200);
  assert.equal(detail.json?.defect?.id, firstId);
  assert.ok(Array.isArray(detail.json?.history));

  const tasks = await rawRequest(`/api/tasks?project=${encodeURIComponent(project)}`, { headers });
  assert.equal(tasks.status, 200);
  assert.ok(Array.isArray(tasks.json?.tasks));
  assert.ok(Array.isArray(tasks.json?.conditions));

  const history = await rawRequest(`/api/history?limit=10`, { headers });
  assert.equal(history.status, 200);
  assert.ok(history.json?.items?.length >= 1);

  const grants = await rawRequest("/api/grants", { headers });
  assert.equal(grants.status, 200);
  assert.ok(grants.json?.items?.some((g) => g.project === project));

  const codes = await rawRequest("/api/codes", { headers });
  assert.equal(codes.status, 200);
  assert.ok(codes.json?.items?.some((c) => c.code === "BUG"));
});

await check("owner mutations: grant + revoke session scopes via Admin API", async () => {
  const grantRes = await rawRequest("/api/grants", {
    method: "POST",
    headers: {
      Cookie: sessionCookie,
      "Content-Type": "application/json",
      "X-Requested-With": "pg-ledger-admin",
    },
    body: JSON.stringify({
      author_id: "gemini:admin-ui-test",
      project: project,
      scopes: ["defect", "task"],
      expires_at: "2099-12-31T00:00:00Z",
      note: "granted via admin-ui e2e test",
    }),
  });
  assert.equal(grantRes.status, 201, `grant failed: ${grantRes.text}`);

  const listAfterGrant = await rawRequest("/api/grants", { headers: { Cookie: sessionCookie } });
  const grantedScopes = listAfterGrant.json.items
    .filter((g) => g.author_id === "gemini:admin-ui-test" && g.project === project)
    .map((g) => g.scope)
    .sort();
  assert.deepEqual(grantedScopes, ["defect", "task"]);

  const revokeRes = await rawRequest("/api/grants/revoke", {
    method: "POST",
    headers: {
      Cookie: sessionCookie,
      "Content-Type": "application/json",
      "X-Requested-With": "pg-ledger-admin",
    },
    body: JSON.stringify({
      author_id: "gemini:admin-ui-test",
      project: project,
      scope: "task",
    }),
  });
  assert.equal(revokeRes.status, 200, `revoke failed: ${revokeRes.text}`);

  const listAfterRevoke = await rawRequest("/api/grants", { headers: { Cookie: sessionCookie } });
  const remainingScopes = listAfterRevoke.json.items
    .filter((g) => g.author_id === "gemini:admin-ui-test" && g.project === project)
    .map((g) => g.scope);
  assert.deepEqual(remainingScopes, ["defect"]);

  // Clean up remaining test grant
  await rawRequest("/api/grants/revoke", {
    method: "POST",
    headers: {
      Cookie: sessionCookie,
      "Content-Type": "application/json",
      "X-Requested-With": "pg-ledger-admin",
    },
    body: JSON.stringify({
      author_id: "gemini:admin-ui-test",
      project: project,
    }),
  });
});

await check("owner mutations: common code delete is refused; code-set updates DB, re-renders tools.toml, and keeps MCP healthy", async () => {
  const delRes = await rawRequest("/api/codes", {
    method: "DELETE",
    headers: {
      Cookie: sessionCookie,
      "X-Requested-With": "pg-ledger-admin",
    },
  });
  assert.equal(delRes.status, 405);
  assert.equal(delRes.json?.error, "COMMON_CODE_DELETE_FORBIDDEN");

  const codeSetRes = await rawRequest("/api/codes", {
    method: "POST",
    headers: {
      Cookie: sessionCookie,
      "Content-Type": "application/json",
      "X-Requested-With": "pg-ledger-admin",
    },
    body: JSON.stringify({
      code_group: "DEFECT_CATEGORY",
      code: "UI_UX",
      name_ko: "사용자 경험",
      description_ko: "화면 동선 및 접근성 결함",
      name_en: "UI and UX",
      description_en: "User flow and accessibility defect",
      sort_order: 25,
      active: true,
    }),
  });
  assert.equal(codeSetRes.status, 200, `code-set failed: ${codeSetRes.text}`);
  assert.equal(codeSetRes.json?.sync?.steps?.length, 4);
  assert.equal(codeSetRes.json?.sync?.mcpHealth?.ok, true);

  // Verify via MCP tools/list that the newly added active code is live on DBHub
  const mcp = await connect(mcpUrl, mcpToken);
  try {
    const tools = await mcp.toolDefinitions();
    const saveTool = tools.find((t) => t.name === "ledger_save");
    assert.ok(saveTool?.description?.includes("UI_UX"), "expected UI_UX in ledger_save tool description after code-set");
  } finally {
    await mcp.close();
  }

  // Retire the code via active=false and confirm it remains in common_code as inactive
  const retireRes = await rawRequest("/api/codes", {
    method: "POST",
    headers: {
      Cookie: sessionCookie,
      "Content-Type": "application/json",
      "X-Requested-With": "pg-ledger-admin",
    },
    body: JSON.stringify({
      code_group: "DEFECT_CATEGORY",
      code: "UI_UX",
      name_ko: "사용자 경험",
      description_ko: "화면 동선 및 접근성 결함",
      name_en: "UI and UX",
      description_en: "User flow and accessibility defect",
      sort_order: 25,
      active: false,
    }),
  });
  assert.equal(retireRes.status, 200);

  const codesAfter = await rawRequest("/api/codes?group=DEFECT_CATEGORY", {
    headers: { Cookie: sessionCookie },
  });
  const uiUxRow = codesAfter.json?.items?.find((c) => c.code === "UI_UX");
  assert.ok(uiUxRow, "UI_UX row still exists in common_code");
  assert.equal(uiUxRow.active, false);
});

if (process.exitCode) {
  process.exit(process.exitCode);
}
console.log(`PASS  all ${passed} admin-ui checks succeeded`);
