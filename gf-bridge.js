/* Injected by the Android app BEFORE the website's own scripts run.
   It does not change the website's files, look or behaviour. It only:
   1) puts the shared calendar data into localStorage (so widget edits show up),
   2) forwards every save to the phone so the widget updates,
   3) lets the website's existing notifications show as real Android notifications. */
(function () {
  var KEY = "ghibliforest_v1";
  var injected = __GF_STATE__;
  try { if (injected) localStorage.setItem(KEY, injected); } catch (e) {}

  function push(v) {
    try { if (window.GFNative && window.GFNative.saveState) window.GFNative.saveState(String(v)); } catch (e) {}
  }
  try {
    var origSet = Storage.prototype.setItem;
    Storage.prototype.setItem = function (k, v) {
      origSet.apply(this, arguments);
      if (k === KEY && this === window.localStorage) push(v);
    };
  } catch (e) {}
  window.addEventListener("load", function () {
    try { var v = localStorage.getItem(KEY); if (v) push(v); } catch (e) {}
  });

  /* ---- notifications: the Android WebView has no Notification API, so map it ---- */
  function N(title, opts) {
    opts = opts || {};
    try { window.GFNative.showNotification(String(title), String(opts.body || ""), String((opts.data && opts.data.day) || ""), String(opts.tag || ""), "[]"); } catch (e) {}
  }
  Object.defineProperty(N, "permission", { get: function () { try { return window.GFNative.permission(); } catch (e) { return "denied"; } } });
  N.requestPermission = function (cb) {
    return new Promise(function (res) {
      window.__gfPerm = function (r) { res(r); if (typeof cb === "function") cb(r); };
      try { window.GFNative.requestPermission(); } catch (e) { res("denied"); }
    });
  };
  try { window.Notification = N; } catch (e) {}
  try {
    if (window.ServiceWorkerRegistration) {
      ServiceWorkerRegistration.prototype.showNotification = function (title, opts) {
        opts = opts || {};
        try {
          window.GFNative.showNotification(String(title), String(opts.body || ""), String((opts.data && opts.data.day) || ""), String(opts.tag || ""),
            JSON.stringify((opts.actions || []).map(function (a) { return { a: a.action, t: a.title }; })));
        } catch (e) {}
        return Promise.resolve();
      };
    }
  } catch (e) {}
})();
