/*
 * p2pgate operator dashboard — vanilla JS, no build step, no dependencies
 * (specs/seller-dashboard.md §1). A pure consumer of the /v1 JSON API and the
 * /v1/events WebSocket: it holds no keys, builds no transactions, invents no
 * state. Canonical state names pass through verbatim from the API.
 */
"use strict";

const API = "/v1";
const TOKEN_KEY = "p2pgate.operator.token";

// Canonical lane columns (specs/deal-protocol.md §1), terminal column last.
const ACTIVE_STATES = ["QUOTED", "FUNDED", "PAYMENT_PENDING", "PAYMENT_CONFIRMED", "CLAIM_OPENED", "CLAIMABLE"];
const TERMINAL_STATES = ["RELEASED", "RECLAIMED", "CLAIMED"];
// Dispute actions the API actually accepts (POST /v1/disputes/{id}/{action}).
const DISPUTE_ACTIONS = ["contest", "accept", "investigate"];

// --------------------------------------------------------------------- state
const state = {
  token: sessionStorage.getItem(TOKEN_KEY),
  tab: "lane",
  lanes: {},          // canonical state -> [LaneCardDto]
  disputes: [],
  pool: null,
  quotes: [],
  infra: null,
  meetingDealId: null,
  socket: null,
  pollTimer: null,
  countdownTimer: null,
  reconnectDelay: 1000,
};

// ------------------------------------------------------------------- helpers
const $ = (sel) => document.querySelector(sel);

function fmtAmount(baseUnits) {
  // USDT, 6 decimals — the collateral amount a lane card locks.
  return (baseUnits / 1e6).toLocaleString("en-US", { maximumFractionDigits: 2 }) + " USDT";
}

function fmtCountdown(deadlineEpochMs) {
  const left = deadlineEpochMs - Date.now();
  if (left <= 0) return "expired";
  const s = Math.floor(left / 1000);
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  if (h >= 24) return Math.floor(h / 24) + "d " + (h % 24) + "h left";
  if (h > 0) return h + "h " + m + "m left";
  return m + "m " + (s % 60) + "s left";
}

function fmtDuration(fromEpochMs, toEpochMs) {
  const ms = Math.max(0, toEpochMs - fromEpochMs);
  const h = Math.floor(ms / 3600000);
  const m = Math.floor((ms % 3600000) / 60000);
  return h > 0 ? h + "h " + m + "m" : m + "m";
}

function shortId(id) { return id ? id.slice(0, 8) + "…" : ""; }

function showError(el, err) {
  // The UI invents no error vocabulary: the backend's message is shown verbatim
  // (spec §3.1).
  el.textContent = err && err.error ? err.error : String(err);
  el.hidden = false;
}

// ---------------------------------------------------------------------- api
async function api(path, options = {}) {
  const headers = Object.assign({}, options.headers || {});
  if (state.token) headers["Authorization"] = "Bearer " + state.token;
  if (options.body !== undefined) headers["Content-Type"] = "application/json";
  const res = await fetch(API + path, {
    method: options.method || "GET",
    headers: headers,
    body: options.body !== undefined ? JSON.stringify(options.body) : undefined,
  });
  const text = await res.text();
  let parsed = null;
  try { parsed = text ? JSON.parse(text) : null; } catch (e) { parsed = null; }
  if (!res.ok) {
    const err = new Error((parsed && parsed.error) || res.status + " " + res.statusText);
    err.status = res.status;
    err.body = parsed;
    throw err;
  }
  return parsed;
}

// --------------------------------------------------------------------- auth
function showLogin() {
  $("#app").hidden = true;
  $("#meeting-view").hidden = true;
  $("#login-view").hidden = false;
  $("#token-input").value = "";
  $("#login-error").hidden = true;
}

async function login(token) {
  state.token = token;
  try {
    await api("/lane"); // any dashboard endpoint proves the token
  } catch (e) {
    state.token = null;
    const el = $("#login-error");
    el.textContent = e.status === 401 && e.body && e.body.error ? e.body.error : String(e.message);
    el.hidden = false;
    return;
  }
  sessionStorage.setItem(TOKEN_KEY, token);
  showApp();
}

function logout() {
  sessionStorage.removeItem(TOKEN_KEY);
  state.token = null;
  if (state.socket) { state.socket.close(); state.socket = null; }
  clearInterval(state.pollTimer);
  clearInterval(state.countdownTimer);
  showLogin();
}

