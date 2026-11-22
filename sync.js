/* ============================================================
   Ghibli Calendar — optional cloud sync (sync.js)

   WHAT IT DOES
   Keeps your tasks / events / reminders / day notes / streak identical on every
   device or browser where you switch it on (Chrome PWA, the Android app, ...).
   The shared copy is ONE private file in YOUR OWN GitHub repository.

   SAFETY RULES
   - Does nothing until you configure it (sync-setup.html). Without a token this
     file is inert and your calendar behaves exactly as before.
   - Merges, never overwrites: a 3-way merge by item id against the last synced copy.
       new on either side      -> kept
       edited on one side      -> that edit is kept
       edited on both sides    -> this device's version wins (or the newer updatedAt)
       deleted on one side     -> deleted, unless the other side edited it (edit wins)
   - The very first sync on a device only ADDS items (it never deletes anything).
   - Before it ever changes local data, it stores a backup copy (last 3 are kept).
   - If the cloud file is unreadable or invalid, sync stops and changes nothing.
   - Device-only things are never synced: settings, theme, companion, notification log.

   LIMITS (honest)
   - It syncs when the page opens, when you come back to it, and a few seconds after
     each change. It is not instant, and nothing syncs while a device is closed.
   - Two devices editing the SAME item at the same moment: one edit wins.
   - The token lives in this browser's localStorage. Use a fine-grained token limited to
     ONE private repo with only "Contents: Read and write".
   ============================================================ */
