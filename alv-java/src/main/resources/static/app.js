const DEFAULT_PAGE_LIMIT = 200;
const MAX_PAGE_LIMIT = 5000;
let offset = 0;
let lastTotal = 0;
let browsePath = "";
/** ディレクトリ参照ダイアログの親ディレクトリ（無ければ null）。 */
let browseParent = null;
let metaRange = { first: null, last: null };

const els = {
  meta: document.getElementById("meta"),
  parseWarning: document.getElementById("parse-warning"),
  parseWarningText: document.getElementById("parse-warning-text"),
  parseWarningSamples: document.getElementById("parse-warning-samples"),
  logDir: document.getElementById("log-dir"),
  browse: document.getElementById("browse"),
  loadDir: document.getElementById("load-dir"),
  fileList: document.getElementById("file-list"),
  status: document.getElementById("status"),
  path: document.getElementById("path"),
  method: document.getElementById("method"),
  host: document.getElementById("host"),
  sinceDate: document.getElementById("since-date"),
  sinceTime: document.getElementById("since-time"),
  untilDate: document.getElementById("until-date"),
  untilTime: document.getElementById("until-time"),
  rangeFirst1h: document.getElementById("range-first-1h"),
  rangeFirst24h: document.getElementById("range-first-24h"),
  rangeLast1h: document.getElementById("range-last-1h"),
  rangeLast24h: document.getElementById("range-last-24h"),
  rangeClear: document.getElementById("range-clear"),
  rangeHint: document.getElementById("range-hint"),
  grep: document.getElementById("grep"),
  source: document.getElementById("source"),
  pageLimit: document.getElementById("page-limit"),
  search: document.getElementById("search"),
  reset: document.getElementById("reset"),
  rows: document.getElementById("rows"),
  resultCount: document.getElementById("result-count"),
  highlight: document.getElementById("highlight"),
  fullPath: document.getElementById("full-path"),
  pageInfo: document.getElementById("page-info"),
  prev: document.getElementById("prev"),
  next: document.getElementById("next"),
  detail: document.getElementById("detail"),
  detailBody: document.getElementById("detail-body"),
  detailSearchAround1: document.getElementById("detail-search-around-1"),
  detailSearchAround5: document.getElementById("detail-search-around-5"),
  detailFilterIp: document.getElementById("detail-filter-ip"),
  regexSamples: document.getElementById("regex-samples"),
  regexSamplesDialog: document.getElementById("regex-samples-dialog"),
  browseDialog: document.getElementById("browse-dialog"),
  browseCurrent: document.getElementById("browse-current"),
  browseList: document.getElementById("browse-list"),
  browseUp: document.getElementById("browse-up"),
  browseSelect: document.getElementById("browse-select"),
  loadingOverlay: document.getElementById("loading-overlay"),
  loadingText: document.getElementById("loading-text"),
};

let loadingDepth = 0;
let backgroundLoading = false;
let suppressDatetimeChange = false;

function syncLoadingOverlay() {
  const visible = loadingDepth > 0 || backgroundLoading;
  els.loadingOverlay.hidden = !visible;
  document.body.classList.toggle("is-loading", visible);
}

function pushLoading(message) {
  loadingDepth += 1;
  if (message) els.loadingText.textContent = message;
  syncLoadingOverlay();
}

function popLoading() {
  loadingDepth = Math.max(0, loadingDepth - 1);
  syncLoadingOverlay();
}

function setBackgroundLoading(loading, message) {
  backgroundLoading = loading;
  if (message) els.loadingText.textContent = message;
  syncLoadingOverlay();
}

function statusClass(code) {
  if (!code) return "";
  if (code >= 500) return "status-5xx";
  if (code >= 400) return "status-4xx";
  if (code >= 300) return "status-3xx";
  return "status-2xx";
}

function pad2(n) {
  return String(n).padStart(2, "0");
}

function parseIsoParts(iso) {
  if (!iso) return null;
  const match = iso.match(/^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})/);
  if (!match) return null;
  return {
    y: Number(match[1]),
    mo: Number(match[2]),
    d: Number(match[3]),
    h: Number(match[4]),
    mi: Number(match[5]),
    s: Number(match[6]),
  };
}

