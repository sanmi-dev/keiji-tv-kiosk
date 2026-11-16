// 音楽ブラウザのループ見張り。数秒ごとに kjLoopTick(window, Date.now(), 操作からの経過ms) を呼ぶ。
// 戻り値: 'none' | 'replay' | 'resume' | 'restart-list'
function kjLoopTick(win, nowMs, msSinceUserCmd) {
  try {
    var doc = win.document;
    var media = doc.querySelector('video.html5-main-video') || doc.querySelector('video') || doc.querySelector('audio');
    if (!media) return 'none';
    var st = win.__kjLoop;
    if (!st) { st = win.__kjLoop = { pausedAt: null, intentional: false, endedAt: null, endedUrl: null, first: {} }; }
    var href = String(win.location.href);
    var param = function (name) {
      var m = new RegExp('[?&]' + name + '=([^&#]*)').exec(href);
      return m ? m[1] : null;
    };
    var list = param('list'), v = param('v'), index = param('index');
    var firstV = null;
    if (list) {
      var key = 'kjFirst:' + list;
      var stored = null, ss = null;
      try { ss = win.sessionStorage; if (ss) stored = ss.getItem(key); } catch (e) { ss = null; }
      if (!stored) stored = st.first[list] || null;
      if (v && (!stored || index === '1')) {
        stored = v;
        st.first[list] = v;
        try { if (ss) ss.setItem(key, v); } catch (e2) { }
      }
      firstV = stored;
    }
    var safePlay = function () {
      try {
        var p = media.play();
        if (p && typeof p.catch === 'function') p.catch(function () { });
      } catch (e3) { }
    };
    if (media.ended) {
      st.pausedAt = null; st.intentional = false;
      if (st.endedAt === null || st.endedUrl !== href) {
        st.endedAt = nowMs; st.endedUrl = href;
        return 'none';
      }
      if (nowMs - st.endedAt >= 8000) {
        st.endedAt = null; st.endedUrl = null;
        if (list && firstV) {
          win.location.href = (String(win.location.href).match(/^https?:\/\/[^\/]+/) || ['https://www.youtube.com'])[0] + '/watch?v=' + firstV + '&list=' + list + '&index=1';
          return 'restart-list';
        }
        try { media.currentTime = 0; } catch (e4) { }
        safePlay();
        return 'replay';
      }
      return 'none';
    }
    st.endedAt = null; st.endedUrl = null;
    if (media.paused) {
      if (st.pausedAt === null) {
        st.pausedAt = nowMs;
        st.intentional = msSinceUserCmd < 15000;
        return 'none';
      }
      if (!st.intentional && nowMs - st.pausedAt >= 8000) {
        st.pausedAt = null;
        safePlay();
        return 'resume';
      }
      return 'none';
    }
    st.pausedAt = null; st.intentional = false;
    return 'none';
  } catch (e) {
    return 'none';
  }
}
