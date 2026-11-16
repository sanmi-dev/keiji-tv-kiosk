// 音楽ブラウザのループ見張り（app/src/main/assets/loop-watch.js）のテスト。
// 実行: node tests/loop-watch.test.js
// 仕様: kjLoopTick(win, nowMs, msSinceUserCmd) を数秒ごとに呼ぶ。戻り値は 'none'|'replay'|'resume'|'restart-list'。
const fs = require('fs');
const path = require('path');
const src = fs.readFileSync(path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'loop-watch.js'), 'utf8');
const kjLoopTick = new Function(src + '\nreturn kjLoopTick;')();

let passed = 0, failed = 0;
function check(label, fn) {
  try { fn(); passed++; console.log('PASS ' + label); }
  catch (e) { failed++; console.log('FAIL ' + label + '  -> ' + e.message); }
}
function eq(e, a, m) { if (e !== a) throw new Error((m || '') + ' expected=' + e + ' actual=' + a); }

function media(o) {
  const m = Object.assign({ paused: false, ended: false, currentTime: 50, plays: 0 }, o);
  m.play = function () { m.plays++; m.paused = false; m.ended = false; return Promise.resolve(); };
  return m;
}
function win(href, m, opts) {
  const store = {};
  const w = {
    location: { href },
    document: { querySelector: (sel) => (m && ((sel === 'video' && m.tag === 'video') || (sel === 'audio' && m.tag === 'audio')) ? m : null) },
    sessionStorage: (opts && opts.noStorage) ? undefined : {
      getItem: (k) => (k in store ? store[k] : null), setItem: (k, v) => { store[k] = String(v); }
    }
  };
  return w;
}
const FAR = 1e9;