/**
 * ログの表示時刻（ISO）を「壁時計 ms」へ。
 *
 * タイムゾーンオフセットは意図的に無視する。画面の時刻列はログ行ごとの現地時刻で
 * 表示されるため、期間指定も同じ座標系で扱わないと表示と検索が食い違うため。
 */
function isoToWallMs(iso) {
  const p = parseIsoParts(iso);
  if (!p) return null;
  return Date.UTC(p.y, p.mo - 1, p.d, p.h, p.mi, p.s);
}

/** 壁時計 ms を日付・時刻の入力欄の値へ。 */
function wallMsToFields(ms) {
  const t = new Date(ms);
  return {
    date:
      t.getUTCFullYear() +
      "-" +
      pad2(t.getUTCMonth() + 1) +
      "-" +
      pad2(t.getUTCDate()),
    time: pad2(t.getUTCHours()) + ":" + pad2(t.getUTCMinutes()),
  };
}

function formatRangeHint(iso) {
  const p = parseIsoParts(iso);
  if (!p) return iso;
  return `${p.y}-${pad2(p.mo)}-${pad2(p.d)} ${pad2(p.h)}:${pad2(p.mi)}`;
}

function setDatetimeFields(start, end) {
  suppressDatetimeChange = true;
  try {
    els.sinceDate.value = start.date;
    els.sinceTime.value = start.time;
    els.untilDate.value = end.date;
    els.untilTime.value = end.time;
  } finally {
    suppressDatetimeChange = false;
  }
}

/** プログラムから期間を設定するとき、日付入力の min/max を広げる。 */
function widenDateInputBounds(startDate, endDate) {
  const minDate = startDate <= endDate ? startDate : endDate;
  const maxDate = startDate <= endDate ? endDate : startDate;
  if (!els.sinceDate.min || minDate < els.sinceDate.min) {
    els.sinceDate.min = minDate;
    els.untilDate.min = minDate;
  }
  if (!els.sinceDate.max || maxDate > els.sinceDate.max) {
    els.sinceDate.max = maxDate;
    els.untilDate.max = maxDate;
  }
}

function setExactQueryRange(sinceMs, untilMs) {
  const start = wallMsToFields(sinceMs);
  const end = wallMsToFields(untilMs);
  widenDateInputBounds(start.date, end.date);
  exactQueryRange = {
    since: wallMsToApiDatetime(sinceMs),
    until: wallMsToApiDatetime(untilMs),
  };
  setDatetimeFields(start, end);
}

function clearDatetimeFields() {
  els.sinceDate.value = "";
  els.sinceTime.value = "";
  els.untilDate.value = "";
  els.untilTime.value = "";
  clearExactQueryRange();
}

/** 壁時計 ms を API の since/until 形式（yyyy-MM-dd HH:mm:ss）へ。 */
function wallMsToApiDatetime(ms) {
  const t = new Date(ms);
  const y = t.getUTCFullYear();
  const mo = pad2(t.getUTCMonth() + 1);
  const d = pad2(t.getUTCDate());
  const h = pad2(t.getUTCHours());
  const mi = pad2(t.getUTCMinutes());
  const sec = pad2(t.getUTCSeconds());
  return `${y}-${mo}-${d} ${h}:${mi}:${sec}`;
}

/** クイック選択ボタン用。手入力フィールドより優先する正確な since/until。 */
let exactQueryRange = null;

/** 詳細ダイアログ表示中のログ時刻（ISO）。詳細 API は timestamp を返さないため行クリック時に保持する。 */
let detailTimestamp = null;

/** 詳細ダイアログ表示中の Client IP。 */
let detailClientHost = null;

function clearExactQueryRange() {
  exactQueryRange = null;
}

function getSinceParam() {
  if (!els.sinceDate.value) return null;
  // 時刻未指定なら当日の先頭（00:00:00）から。
  const time = els.sinceTime.value ? `${els.sinceTime.value}:00` : "00:00:00";
  return `${els.sinceDate.value} ${time}`;
}

