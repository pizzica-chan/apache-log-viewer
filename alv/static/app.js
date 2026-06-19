const limit = 200;
let offset = 0;
let lastTotal = 0;
let browsePath = "";

const els = {
  meta: document.getElementById("meta"),
  logDir: document.getElementById("log-dir"),
  browse: document.getElementById("browse"),
  loadDir: document.getElementById("load-dir"),
  fileList: document.getElementById("file-list"),
  status: document.getElementById("status"),
  path: document.getElementById("path"),
  method: document.getElementById("method"),
  host: document.getElementById("host"),
  since: document.getElementById("since"),
  until: document.getElementById("until"),
  grep: document.getElementById("grep"),
  source: document.getElementById("source"),
  search: document.getElementById("search"),
  reset: document.getElementById("reset"),
  rows: document.getElementById("rows"),
  resultCount: document.getElementById("result-count"),
  pageInfo: document.getElementById("page-info"),
  prev: document.getElementById("prev"),
  next: document.getElementById("next"),
  detail: document.getElementById("detail"),
  detailBody: document.getElementById("detail-body"),
  browseDialog: document.getElementById("browse-dialog"),
  browseCurrent: document.getElementById("browse-current"),
  browseList: document.getElementById("browse-list"),
  browseUp: document.getElementById("browse-up"),
  browseSelect: document.getElementById("browse-select"),
};

function statusClass(code) {
  if (!code) return "";
  if (code >= 500) return "status-5xx";
  if (code >= 400) return "status-4xx";
  if (code >= 300) return "status-3xx";
  return "status-2xx";
}

function buildQuery() {
  const params = new URLSearchParams();
  params.set("limit", String(limit));
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
  if (els.since.value) params.set("since", els.since.value.replace("T", " ") + ":00");
  if (els.until.value) params.set("until", els.until.value.replace("T", " ") + ":00");
  return params;
}

function updateMeta(data) {
  if (data.directory) {
    els.logDir.value = data.directory;
  }
  if (data.files.length === 0) {
    els.meta.textContent = "ログファイル未読み込み — ディレクトリを選択してください";
    els.fileList.textContent = "";
    return;
  }
  els.meta.textContent =
    `${data.total.toLocaleString()} 行 / ファイル ${data.files.length} 件` +
    (data.first ? ` / ${data.first} 〜 ${data.last}` : "");
  els.fileList.textContent = data.files.join(" | ");
}

async function loadMeta() {
  const res = await fetch("/api/meta");
  const data = await res.json();
  updateMeta(data);
}

async function loadDirectory() {
  const directory = els.logDir.value.trim();
  if (!directory) {
    alert("ログディレクトリを入力してください。");
    return;
  }
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
  await loadLogs();
}

async function openBrowseDialog() {
  browsePath = els.logDir.value.trim();
  await refreshBrowseList();
  els.browseDialog.showModal();
}

async function refreshBrowseList() {
  const params = new URLSearchParams();
  if (browsePath) params.set("path", browsePath);
  const res = await fetch("/api/browse?" + params);
  const data = await res.json();
  if (!res.ok) {
    alert(data.error || "ディレクトリ一覧の取得に失敗しました。");
    return;
  }
  browsePath = data.current;
  els.browseCurrent.textContent = data.current;
  els.browseUp.disabled = !data.parent;
  els.browseList.innerHTML = "";
  for (const dir of data.directories) {
    const li = document.createElement("li");
    const btn = document.createElement("button");
    btn.type = "button";
    btn.textContent = dir.split(/[/\\]/).pop() || dir;
    btn.title = dir;
    btn.addEventListener("click", async () => {
      browsePath = dir;
      await refreshBrowseList();
    });
    li.appendChild(btn);
    els.browseList.appendChild(li);
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

async function loadLogs() {
  const res = await fetch("/api/logs?" + buildQuery());
  const data = await res.json();
  lastTotal = data.total;
  els.resultCount.textContent = `${data.total.toLocaleString()} 件ヒット`;
  const page = Math.floor(offset / limit) + 1;
  const pages = Math.max(1, Math.ceil(data.total / limit));
  els.pageInfo.textContent = `${page} / ${pages}`;
  els.prev.disabled = offset <= 0;
  els.next.disabled = offset + limit >= data.total;

  els.rows.innerHTML = "";
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
    addCell(tr, item.bytes_sent ?? "-");
    addCell(tr, item.source + ":" + item.line_no, {
      className: "source",
      title: item.source,
    });

    tr.addEventListener("click", () => {
      const xffLine = item.forwarded_for
        ? "X-Forwarded-For: " + item.forwarded_for + "\n"
        : "";
      els.detailBody.textContent =
        "ファイル: " + item.source + "\n" +
        "行番号: " + item.line_no + "\n" +
        "Client: " + item.client_host + "\n" +
        "Remote: " + item.host + "\n" +
        xffLine + "\n" +
        item.raw;
      els.detail.showModal();
    });
    els.rows.appendChild(tr);
  }
}

function resetFilters() {
  for (const el of [
    els.status,
    els.path,
    els.method,
    els.host,
    els.since,
    els.until,
    els.grep,
    els.source,
  ]) {
    el.value = "";
  }
  offset = 0;
  loadLogs();
}

els.search.addEventListener("click", () => {
  offset = 0;
  loadLogs();
});

els.reset.addEventListener("click", resetFilters);
els.loadDir.addEventListener("click", loadDirectory);
els.browse.addEventListener("click", openBrowseDialog);
els.browseUp.addEventListener("click", async () => {
  const params = new URLSearchParams();
  params.set("path", browsePath);
  const res = await fetch("/api/browse?" + params);
  const data = await res.json();
  if (data.parent) {
    browsePath = data.parent;
    await refreshBrowseList();
  }
});
els.browseSelect.addEventListener("click", () => {
  els.logDir.value = browsePath;
  els.browseDialog.close();
});

els.prev.addEventListener("click", () => {
  offset = Math.max(0, offset - limit);
  loadLogs();
});
els.next.addEventListener("click", () => {
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
    offset = 0;
    loadLogs();
  }
});

loadMeta().then(loadLogs);