check('メディアが無いページは何もしない', () => {
  eq('none', kjLoopTick(win('about:blank', null), 0, FAR));
});
check('再生中は何もしない', () => {
  const m = media({ tag: 'video' }); const w = win('https://www.youtube.com/watch?v=A', m);
  eq('none', kjLoopTick(w, 0, FAR)); eq('none', kjLoopTick(w, 20000, FAR)); eq(0, m.plays);
});
check('単曲が終わったら8秒後に頭から再生し直す', () => {
  const m = media({ tag: 'video', ended: true, paused: true }); const w = win('https://www.youtube.com/watch?v=A', m);
  eq('none', kjLoopTick(w, 0, FAR));
  eq('none', kjLoopTick(w, 5000, FAR));
  eq('replay', kjLoopTick(w, 8000, FAR));
  eq(0, m.currentTime); eq(1, m.plays);
});
check('終わった直後にYouTubeが次の曲へ進んだら（URLが変わったら）何もしない', () => {
  const m = media({ tag: 'video', ended: true, paused: true }); const w = win('https://www.youtube.com/watch?v=A&list=L', m);
  eq('none', kjLoopTick(w, 0, FAR));
  w.location.href = 'https://www.youtube.com/watch?v=B&list=L';
  eq('none', kjLoopTick(w, 9000, FAR)); eq(0, m.plays);
});
check('再生リストの最後が終わったら最初の曲（覚えておいた v）へ戻る', () => {
  const m = media({ tag: 'video' }); const w = win('https://www.youtube.com/watch?v=FIRST&list=L&index=1', m);
  eq('none', kjLoopTick(w, 0, FAR));
  w.location.href = 'https://www.youtube.com/watch?v=LAST&list=L&index=3';
  eq('none', kjLoopTick(w, 1000, FAR));
  m.ended = true; m.paused = true;
  eq('none', kjLoopTick(w, 2000, FAR));
  eq('restart-list', kjLoopTick(w, 10000, FAR));
  eq('https://www.youtube.com/watch?v=FIRST&list=L&index=1', w.location.href);
});
check('最初の曲を覚えていない再生リストは、その曲を頭から再生し直す', () => {
  const m = media({ tag: 'video', ended: true, paused: true }); const w = win('https://www.youtube.com/watch?v=X&list=L&index=5', m, { noStorage: true });
  // index=5 は最初の曲ではないが、記録が無ければ最初に見た曲を覚える → その曲へ戻る
  eq('none', kjLoopTick(w, 0, FAR));
  const r = kjLoopTick(w, 9000, FAR);
  if (r !== 'restart-list' && r !== 'replay') throw new Error('unexpected ' + r);
  if (r === 'restart-list') eq('https://www.youtube.com/watch?v=X&list=L&index=1', w.location.href);
});
check('勝手に止まったら（直前に操作なし）8秒後に再生し直す', () => {
  const m = media({ tag: 'audio', paused: true }); const w = win('http://127.0.0.1:8787/mp3', m);
  eq('none', kjLoopTick(w, 0, FAR)); eq('none', kjLoopTick(w, 7000, FAR));
  eq('resume', kjLoopTick(w, 8000, FAR)); eq(1, m.plays);
});
check('PCから操作した直後に止まったら（意図的な一時停止）再生し直さない', () => {
  const m = media({ tag: 'video', paused: true }); const w = win('https://www.youtube.com/watch?v=A', m);
  eq('none', kjLoopTick(w, 0, 1200));
  eq('none', kjLoopTick(w, 60000, 61200)); eq('none', kjLoopTick(w, 600000, 601200)); eq(0, m.plays);
});
check('意図的な一時停止のあと再生されたら、次の勝手な停止は見張る', () => {
  const m = media({ tag: 'video', paused: true }); const w = win('https://www.youtube.com/watch?v=A', m);
  kjLoopTick(w, 0, 500);
  m.paused = false; kjLoopTick(w, 5000, 5500);
  m.paused = true; kjLoopTick(w, 100000, FAR);
  eq('resume', kjLoopTick(w, 108000, FAR));
});
check('video が無ければ audio を見る（MP3ページ）', () => {
  const m = media({ tag: 'audio', ended: true, paused: true }); const w = win('http://127.0.0.1:8787/mp3', m);
  kjLoopTick(w, 0, FAR); eq('replay', kjLoopTick(w, 8000, FAR));
});
check('play() が例外でも落ちない', () => {
  const m = media({ tag: 'video', paused: true }); m.play = () => { throw new Error('NotAllowed'); };
  const w = win('https://www.youtube.com/watch?v=A', m);
  kjLoopTick(w, 0, FAR); kjLoopTick(w, 8000, FAR);
});
check('play() の Promise が reject しても落ちない', () => {
  const m = media({ tag: 'video', paused: true }); m.play = () => Promise.reject(new Error('x'));
  const w = win('https://www.youtube.com/watch?v=A', m);
  kjLoopTick(w, 0, FAR); kjLoopTick(w, 8000, FAR);
});

check('操作の10秒後に見張りが気づいた停止も意図的とみなす（見張り間隔5秒＋余裕）', () => {
  const m = media({ tag: 'video', paused: true }); const w = win('https://www.youtube.com/watch?v=A', m);
  eq('none', kjLoopTick(w, 0, 10000)); eq('none', kjLoopTick(w, 20000, 30000)); eq(0, m.plays);
});
check('再生リストの戻り先は元のページと同じ住所（music.youtube.com など）', () => {
  const m = media({ tag: 'video' }); const w = win('https://music.youtube.com/watch?v=F&list=L&index=1', m);
  kjLoopTick(w, 0, FAR); m.ended = true; m.paused = true; kjLoopTick(w, 1000, FAR);
  eq('restart-list', kjLoopTick(w, 9000, FAR));
  eq('https://music.youtube.com/watch?v=F&list=L&index=1', w.location.href);
});
check('YouTube のメイン動画（video.html5-main-video）を優先して見る', () => {
  const main = media({ tag: 'video', paused: true }); const other = media({ tag: 'video', paused: true });
  const w = win('https://www.youtube.com/watch?v=A', null);
  w.document.querySelector = (sel) => sel === 'video.html5-main-video' ? main : (sel === 'video' ? other : null);
  kjLoopTick(w, 0, FAR); kjLoopTick(w, 8000, FAR); eq(1, main.plays); eq(0, other.plays);
});

console.log(failed ? `${failed} FAILED / ${passed} passed` : `ALL PASS (${passed})`);
process.exitCode = failed ? 1 : 0;