function getUntilParam() {
  if (!els.untilDate.value) return null;
  // 時刻未指定なら当日の末尾（23:59:59）まで含める（until は境界を含む判定のため）。
  const time = els.untilTime.value ? `${els.untilTime.value}:59` : "23:59:59";
  return `${els.untilDate.value} ${time}`;
}

function updateRangeUi() {
  const ready = Boolean(metaRange.first && metaRange.last);
  els.rangeFirst1h.disabled = !ready;
  els.rangeFirst24h.disabled = !ready;
  els.rangeLast1h.disabled = !ready;
  els.rangeLast24h.disabled = !ready;
  if (ready) {
    els.sinceDate.min = wallMsToFields(isoToWallMs(metaRange.first)).date;
    els.sinceDate.max = wallMsToFields(isoToWallMs(metaRange.last)).date;
    els.untilDate.min = els.sinceDate.min;
    els.untilDate.max = els.sinceDate.max;
    els.rangeHint.textContent =
      "ログの範囲: " +
      formatRangeHint(metaRange.first) +
      " 〜 " +
      formatRangeHint(metaRange.last);
  } else {
    els.sinceDate.min = "";
    els.sinceDate.max = "";
    els.untilDate.min = "";
    els.untilDate.max = "";
    els.rangeHint.textContent = "ログ読み込み後に期間ボタンが使えます";
  }
}

function applyFirstHours(hours) {
  if (!metaRange.first || !metaRange.last) return;
  const startMs = isoToWallMs(metaRange.first);
  const endMs = isoToWallMs(metaRange.last);
  if (startMs == null || endMs == null) return;
  const untilMs = Math.min(endMs, startMs + hours * 3600000);
  exactQueryRange = {
    since: wallMsToApiDatetime(startMs),
    until: wallMsToApiDatetime(untilMs),
  };
  setDatetimeFields(wallMsToFields(startMs), wallMsToFields(untilMs));
  offset = 0;
  loadLogs();
}

function applyLastHours(hours) {
  if (!metaRange.first || !metaRange.last) return;
  const endMs = isoToWallMs(metaRange.last);
  const startMs = isoToWallMs(metaRange.first);
  if (endMs == null || startMs == null) return;
  const sinceMs = Math.max(startMs, endMs - hours * 3600000);
  exactQueryRange = {
    since: wallMsToApiDatetime(sinceMs),
    until: wallMsToApiDatetime(endMs),
  };
  setDatetimeFields(wallMsToFields(sinceMs), wallMsToFields(endMs));
  offset = 0;
  loadLogs();
}

function applyAroundMinutes(isoTimestamp, minutes) {
  const centerMs = isoToWallMs(isoTimestamp);
  if (centerMs == null) return false;
  const delta = minutes * 60 * 1000;
  setExactQueryRange(centerMs - delta, centerMs + delta);
  offset = 0;
  loadLogs();
  return true;
}