function showApp() {
  $("#login-view").hidden = true;
  $("#app").hidden = false;
  connectEvents();
  refreshAll();
  // Slow polling fallback keeps the lane fresh if the socket drops (spec §1).
  state.pollTimer = setInterval(() => { if (!state.socket || state.socket.readyState > 1) refreshAll(); }, 30000);
  state.countdownTimer = setInterval(renderCountdowns, 1000);
}

// --------------------------------------------------------------- live events
function connectEvents() {
  if (!state.token) return;
  const scheme = location.protocol === "https:" ? "wss" : "ws";
  // Browsers cannot set headers on a WebSocket: the operator token travels as
  // the same `token` query parameter the API already accepts.
  const ws = new WebSocket(scheme + "://" + location.host + API + "/events?token=" + encodeURIComponent(state.token));
  state.socket = ws;
  ws.onopen = () => {
    state.reconnectDelay = 1000;
    $("#conn-state").textContent = "live";
    $("#conn-state").className = "conn ok";
  };
  ws.onmessage = (msg) => {
    let event = null;
    try { event = JSON.parse(msg.data); } catch (e) { return; }
    onEvent(event);
  };
  ws.onclose = () => {
    if (state.socket !== ws) return; // superseded by logout/re-login
    $("#conn-state").textContent = "reconnecting…";
    $("#conn-state").className = "conn bad";
    state.socket = null;
    setTimeout(connectEvents, state.reconnectDelay);
    state.reconnectDelay = Math.min(state.reconnectDelay * 2, 30000);
  };
  ws.onerror = () => ws.close();
}

function onEvent(event) {
  // Any deal transition, pause change, or quote change re-renders the views.
  if (event.kind === "pause.changed") { refreshInfra(); refreshQuote(); return; }
  if (event.kind && event.kind.indexOf("quote.") === 0) { refreshQuote(); refreshInfra(); return; }
  refreshLane();
  refreshDisputes();
  refreshInfra();
}

// -------------------------------------------------------------------- refresh
async function refreshAll() {
  await Promise.allSettled([refreshLane(), refreshPool(), refreshDisputes(), refreshInfra(), refreshQuote()]);
}

async function refreshLane() {
  try {
    const dto = await api("/lane");
    state.lanes = dto.lanes || {};
    renderLane();
  } catch (e) { /* session errors surface on the next user action */ }
}

async function refreshPool() {
  try {
    state.pool = await api("/pool");
    renderPool();
  } catch (e) { /* keep last known */ }
}

async function refreshDisputes() {
  try {
    state.disputes = await api("/disputes");
    renderDisputes();
    renderFailLoud();
  } catch (e) { /* keep last known */ }
}

async function refreshInfra() {
  try {
    state.infra = await api("/infra");
    renderInfra();
    renderPauseBanner();
  } catch (e) { /* keep last known */ }
}

async function refreshQuote() {
  try {
    const dto = await api("/dashboard/quotes");
    state.quotes = dto.quotes || [];
    renderQuote();
  } catch (e) { /* keep last known */ }
}

// ------------------------------------------------------------------ vault lane
function deadlineFor(card) {
  if (card.reclaimDeadlineEpochMs) return { label: "reclaim in", at: card.reclaimDeadlineEpochMs };
  if (card.claimMaturesAtEpochMs) return { label: "claim matures in", at: card.claimMaturesAtEpochMs };
  return null;
}

function renderLane() {
  const lane = $("#lane");
  lane.innerHTML = "";
  const columns = ACTIVE_STATES.concat(["TERMINAL"]);
  for (const col of columns) {
    const states = col === "TERMINAL" ? TERMINAL_STATES : [col];
    const cards = states.flatMap((s) => state.lanes[s] || []);
    const el = document.createElement("div");
    el.className = "lane-col";
    el.innerHTML = "<h2>" + col + ' <span class="count">' + cards.length + "</span></h2>";
    for (const card of cards) el.appendChild(renderCard(card, col));
    if (cards.length === 0) {
      const empty = document.createElement("p");
      empty.className = "muted empty";
      empty.textContent = "—";
      el.appendChild(empty);
    }
    lane.appendChild(el);
  }
  renderCountdowns();
}

