# 掲示物テレビ（keiji-tv-kiosk）

サンミの薬局のテレビ（Google TV）で、掲示物を全画面で流し続けるためのアプリです。

[Screenlite Web Kiosk](https://github.com/screenlite/web-kiosk)（MIT ライセンス・Copyright (c) 2025 514sid）を元に、次の点だけ変えています。

- テレビのリモコンのボタンを Web ページへ渡す（元のアプリはテレビでは入力を全部ふさいでいた）
- 戻るボタンは押してもページを閉じない。**戻るボタンを3秒押し続けると設定画面**
- 初めに開くページ: `https://sanmi-dev.github.io/sanmi-apps/tv`（店舗を選ぶ入口）
- 何もしなくても画面を暗くしない（Idle Brightness の初期値 100%）
- アプリ名「掲示物テレビ」・識別名 `jp.sanmi.keijitv`（元の Screenlite と同時に入れられる）

## 入れ方（テレビ）

Downloader アプリのアドレス欄に次を入れてダウンロード → インストール。

```
https://github.com/sanmi-dev/keiji-tv-kiosk/releases/latest/download/keiji-tv.apk
```

## 組み立て

`main` に push すると GitHub Actions が組み立て、Release「latest」の `keiji-tv.apk` を差し替えます。署名の鍵はリポジトリの Secrets（KEYSTORE_BASE64 / KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD）にあります。

## ライセンス

MIT（LICENSE.md）。元の著作権表示を残しています。

## 音楽統合版 1.2.0（2026-11-15・versionCode 1005）

テレビ自身が店内LANで音楽の操作画面を配信する構成。公開APKに秘密は入っていない（同じAPKを全店で使える）。

- 店のPCは固定URL `https://sanmi-dev.github.io/sanmi-apps/music.html` を開く → 本部GAS（keiji-app の `?mode=tvaddr`）でテレビの現在の店内IPを引く → `http://<テレビのIP>:8787/store` へ自動で移る。見つからないときは「テレビが見つかりません」と10秒ごとの再試行。
- テレビ内サーバー: 起動時に 8787 番（使用中なら 8788〜8790）で待受。Host が店内アドレス（私設IPv4・127.0.0.1）以外は403。CORS は許可しない。
- 初回接続: PCの操作画面を開くとテレビ右下に「音楽の接続番号 1234」が2分出る → PCで入力 → 通行証（32文字）をPCのブラウザに保存。番号は10分有効・1回限り、5回間違えると5分ロック。通行証はテレビに最大10件。
- PCの操作画面: テレビ内の音楽画面の画像（約1秒ごと）、クリック・ホイール・文字送信・Enter等のキー、戻る/進む/再読込/停止、URL/検索欄、プリセット「YouTube（テスト曲）」「MP3（予備）」。
- MP3（予備）: `assets/mp3/` の自作曲3つ（`tools/make_mp3.py` で生成・第三者音源なし）を `http://127.0.0.1:8787/mp3` で連続再生。
- 本部GASへの登録: 起動30秒後と5分ごと。店舗IDは掲示の tv.html（`?store=` か localStorage `keijiTvPick`）から読む。端末ID（20文字）と鍵（32バイト）はテレビ内で初回に作って保存（端末バックアップは無効）。鍵は初回登録時だけ送り、以後は HMAC-SHA256 署名のみ。先に登録した1台だけが受け付けられ、テレビを入れ替えたら本部画面（サイネージ設定）の「テレビの音楽接続をリセット」で消す。テレビの時計が10分以上ずれていると登録できない。
- 登録・サーバーの失敗は掲示に影響させない。
- JVMテスト: `javac -encoding UTF-8 -d <出力先> app/src/main/java/org/screenlite/webkiosk/data/StartupConfig.java app/src/main/java/org/screenlite/webkiosk/music/*.java tests/MusicServerTest.java` → `java -cp <出力先> MusicServerTest`。
- `KEYSTORE_FILE` で既存署名鍵のパスを指定可能。署名鍵・パスワードはリポジトリへ保存しない。