function escapeRegex(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

function applyFilterByClientHost(clientHost) {
  if (!clientHost || clientHost === "-") return false;
  els.host.value = escapeRegex(clientHost);
  offset = 0;
  loadLogs();
  return true;
}

function getLimit() {
  const n = Number.parseInt(els.pageLimit.value, 10);
  if (!Number.isFinite(n) || n < 1) return DEFAULT_PAGE_LIMIT;
  return Math.min(n, MAX_PAGE_LIMIT);
}

function buildQuery() {
  const params = new URLSearchParams();
  params.set("limit", String(getLimit()));
  params.set("offset", String(offset));
  for (const [key, el] of [
    ["status", els.status],
    ["path", els.path],
    ["method", els.method],
    ["host", els.host],
    ["grep", els.grep],
    ["source", els.source],
  ]) {
    if (el.value.trim()) params.set(key, el.value.trim());
  }
  if (exactQueryRange) {
    params.set("since", exactQueryRange.since);
    params.set("until", exactQueryRange.until);
  } else {
    if (els.sinceDate.value) params.set("since", getSinceParam());
    if (els.untilDate.value) params.set("until", getUntilParam());
  }
  return params;
}

let loadPollTimer = null;
let lastPageItems = [];
/** 直近の検索に使った表示件数。change と Enter の二重リクエストを防ぐ。 */
let appliedLimit = DEFAULT_PAGE_LIMIT;
/** 検索リクエストの通し番号。古い応答で新しい結果を上書きしないために使う。 */
let logsRequestSeq = 0;

function getHighlightNeedle() {
  const text = els.highlight.value.trim();
  return text ? text.toLowerCase() : "";
}

function rowMatchesHighlight(item, needle) {
  if (!needle) return false;
  const haystack = item.raw || [
    item.timestamp,
    item.status,
    item.method,
    item.path,
    item.client_host,
    item.host,
    item.forwarded_for,
    item.source,
    item.line_no,
  ].filter((v) => v != null && v !== "").join(" ");
  return haystack.toLowerCase().includes(needle);
}

function applyRowHighlights() {
  const needle = getHighlightNeedle();
  const rows = els.rows.querySelectorAll("tr");
  for (let i = 0; i < rows.length; i += 1) {
    const item = lastPageItems[i];
    rows[i].classList.toggle(
      "row-highlight",
      Boolean(item && rowMatchesHighlight(item, needle))
    );
  }
}

function setLoadingUi(loading) {
  els.search.disabled = loading;
  els.reset.disabled = loading;
  els.loadDir.disabled = loading;
  els.browse.disabled = loading;
}

function clearLoadPoll() {
  if (loadPollTimer) {
    clearTimeout(loadPollTimer);
    loadPollTimer = null;
  }
}

function scheduleLoadPoll() {
  if (loadPollTimer) return;
  loadPollTimer = setTimeout(async () => {
    loadPollTimer = null;
    const data = await fetchMeta();
    updateMeta(data);
    if (data.loading) {
      scheduleLoadPoll();
      return;
    }
    offset = 0;
    await loadLogs();
  }, 1000);
}

async function fetchMeta() {
  const res = await fetch("/api/meta");
  return res.json();
}

async function loadMeta(options = {}) {
  if (!options.silent) pushLoading("情報を取得中...");
  try {
    const data = await fetchMeta();
    updateMeta(data);
    return data;
  } finally {
    if (!options.silent) popLoading();
  }
}

function updateParseWarning(data) {
  const skipped = data.skipped_lines || 0;
  if (skipped <= 0) {
    els.parseWarning.hidden = true;
    els.parseWarningText.textContent = "";
    els.parseWarningSamples.innerHTML = "";
    return;
  }
  els.parseWarning.hidden = false;
  els.parseWarningText.textContent =
    `${skipped.toLocaleString()} 行を Apache Common/Combined 形式として解析できませんでした（一覧には表示されません）。`;
  els.parseWarningSamples.innerHTML = "";
  const samples = data.skipped_samples || [];
  for (const s of samples) {
    const li = document.createElement("li");
    li.textContent = `${s.source}:${s.line_no} — ${s.preview}`;
    li.title = s.preview;
    els.parseWarningSamples.appendChild(li);
  }
  if (skipped > samples.length) {
    const li = document.createElement("li");
    li.textContent = `…他 ${(skipped - samples.length).toLocaleString()} 行`;
    els.parseWarningSamples.appendChild(li);
  }
}

function updateMeta(data) {
  if (data.directory) {
    els.logDir.value = data.directory;
  }
  if (data.files.length === 0) {
    metaRange = { first: null, last: null };
    els.meta.textContent = "ログファイル未読み込み — ディレクトリを選択してください";
    els.fileList.textContent = "";
    els.fileList.title = "";
    updateParseWarning({});
    setBackgroundLoading(false);
    setLoadingUi(false);
    clearLoadPoll();
    updateRangeUi();
    return;
  }
  if (data.load_error) {
    metaRange = { first: null, last: null };
    els.meta.textContent = `読み込みエラー: ${data.load_error}`;
    els.fileList.textContent = data.files.join(" | ");
    els.fileList.title = data.files.join("\n");
    updateParseWarning({});
    setBackgroundLoading(false);
    setLoadingUi(false);
    clearLoadPoll();
    updateRangeUi();
    return;
  }
  if (data.loading) {
    metaRange = { first: null, last: null };
    const message = `ログを読み込み中... ${data.load_progress.toLocaleString()} 行`;
    els.meta.textContent = `${message} / ファイル ${data.files.length} 件`;
    els.fileList.textContent = data.files.join(" | ");
    els.fileList.title = data.files.join("\n");
    updateParseWarning({});
    setBackgroundLoading(true, message);
    setLoadingUi(true);
    scheduleLoadPoll();
    updateRangeUi();
    return;
  }
  metaRange = { first: data.first, last: data.last };
  els.meta.textContent =
    `${data.total.toLocaleString()} 行 / ファイル ${data.files.length} 件` +
    (data.first ? ` / ${data.first} 〜 ${data.last}` : "");
  els.fileList.textContent = data.files.join(" | ");
  els.fileList.title = data.files.join("\n");
  updateParseWarning(data);
  setBackgroundLoading(false);
  setLoadingUi(false);
  clearLoadPoll();
  updateRangeUi();
}

async function loadDirectory() {
  const directory = els.logDir.value.trim();
  if (!directory) {
    alert("ログディレクトリを入力してください。");
    return;
  }
  pushLoading("ディレクトリを読み込み中...");
  try {
    const res = await fetch("/api/load", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ directory }),
    });
    const data = await res.json();
    if (!res.ok) {
      alert(data.error || "読み込みに失敗しました。");
      return;
    }
    offset = 0;
    updateMeta(data);
    if (!data.loading) {
      await loadLogs();
    }
  } finally {
    popLoading();
  }
}