function renderCard(card, column) {
  const el = document.createElement("div");
  el.className = "card" + (card.contested ? " contested" : "");
  el.dataset.dealId = card.dealId;

  const dl = deadlineFor(card);
  el.innerHTML =
    '<div class="card-top"><strong>' + fmtAmount(card.amount) + "</strong>" +
    '<span class="muted mono">' + shortId(card.dealId) + "</span></div>" +
    (dl ? '<div class="countdown" data-deadline="' + dl.at + '">' + dl.label + " " + fmtCountdown(dl.at) + "</div>"
        : '<div class="countdown none">no countdown</div>') +
    '<div class="exit">' + escapeHtml(card.exitPath) + "</div>";

  const actions = document.createElement("div");
  actions.className = "card-actions";

  // Meeting entry: FUNDED to sign, PAYMENT_PENDING to re-show the QR.
  if (column === "FUNDED" || column === "PAYMENT_PENDING") {
    const btn = document.createElement("button");
    btn.textContent = column === "FUNDED" ? "Meeting" : "Show QR";
    btn.addEventListener("click", () => openMeeting(card.dealId, column));
    actions.appendChild(btn);
  }

  // Manual reclaim where the API allows it: FUNDED (timeout elapsed) and
  // PAYMENT_CONFIRMED. From PAYMENT_PENDING the button is absent, not
  // disabled — reclaim from there is a theft path the backend rejects.
  if (column === "FUNDED" || column === "PAYMENT_CONFIRMED") {
    const btn = document.createElement("button");
    btn.className = "secondary";
    btn.textContent = "Reclaim";
    btn.addEventListener("click", () => reclaim(card.dealId, btn));
    actions.appendChild(btn);
  }

  el.appendChild(actions);
  return el;
}

function renderCountdowns() {
  document.querySelectorAll(".countdown[data-deadline]").forEach((el) => {
    const at = Number(el.dataset.deadline);
    const label = el.dataset.label || (el.textContent.indexOf("claim matures") === 0 ? "claim matures in" : "reclaim in");
    el.dataset.label = label;
    el.textContent = label + " " + fmtCountdown(at);
    el.classList.toggle("over", at - Date.now() <= 0);
  });
}

async function reclaim(dealId, btn) {
  btn.disabled = true;
  try {
    await api("/vaults/" + encodeURIComponent(dealId) + "/reclaim", { method: "POST" });
  } catch (e) {
    alert(e.message); // backend's reason, verbatim
  }
  btn.disabled = false;
  refreshLane();
}

// ------------------------------------------------------------------- meeting
function openMeeting(dealId, dealState) {
  state.meetingDealId = dealId;
  $("#app").hidden = true;
  $("#meeting-view").hidden = false;
  $("#meeting-error").hidden = true;
  $("#meeting-amount").textContent = "";
  $("#meeting-sub").textContent = "deal " + shortId(dealId);
  $("#cash-counted").checked = false;
  $("#sign-btn").disabled = true;

  // Re-entry on a PAYMENT_PENDING deal shows the QR without re-signing; a
  // FUNDED deal walks the counted-cash gate first.
  if (dealState === "PAYMENT_PENDING") {
    showQr(dealId);
  } else {
    $("#meeting-gate").hidden = false;
    $("#meeting-qr").hidden = true;
  }
}

function closeMeeting() {
  state.meetingDealId = null;
  $("#meeting-view").hidden = true;
  $("#app").hidden = false;
  refreshLane();
}

async function signHandoff() {
  const id = state.meetingDealId;
  const errEl = $("#meeting-error");
  errEl.hidden = true;
  $("#sign-btn").disabled = true;
  try {
    const res = await api("/dashboard/deals/" + encodeURIComponent(id) + "/handoff/sign", { method: "POST", body: {} });
    // Success: the record is final, the deal is PAYMENT_PENDING.
    showQr(id);
  } catch (e) {
    showError(errEl, e.body || e.message); // 409 carries the deal's state
    $("#sign-btn").disabled = false;
  }
}

function showQr(dealId) {
  $("#meeting-gate").hidden = true;
  $("#meeting-qr").hidden = false;
  const img = $("#qr-img");
  img.src = API + "/dashboard/deals/" + encodeURIComponent(dealId) + "/handoff/qr.png?token=" +
    encodeURIComponent(state.token);
  // Post-sign state: nothing else on the screen, no re-sign, no close-deal
  // while the buyer's app is mid-verification (spec §3.3). Re-entry
  // (alreadySigned) and a fresh sign land on the same state.
  $("#meeting-signed").textContent = "record signed — the buyer verifies";
  $("#meeting-signed").hidden = false;
}

