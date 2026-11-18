// 1.2.6: PC の操作画面（store.html）の「リンクをテレビに映す」と、入口ページ（sanmi-apps/music.html）の受け渡しのテスト。
// 実行: node tests/cast-link.test.js   （keiji-tv-kiosk フォルダで実行）
// 仕様:
//   store.html の /*<cast-link>*/ 〜 /*</cast-link>*/ の間に function kjCastLink(input) を置く。
//     YouTube のリンクなら整えた https の URL、それ以外は ''。
//   music.html の /*<cast-pass>*/ 〜 /*</cast-pass>*/ の間に function withCast(target, storeId, hash) を置く。
//     target（http://IP:ポート/store）に ?store=店番号 と、正しい形の #cast=... だけを付けて返す。
const fs = require('fs');
const path = require('path');
function block(file, name) {
  const src = fs.readFileSync(file, 'utf8');
  const m = new RegExp('/\\*<' + name + '>\\*/([\\s\\S]*?)/\\*</' + name + '>\\*/').exec(src);
  if (!m) throw new Error(path.basename(file) + ' に /*<' + name + '>*/ の区切りがありません');
  return m[1];
}
let passed = 0, failed = 0;
function check(label, fn) {
  try { fn(); passed++; console.log('PASS ' + label); }
  catch (e) { failed++; console.log('FAIL ' + label + '  -> ' + e.message); }
}
function eq(e, a) { if (e !== a) throw new Error('expected=' + JSON.stringify(e) + ' actual=' + JSON.stringify(a)); }

const storeFile = path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'store.html');
let kjCastLink = null;
check('store.html に kjCastLink がある', () => { kjCastLink = new Function(block(storeFile, 'cast-link') + '\nreturn kjCastLink;')(); eq('function', typeof kjCastLink); });
const C = (input) => { if (!kjCastLink) throw new Error('kjCastLink が無い'); return kjCastLink(input); };

check('動画のリンク: 追跡用の si / feature / pp を落とす', () => eq('https://www.youtube.com/watch?v=abc123&list=PLx&index=2', C('https://www.youtube.com/watch?v=abc123&list=PLx&index=2&si=zzz&feature=share&pp=yyy')));
check('短いリンク youtu.be を動画のリンクに直す（t は残す）', () => eq('https://www.youtube.com/watch?v=abc123&t=30', C('https://youtu.be/abc123?si=zz&t=30')));
check('短いリンク youtu.be（引数なし）', () => eq('https://www.youtube.com/watch?v=abc123', C('https://youtu.be/abc123')));
check('https:// を省いても受ける', () => eq('https://www.youtube.com/watch?v=abc', C('youtube.com/watch?v=abc')));
check('www なしは www 付きにそろえる', () => eq('https://www.youtube.com/watch?v=abc', C('https://youtube.com/watch?v=abc')));
check('http は https に直す・スマホ版ホストはそのまま', () => eq('https://m.youtube.com/watch?v=abc', C('http://m.youtube.com/watch?v=abc')));
check('YouTube Music はそのまま', () => eq('https://music.youtube.com/watch?v=abc&list=RDabc', C('https://music.youtube.com/watch?v=abc&list=RDabc')));
check('再生リストのページ', () => eq('https://www.youtube.com/playlist?list=PLx', C('https://www.youtube.com/playlist?list=PLx')));
check('ショート動画', () => eq('https://www.youtube.com/shorts/abc', C('https://www.youtube.com/shorts/abc')));
check('前後の空白・改行を取る', () => eq('https://www.youtube.com/watch?v=abc', C('  https://www.youtube.com/watch?v=abc \n')));
check('大文字のホストも受ける', () => eq('https://www.youtube.com/watch?v=abc', C('https://WWW.YouTube.com/watch?v=abc')));
check('# 以降は落とす', () => eq('https://www.youtube.com/watch?v=abc', C('https://www.youtube.com/watch?v=abc#t=1')));
check('空は不可', () => eq('', C('')));
check('null は不可', () => eq('', C(null)));
check('別サイトは不可', () => eq('', C('https://evil.example/watch?v=abc')));
check('@ で偽装したホストは不可', () => eq('', C('https://www.youtube.com@evil.example/watch?v=a')));
check('似た名前のホストは不可', () => eq('', C('https://www.youtube.com.evil.example/watch?v=a')));
check('引数に YouTube を含むだけの別サイトは不可', () => eq('', C('https://evil.example/?u=https://www.youtube.com/watch?v=a')));
check('javascript: は不可', () => eq('', C('javascript:alert(1)')));
check('ただの言葉は不可', () => eq('', C('ピアノ 音楽')));
check('ポート付きは不可', () => eq('', C('https://www.youtube.com:8443/watch?v=a')));
check('youtu.be で動画番号なしは不可', () => eq('', C('https://youtu.be/')));

check('store.html: #cast= を受け取ってアドレスから消す（送るのは「テレビに映す」を押したとき。自動では送らない＝画面で確認）', () => {
  const s = fs.readFileSync(storeFile, 'utf8');
  if (!/#cast=|cast=/.test(s) || !s.includes('replaceState')) throw new Error('#cast の受け取りか replaceState が無い');
  if (!s.includes("action: 'cast'")) throw new Error("action: 'cast' を送っていない");
});
check('store.html: YouTube を別のタブで開くリンク（noopener）', () => {
  const s = fs.readFileSync(storeFile, 'utf8');
  if (!/<a[^>]+href="https:\/\/www\.youtube\.com\/?"[^>]*target="_blank"[^>]*rel="noopener[^"]*"/.test(s) &&
      !/<a[^>]+target="_blank"[^>]*rel="noopener[^"]*"[^>]*href="https:\/\/www\.youtube\.com\/?"/.test(s)) throw new Error('リンクが無い');
});

// ===== 入口ページ（別リポジトリ sanmi-apps） =====
const musicFile = path.join(__dirname, '..', '..', 'sheet-app', 'sanmi-apps', 'music.html');
if (!fs.existsSync(musicFile)) {
  console.log('SKIP music.html が見つからないので入口ページのテストは飛ばします: ' + musicFile);
} else {
  let withCast = null;
  check('music.html に withCast がある', () => { withCast = new Function(block(musicFile, 'cast-pass') + '\nreturn withCast;')(); eq('function', typeof withCast); });
  const W = (t, s, h) => { if (!withCast) throw new Error('withCast が無い'); return withCast(t, s, h); };
  const T = 'http://192.168.1.7:8787/store';
  const link = encodeURIComponent('https://www.youtube.com/watch?v=abc&list=PLx');
  check('店番号を付けて渡す', () => eq(T + '?store=002', W(T, '002', '')));
  check('#cast= をそのまま渡す', () => eq(T + '?store=002#cast=' + link, W(T, '002', '#cast=' + link)));
  check('#cast= 以外の # は渡さない', () => eq(T + '?store=002', W(T, '002', '#foo=bar')));
  check('形のおかしい #cast= は渡さない（空白や < を含む）', () => eq(T + '?store=002', W(T, '002', '#cast=<script>')));
  check('長すぎる #cast= は渡さない', () => eq(T + '?store=002', W(T, '002', '#cast=' + 'a'.repeat(3001))));
  check('移動先が空なら空のまま', () => eq('', W('', '002', '#cast=' + link)));
  check('店番号が無ければ ? を付けない', () => eq(T + '#cast=' + link, W(T, '', '#cast=' + link)));
}

console.log(failed === 0 ? 'ALL PASS (' + passed + ')' : failed + ' FAILED / ' + passed + ' passed');
if (failed > 0) process.exit(1);