async function openBrowseDialog() {
  await refreshBrowseList(els.logDir.value.trim());
  els.browseDialog.showModal();
}

/**
 * ディレクトリ一覧を取得して描画する。
 *
 * 取得に失敗した場合は現在位置（browsePath / browseParent）を変更しない。
 * 遷移先を先に代入すると、失敗時に画面表示と現在位置が食い違い、
 * 「選択」で存在しないパスを入力欄へ書き戻してしまうため。
 *
 * @param nextPath 遷移先。省略時は現在位置を読み直す
 */
async function refreshBrowseList(nextPath) {
  pushLoading("ディレクトリ一覧を取得中...");
  try {
    await fetchBrowseList(nextPath);
  } catch (e) {
    alert("ディレクトリ一覧の取得に失敗しました: " + e);
  } finally {
    popLoading();
  }
}

/** 実際の取得と描画（エラー処理は refreshBrowseList 側で行う）。 */
async function fetchBrowseList(nextPath) {
  const target = nextPath !== undefined ? nextPath : browsePath;
  const params = new URLSearchParams();
  if (target) params.set("path", target);
  const res = await fetch("/api/browse?" + params);
  const data = await res.json();
  if (!res.ok) {
    alert(data.error || "ディレクトリ一覧の取得に失敗しました。");
    return;
  }
  browsePath = data.current;
  browseParent = data.parent || null;
  els.browseCurrent.textContent = data.current;
  els.browseUp.disabled = !browseParent;
  els.browseList.innerHTML = "";
  for (const dir of data.directories) {
    const li = document.createElement("li");
    const btn = document.createElement("button");
    btn.type = "button";
    btn.textContent = dir.split(/[/\\]/).pop() || dir;
    btn.title = dir;
    btn.addEventListener("click", async () => {
      await refreshBrowseList(dir);
    });
    li.appendChild(btn);
    els.browseList.appendChild(li);
  }
}

/**
 * ログファイル列の表示文字列。既定は末尾 2 要素だけの短縮表示で、
 * 「フルパス表示」を入れると絶対パスをそのまま出す。
 */
function sourceCellText(item) {
  const path =
    els.fullPath.checked && item.source ? item.source : formatSourceLabel(item.source);
  return path + ":" + item.line_no;
}