// ----------------------------------------------------------------------- pool
function renderPool() {
  const p = state.pool;
  if (!p) return;
  const total = p.mixReady + p.locked + p.free;
  $("#pool").innerHTML =
    "<h2>Collateral pool</h2>" +
    '<div class="stat-grid">' +
    stat("mix-ready", fmtAmount(p.mixReady) + " <span class='muted'>(pre-mixed)</span>") +
    stat("locked in vaults", fmtAmount(p.locked)) +
    stat("free (unmixed)", fmtAmount(p.free)) +
    stat("utilization", p.utilizationPct.toFixed(1) + " %") +
    stat("open deals", String(p.openDeals)) +
    stat("capacity", fmtAmount(total)) +
    "</div>" +
    '<p class="muted">Amounts only — per the privacy-funding rule there is no consolidated ' +
    "operator-wallet graph.</p>";
}

function stat(label, value) {
  return '<div class="stat"><div class="stat-value">' + value + '</div><div class="muted">' + label + "</div></div>";
}

function renderQuote() {
  const quotes = state.quotes;
  const box = $("#quote");
  if (!quotes || quotes.length === 0) {
    box.innerHTML = "<h2>Quotes</h2>" +
      '<p class="muted">no active quotes — paused or none published</p>';
    return;
  }
  box.innerHTML = "<h2>Quotes</h2>";
  for (const q of quotes) {
    const el = document.createElement("div");
    el.className = "quote";
    el.innerHTML =
      '<div class="stat-grid">' +
      stat("spread", q.spreadBps + " bps") +
      stat("ETA", q.etaMinutes + " min") +
      stat("max amount", fmtAmount(q.maxAmount)) +
      stat("expires in", fmtCountdown(q.expiresAtEpochMs)) +
      (q.lat != null ? stat("location", q.lat.toFixed(3) + ", " + q.lon.toFixed(3)) : "") +
      "</div>";
    const actions = document.createElement("div");
    actions.className = "card-actions";
    const btn = document.createElement("button");
    btn.className = "secondary";
    btn.textContent = "Withdraw";
    btn.addEventListener("click", () => withdrawQuote(q.id, btn));
    actions.appendChild(btn);
    el.appendChild(actions);
    box.appendChild(el);
  }
}

async function withdrawQuote(id, btn) {
  btn.disabled = true;
  try {
    await api("/dashboard/quotes/" + encodeURIComponent(id) + "/withdraw", { method: "POST" });
  } catch (e) {
    alert(e.message); // backend's reason, verbatim
  }
  btn.disabled = false;
  refreshQuote();
}

// -------------------------------------------------------------------- disputes
function renderDisputes() {
  const box = $("#disputes");
  box.innerHTML = "<h2>Dispute inbox</h2>";
  if (state.disputes.length === 0) {
    box.innerHTML += '<p class="muted">no open claims</p>';
    return;
  }
  // The deadline is the sort key (spec §3.5).
  const rows = state.disputes.slice().sort((a, b) => a.maturesAtEpochMs - b.maturesAtEpochMs);
  for (const row of rows) box.appendChild(renderDisputeRow(row));
}

function renderDisputeRow(row) {
  const el = document.createElement("div");
  el.className = "dispute" + (row.contested ? " contested" : "");
  const maturesIn = fmtCountdown(row.maturesAtEpochMs);
  const matured = row.maturesAtEpochMs - Date.now() <= 0;
  el.innerHTML =
    '<div class="card-top"><strong>' + row.state + '</strong>' +
    '<span class="countdown' + (matured ? " over" : "") + '" data-deadline="' + row.maturesAtEpochMs +
    '" data-label="matures in">matures in ' + maturesIn + "</span></div>" +
    '<div class="muted mono">' + shortId(row.dealId) + "</div>" +
    '<div class="evidence">handoff record: ' + (row.handoffRecordRef ? "on file" : "none") +
    " · oracle attestation: " + (row.oracleConfirmed ? ("confirmed" + (row.attestationDigest ? " (" + shortId(row.attestationDigest) + ")" : "")) : "absent") +
    (row.geoRef ? " · geo: " + escapeHtml(row.geoRef) : "") + "</div>" +
    (row.actioned ? '<div class="muted">actioned: ' + escapeHtml(row.action || "") + "</div>" : "");

  if (!row.actioned) {
    const actions = document.createElement("div");
    actions.className = "card-actions";
    for (const action of DISPUTE_ACTIONS) {
      const btn = document.createElement("button");
      btn.className = action === "contest" ? "" : "secondary";
      btn.textContent = action;
      btn.title = action === "contest"
        ? "Present the oracle attestation alone (path C′). The phase-1 oracle's attestation alone releases the vault — it is trusted, period, on the release path."
        : action === "accept" ? "Accept the loss (no on-chain action)." : "Route to manual review (changes nothing on-chain).";
      btn.addEventListener("click", () => actOnDispute(row.dealId, action, btn));
      actions.appendChild(btn);
    }
    el.appendChild(actions);
  }
  return el;
}

