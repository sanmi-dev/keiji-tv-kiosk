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