/**
 * ログファイル列だけを描き替える。検索をやり直さずに切り替えたいので
 * 行は作り直さない。フルパスのときは列幅の上限を外す（body のクラスで CSS 側を切り替え）。
 */
function applySourceDisplay() {
  document.body.classList.toggle("show-full-path", els.fullPath.checked);
  const rows = els.rows.querySelectorAll("tr");
  for (let i = 0; i < rows.length; i += 1) {
    const item = lastPageItems[i];
    const td = rows[i].querySelector("td.source");
    if (item && td) td.textContent = sourceCellText(item);
  }
}

function addCell(tr, content, options = {}) {
  const td = document.createElement("td");
  const text = content == null || content === "" ? "-" : String(content);
  td.textContent = text;
  if (options.className) td.className = options.className;
  if (options.title) td.title = options.title;
  tr.appendChild(td);
  return td;
}

function formatSourceLabel(source) {
  if (!source) return "-";
  const parts = source.split(/[/\\]/).filter(Boolean);
  if (parts.length <= 1) return parts[0] || source;
  const sep = source.includes("\\") ? "\\" : "/";
  return parts.slice(-2).join(sep);
}

async function loadLogs() {
  appliedLimit = getLimit();
  const seq = ++logsRequestSeq;
  pushLoading("ログを検索中...");
  try {
    const res = await fetch("/api/logs?" + buildQuery());
    const data = await res.json();
    // 後から投げた検索が既に走っている場合、この応答は捨てる（順序の逆転を防ぐ）。
    if (seq !== logsRequestSeq) return;
    if (data.loading) {
      const message = `ログを読み込み中... ${data.load_progress.toLocaleString()} 行`;
      els.resultCount.textContent = message;
      els.pageInfo.textContent = "-";
      els.rows.innerHTML = "";
      lastPageItems = [];
      els.prev.disabled = true;
      els.next.disabled = true;
      setBackgroundLoading(true, message);
      setLoadingUi(true);
      return;
    }
    if (!res.ok) {
      alert(data.error || "取得に失敗しました。");
      return;
    }
    setBackgroundLoading(false);
    setLoadingUi(false);
    lastTotal = data.total;
    const limit = getLimit();
    els.resultCount.textContent = `${data.total.toLocaleString()} 件ヒット`;
    const page = Math.floor(offset / limit) + 1;
    const pages = Math.max(1, Math.ceil(data.total / limit));
    els.pageInfo.textContent = `${page} / ${pages}`;
    els.prev.disabled = offset <= 0;
    els.next.disabled = offset + limit >= data.total;

    els.rows.innerHTML = "";
    lastPageItems = data.items;
    for (const item of data.items) {
      const tr = document.createElement("tr");
      const clientTitle = item.forwarded_for
        ? "X-Forwarded-For: " + item.forwarded_for
        : item.host;

      addCell(tr, item.timestamp);
      addCell(tr, item.status ?? "-", { className: statusClass(item.status) });
      addCell(tr, item.method);
      addCell(tr, item.path, { className: "path", title: item.path });
      addCell(tr, item.client_host, { title: clientTitle });
      addCell(tr, item.host, { title: item.host });
      addCell(tr, sourceCellText(item), {
        className: "source",
        title: item.source + ":" + item.line_no,
      });

      tr.addEventListener("click", async () => {
        pushLoading("詳細を取得中...");
        try {
          const detailRes = await fetch(
            "/api/logs/detail?" +
              new URLSearchParams({
                source: item.source,
                line_no: String(item.line_no),
                timestamp: item.timestamp,
              })
          );
          const detail = await detailRes.json();
          if (!detailRes.ok) {
            alert(detail.error || "詳細の取得に失敗しました。");
            return;
          }
          const xffLine = detail.forwarded_for
            ? "X-Forwarded-For: " + detail.forwarded_for + "\n"
            : "";
          els.detailBody.textContent =
            "ログファイル: " + detail.source + "\n" +
            "行番号: " + detail.line_no + "\n" +
            "Client: " + detail.client_host + "\n" +
            "Remote: " + detail.host + "\n" +
            xffLine + "\n" +
            detail.raw;
          detailTimestamp = item.timestamp;
          detailClientHost = detail.client_host;
          els.detailFilterIp.disabled =
            !detailClientHost || detailClientHost === "-";
          els.detail.showModal();
        } finally {
          popLoading();
        }
      });
      els.rows.appendChild(tr);
    }
    applyRowHighlights();
  } finally {
    popLoading();
  }
}