async function actOnDispute(dealId, action, btn) {
  btn.disabled = true;
  try {
    await api("/disputes/" + encodeURIComponent(dealId) + "/" + action, { method: "POST" });
  } catch (e) {
    alert(e.message); // backend's reason, verbatim
  }
  btn.disabled = false;
  refreshDisputes();
  refreshLane();
}

// A claim nearing maturation unactioned renders fail-loud (spec §3.5):
// a matured-unactioned claim is collateral donated.
const FAIL_LOUD_WINDOW_MS = 2 * 3600 * 1000;

function renderFailLoud() {
  const urgent = state.disputes.filter((r) => !r.actioned && r.maturesAtEpochMs - Date.now() <= FAIL_LOUD_WINDOW_MS);
  const el = $("#fail-loud");
  if (urgent.length === 0) { el.hidden = true; return; }
  el.hidden = false;
  el.textContent = urgent.length + " claim(s) nearing maturation unactioned — a matured-unactioned " +
    "claim is collateral donated. " + urgent.map((r) => shortId(r.dealId)).join(", ");
}

// ------------------------------------------------------------------------ infra
function renderInfra() {
  const infra = state.infra;
  if (!infra) return;
  const rows = infra.signals.map((s) =>
    '<div class="signal ' + (s.healthy ? "ok" : "bad") + '">' +
    "<strong>" + escapeHtml(s.signal) + "</strong>" +
    '<span class="muted">' + escapeHtml(s.detail || (s.healthy ? "healthy" : "degraded")) + "</span></div>"
  ).join("");
  const history = infra.pauseHistory.map((p) =>
    '<div class="signal">' + "<strong>" + escapeHtml(p.cause) + "</strong>" +
    '<span class="muted">' + fmtDuration(p.startedAtEpochMs, p.endedAtEpochMs || Date.now()) + "</span></div>"
  ).join("");
  $("#infra").innerHTML =
    "<h2>Infrastructure</h2>" +
    '<div class="signal ' + (infra.paused ? "bad" : "ok") + '"><strong>' +
    (infra.paused ? "PAUSED — new business halted" : "operating") + "</strong></div>" +
    rows +
    (history ? '<h3>Pause history</h3>' + history : "");
}

function renderPauseBanner() {
  const infra = state.infra;
  const el = $("#pause-banner");
  if (!infra || !infra.paused) { el.hidden = true; return; }
  const cause = infra.pauseHistory.length
    ? infra.pauseHistory[infra.pauseHistory.length - 1].cause
    : "";
  // The verbatim rule (spec §3.6): quotes are withdrawn when verification is
  // degraded — never sell insurance you can't currently verify.
  el.hidden = false;
  el.textContent = "PAUSED (" + cause + ") — quotes withdrawn. Never sell insurance you can't currently verify. " +
    "Every paused hour is idle capital.";
}

// ----------------------------------------------------------------------- misc
function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, (c) => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;",
  }[c]));
}

// ---------------------------------------------------------------------- wiring
$("#login-form").addEventListener("submit", (e) => {
  e.preventDefault();
  login($("#token-input").value.trim());
});
$("#logout").addEventListener("click", logout);
$("#tabs").addEventListener("click", (e) => {
  const btn = e.target.closest("button[data-tab]");
  if (!btn) return;
  state.tab = btn.dataset.tab;
  document.querySelectorAll("#tabs .tab").forEach((t) => t.classList.toggle("active", t === btn));
  ["lane", "pool", "disputes", "infra"].forEach((name) => {
    $("#tab-" + name).hidden = name !== state.tab;
  });
});
$("#cash-counted").addEventListener("change", (e) => {
  $("#sign-btn").disabled = !e.target.checked;
});
$("#sign-btn").addEventListener("click", signHandoff);
$("#meeting-close").addEventListener("click", closeMeeting);

// Entry point: a stored session resumes without re-typing the token.
if (state.token) showApp(); else showLogin();