(function (root) {
  "use strict";

  var KEY = "ghibliforest_v1";
  var CFG = "gf_sync_cfg_v1";
  var BASE = "gf_sync_base_v1";
  var STATUS = "gf_sync_status_v1";
  var BACKUP = "gf_sync_backup_";
  var SYNCED = ["tasks", "events", "reminders", "days", "streak"];

  // ---------- helpers ----------
  function stable(v) {
    if (v === undefined) return "undefined";
    if (v === null || typeof v !== "object") return JSON.stringify(v);
    if (Array.isArray(v)) return "[" + v.map(stable).join(",") + "]";
    return "{" + Object.keys(v).sort().map(function (k) { return JSON.stringify(k) + ":" + stable(v[k]); }).join(",") + "}";
  }
  function pick(obj) {
    var o = {};
    if (!obj) return o;
    SYNCED.forEach(function (k) { if (obj[k] !== undefined) o[k] = obj[k]; });
    return o;
  }
  function newer(l, r) {
    if (l && r && typeof l.updatedAt === "string" && typeof r.updatedAt === "string" && r.updatedAt > l.updatedAt) return r;
    return l;
  }
  var DROP = { drop: true };

  // one item present/absent on each side
  function mergeEntry(l, r, b, hasBase) {
    var inL = l !== undefined, inR = r !== undefined, inB = b !== undefined;
    if (inL && inR) {
      var lj = stable(l), rj = stable(r);
      if (lj === rj) return l;
      if (!hasBase || !inB) return newer(l, r);
      var bj = stable(b);
      if (lj === bj) return r;      // only remote changed
      if (rj === bj) return l;      // only local changed
      return newer(l, r);           // both changed
    }
    if (inL) {                      // only local has it
      if (!hasBase || !inB) return l;                 // new here
      return stable(l) === stable(b) ? DROP : l;      // deleted elsewhere (unless edited here)
    }
    if (inR) {                      // only remote has it
      if (!hasBase || !inB) return r;                 // new there
      return stable(r) === stable(b) ? DROP : r;      // deleted here (unless edited there)
    }
    return DROP;
  }

  function idOf(item) { return item && item.id != null ? "id:" + item.id : "j:" + stable(item); }

  function indexList(list) {
    var map = {}, order = [];
    (list || []).forEach(function (it) { var k = idOf(it); if (!(k in map)) { map[k] = it; order.push(k); } });
    return { map: map, order: order };
  }

  function mergeList(L, R, B, hasBase) {
    var l = indexList(L), r = indexList(R), b = indexList(B);
    var keys = l.order.concat(r.order.filter(function (k) { return !(k in l.map); }));
    var out = [];
    keys.forEach(function (k) {
      var v = mergeEntry(l.map[k], r.map[k], b.map[k], hasBase);
      if (v !== DROP) out.push(v);
    });
    return out;
  }

  function mergeMap(L, R, B, hasBase) {
    L = L || {}; R = R || {}; B = B || {};
    var keys = Object.keys(L).concat(Object.keys(R).filter(function (k) { return !(k in L); }));
    var out = {};
    keys.forEach(function (k) {
      var v = mergeEntry(L[k], R[k], B[k], hasBase);
      if (v !== DROP) out[k] = v;
    });
    return out;
  }

  function mergeStreak(L, R) {
    if (!L) return R; if (!R) return L;
    var a = L.lastActiveDate || "", b = R.lastActiveDate || "";
    if (a !== b) return a > b ? L : R;
    return (L.count || 0) >= (R.count || 0) ? L : R;
  }

  function idSet(S) {
    var set = {};
    if (!S) return set;
    ["tasks", "events", "reminders"].forEach(function (k) { (S[k] || []).forEach(function (it) { set[k + ":" + idOf(it)] = 1; }); });
    return set;
  }

  /** L = local state, R = cloud state, B = last synced state (or null). Returns the merged state. */
  function merge(L, R, B) {
    var hasBase = !!B;
    var M = {};
    if (L) Object.keys(L).forEach(function (k) { M[k] = L[k]; }); // device-only keys stay as they are
    ["tasks", "events", "reminders"].forEach(function (k) {
      M[k] = mergeList(L && L[k], R && R[k], B && B[k], hasBase);
    });
    M.days = mergeMap(L && L.days, R && R.days, B && B.days, hasBase);
    var s = mergeStreak(L && L.streak, R && R.streak);
    if (s) M.streak = s;
    return M;
  }

  // ---------- GitHub file ----------
  function b64enc(str) {
    if (typeof Buffer !== "undefined") return Buffer.from(str, "utf8").toString("base64");
    return btoa(unescape(encodeURIComponent(str)));
  }
  function b64dec(b64) {
    var clean = String(b64).replace(/\s/g, "");
    if (typeof Buffer !== "undefined") return Buffer.from(clean, "base64").toString("utf8");
    return decodeURIComponent(escape(atob(clean)));
  }
  function apiUrl(cfg) {
    return "https://api.github.com/repos/" + encodeURIComponent(cfg.owner) + "/" + encodeURIComponent(cfg.repo) +
      "/contents/" + cfg.path.split("/").map(encodeURIComponent).join("/");
  }
  function headers(cfg) {
    return { "Authorization": "Bearer " + cfg.token, "Accept": "application/vnd.github+json", "X-GitHub-Api-Version": "2022-11-28" };
  }

  async function getRemote(env, cfg) {
    var res = await env.fetch(apiUrl(cfg) + "?_=" + env.now() + (cfg.branch ? "&ref=" + encodeURIComponent(cfg.branch) : ""),
      { headers: headers(cfg), cache: "no-store" });
    if (res.status === 404) return { sha: null, text: null };
    if (res.status === 401 || res.status === 403) throw new Error("GitHub refused the token (check it and its permissions).");
    if (!res.ok) throw new Error("GitHub error " + res.status);
    var j = await res.json();
    if (j.encoding !== "base64") throw new Error("Cloud file is too large for this simple sync.");
    return { sha: j.sha, text: b64dec(j.content) };
  }

  async function putRemote(env, cfg, state, sha) {
    var body = { message: "Ghibli Calendar sync", content: b64enc(JSON.stringify(pick(state))) };
    if (sha) body.sha = sha;
    if (cfg.branch) body.branch = cfg.branch;
    var h = headers(cfg); h["Content-Type"] = "application/json";
    var res = await env.fetch(apiUrl(cfg), { method: "PUT", headers: h, body: JSON.stringify(body) });
    if (res.status === 409 || res.status === 422) return { conflict: true };
    if (!res.ok) return { ok: false, error: "GitHub error " + res.status };
    return { ok: true };
  }

  // ---------- one sync run ----------
  function readJSON(ls, key) {
    var t = ls.getItem(key);
    if (!t) return null;
    try { return JSON.parse(t); } catch (e) { return undefined; }
  }

  function setStatus(env, ok, msg) {
    try { env.ls.setItem(STATUS, JSON.stringify({ ok: ok, msg: msg, at: new Date(env.now()).toISOString() })); } catch (e) {}
  }

  function backup(env, text) {
    if (!text) return;
    try {
      env.ls.setItem(BACKUP + env.now(), text);
      var keys = [];
      for (var i = 0; i < env.ls.length; i++) { var k = env.ls.key(i); if (k && k.indexOf(BACKUP) === 0) keys.push(k); }
      keys.sort();
      while (keys.length > 3) env.ls.removeItem(keys.shift());
    } catch (e) {}
  }

  /**
   * env = { ls, fetch, now(), selfWrite(bool), apply(): called after local data was changed }
   * returns { skipped | ok, localChanged, remoteChanged | error }
   */
  async function syncOnce(env) {
    var cfg = readJSON(env.ls, CFG);
    if (!cfg || !cfg.token || !cfg.owner || !cfg.repo) return { skipped: true };
    cfg.path = cfg.path || "ghibli-data.json";
    try {
      for (var attempt = 0; attempt < 4; attempt++) {
        var remote = await getRemote(env, cfg);
        var R = null;
        if (remote.text != null) {
          try { R = JSON.parse(remote.text); } catch (e) { R = undefined; }
          if (R === undefined || !R || typeof R !== "object" || Array.isArray(R)) throw new Error("The cloud file is not valid calendar data. Sync stopped; nothing was changed.");
        }
        var Ltext = env.ls.getItem(KEY);
        var L = readJSON(env.ls, KEY);
        if (L === undefined) throw new Error("Local calendar data could not be read. Sync stopped; nothing was changed.");
        var B = readJSON(env.ls, BASE);
        if (B === undefined) B = null;

        var M = merge(L, R, B);
        // safety net: refuse a merge that would suddenly delete most of the calendar
        if (B) {
          var bi = idSet(B), mi = idSet(M), total = 0, gone = 0;
          Object.keys(bi).forEach(function (k) { total++; if (!(k in mi)) gone++; });
          if (gone >= 5 && gone > total / 2) throw new Error("Sync stopped: it would have removed " + gone + " of " + total + " items. Nothing was changed. Use “Re-link” on the sync page if this is really what you want.");
        }
        var localChanged = !L || stable(pick(M)) !== stable(pick(L));
        var remoteChanged = !R || stable(pick(M)) !== stable(pick(R));

        if (remoteChanged) {
          var res = await putRemote(env, cfg, M, remote.sha);
          if (res.conflict) continue;                 // someone else wrote first: re-read and merge again
          if (!res.ok) throw new Error(res.error);
        }
        // did the user change something while we were talking to GitHub? then wait for the next run
        if (env.ls.getItem(KEY) !== Ltext) {
          setStatus(env, true, "Synced (a newer local change will sync next)");
          return { ok: true, again: true, localChanged: false, remoteChanged: remoteChanged };
        }
        if (localChanged) {
          backup(env, Ltext);
          env.selfWrite(true);
          try { env.ls.setItem(KEY, JSON.stringify(M)); } finally { env.selfWrite(false); }
        }
        env.ls.setItem(BASE, JSON.stringify(pick(M)));
        setStatus(env, true, "Synced");
        if (localChanged && env.apply) env.apply();
        return { ok: true, localChanged: localChanged, remoteChanged: remoteChanged };
      }
      throw new Error("Cloud file kept changing; will try again later.");
    } catch (e) {
      setStatus(env, false, String(e && e.message || e));
      return { ok: false, error: String(e && e.message || e) };
    }
  }

  var api = { merge: merge, syncOnce: syncOnce, stable: stable, KEY: KEY, CFG: CFG, BASE: BASE, STATUS: STATUS };

  if (typeof module !== "undefined" && module.exports) { module.exports = api; return; }

  // ---------- browser start-up ----------
  root.GFSync = api;
  try {
    if (!root.localStorage || !root.localStorage.getItem(CFG)) return; // not configured: completely inert
  } catch (e) { return; }

  var selfWrite = false, running = false, queued = false, timer = null;
  var env = {
    ls: root.localStorage,
    fetch: function (u, o) { return root.fetch(u, o); },
    now: function () { return Date.now(); },
    selfWrite: function (v) { selfWrite = v; },
    apply: function () {
      // The page keeps its data in memory; reload so it can never write an out-of-date copy back.
      var n = parseInt(root.sessionStorage.getItem("gf_sync_reloads") || "0", 10);
      var t = parseInt(root.sessionStorage.getItem("gf_sync_reload_t") || "0", 10);
      if (Date.now() - t > 60000) n = 0;
      if (n >= 3) return;
      root.sessionStorage.setItem("gf_sync_reloads", String(n + 1));
      root.sessionStorage.setItem("gf_sync_reload_t", String(Date.now()));
      root.location.reload();
    }
  };

  function run() {
    if (running) { queued = true; return; }
    running = true;
    syncOnce(env).then(function (r) {
      running = false;
      if ((queued || (r && r.again))) { queued = false; schedule(3000); }
    }, function () { running = false; });
  }
  function schedule(ms) { clearTimeout(timer); timer = setTimeout(run, ms); }

  try {
    var orig = Storage.prototype.setItem;
    Storage.prototype.setItem = function (k) {
      var r = orig.apply(this, arguments);
      if (k === KEY && !selfWrite && this === root.localStorage) schedule(4000);
      return r;
    };
  } catch (e) {}
  root.addEventListener("online", function () { schedule(1000); });
  root.document.addEventListener("visibilitychange", function () { if (root.document.visibilityState === "visible") schedule(800); });
  root.addEventListener("load", function () { schedule(1200); });
})(typeof window !== "undefined" ? window : globalThis);