function resetFilters() {
  for (const el of [
    els.status,
    els.path,
    els.method,
    els.host,
    els.grep,
    els.source,
  ]) {
    el.value = "";
  }
  clearDatetimeFields();
  els.pageLimit.value = String(DEFAULT_PAGE_LIMIT);
  offset = 0;
  loadLogs();
}

els.search.addEventListener("click", () => {
  clearExactQueryRange();
  offset = 0;
  loadLogs();
});

els.reset.addEventListener("click", resetFilters);
els.loadDir.addEventListener("click", loadDirectory);
els.browse.addEventListener("click", openBrowseDialog);
els.rangeFirst1h.addEventListener("click", () => applyFirstHours(1));
els.rangeFirst24h.addEventListener("click", () => applyFirstHours(24));
els.rangeLast1h.addEventListener("click", () => applyLastHours(1));
els.rangeLast24h.addEventListener("click", () => applyLastHours(24));
els.rangeClear.addEventListener("click", () => {
  clearDatetimeFields();
  offset = 0;
  loadLogs();
});
for (const el of [els.sinceDate, els.sinceTime, els.untilDate, els.untilTime]) {
  el.addEventListener("change", () => {
    if (suppressDatetimeChange) return;
    clearExactQueryRange();
    offset = 0;
    loadLogs();
  });
}
els.browseUp.addEventListener("click", async () => {
  if (!browseParent) return;
  await refreshBrowseList(browseParent);
});
els.browseSelect.addEventListener("click", () => {
  els.logDir.value = browsePath;
  els.browseDialog.close();
});

function bindDetailSearchAround(button, minutes) {
  button.addEventListener("click", () => {
    if (!detailTimestamp) return;
    if (applyAroundMinutes(detailTimestamp, minutes)) {
      els.detail.close();
    }
  });
}

bindDetailSearchAround(els.detailSearchAround1, 1);
bindDetailSearchAround(els.detailSearchAround5, 5);

els.detailFilterIp.addEventListener("click", () => {
  if (applyFilterByClientHost(detailClientHost)) {
    els.detail.close();
  }
});

els.regexSamples.addEventListener("click", () => {
  els.regexSamplesDialog.showModal();
});

els.highlight.addEventListener("input", applyRowHighlights);
els.fullPath.addEventListener("change", applySourceDisplay);
// リロードでチェック状態が復元されることがあるので、初期表示でも body のクラスを合わせる
applySourceDisplay();

// 表示件数を変えるとページ位置が合わなくなるため、先頭ページから引き直す。
// Enter で確定した場合は keydown 側が既に検索しているので、その分は読み飛ばす。
els.pageLimit.addEventListener("change", () => {
  if (getLimit() === appliedLimit) return;
  offset = 0;
  loadLogs();
});

els.prev.addEventListener("click", () => {
  offset = Math.max(0, offset - getLimit());
  loadLogs();
});
els.next.addEventListener("click", () => {
  const limit = getLimit();
  if (offset + limit < lastTotal) {
    offset += limit;
    loadLogs();
  }
});

document.addEventListener("keydown", (e) => {
  if (e.key === "Enter" && e.target.tagName === "INPUT") {
    if (e.target === els.logDir) {
      loadDirectory();
      return;
    }
    if (e.target === els.highlight) {
      applyRowHighlights();
      return;
    }
    if (
      e.target === els.sinceDate ||
      e.target === els.sinceTime ||
      e.target === els.untilDate ||
      e.target === els.untilTime
    ) {
      clearExactQueryRange();
    }
    offset = 0;
    loadLogs();
  }
});

loadMeta().then(async (data) => {
  if (!data.loading) {
    await loadLogs();
  }
});

updateRangeUi();
