源自FooIbar，用vibe coding最適化・公開（Issues歓迎: https://github.com/FooIbar/EhViewer ）

<p align="right">
  <a href="/README.md">
  简体中文
  </a>
  <span> | </span>
  <a href="/docs/README/en.md">
  English
  </a>
  <span> | </span>
  <a href="/docs/README/zh-tw.md">
  正體中文
  </a>
  <span> | </span>
  <strong>日本語</strong>
</p>

<h1 align="center">
  <img src="https://github.com/FooIbar/EhViewer-art/blob/master/launcher_icon-web.svg" width="200" alt="EhViewer">
  <br>EhViewer<br>
</h1>

<p align="center">
  <a href="https://github.com/ikura23/EhViewer/actions/workflows/build-release.yml">
    <img src="https://github.com/ikura23/EhViewer/actions/workflows/build-release.yml/badge.svg" alt="Github Actions">
  </a>
  <a href="/LICENSE">
    <img src="https://img.shields.io/github/license/ikura23/EhViewer" alt="LICENSE">
  </a>
  <a href="https://github.com/ikura23/EhViewer/releases">
    <img src="https://img.shields.io/github/v/release/ikura23/EhViewer" alt="Release">
  </a>
  <a href="https://github.com/ikura23/EhViewer/issues">
    <img src="https://img.shields.io/github/issues/ikura23/EhViewer" alt="Issues">
  </a>
</p>

<div align="center">
  <h3>
    <a href="#説明">
    説明
    </a>
    <span> | </span>
    <a href="#ダウンロード">
    ダウンロード
    </a>
    <span> | </span>
    <a href="#スクリーンショット">
    スクリーンショット
    </a>
    <span> | </span>
    <a href="#感謝">
    感謝
    </a>
    <span> | </span>
    <a href="#ライセンス">
    ライセンス
    </a>
  </h3>
</div>

# 説明

FooIbar/EhViewer をベースに最適化した Material Design 3（ダイナミックカラー）対応のモダン Android クライアントです。

- **実験的新版 UI**：5 タブ下部ナビゲーション、人気／ランキング／購読のスワイプ切替、おすすめタグウォール、グローバル Non-H モード。
- **WebP アニメーション＆画質強化**：Rust + AHB ゼロコピー倍速再生、アイケアフィルター、GPU リアルタイム画質強化（Android 13+）。
- **ローカルライブラリ管理**：ローカルスキャン、新旧重複ギャラリーの自動検出・統合、ランダム閲覧。

# ダウンロード

- **パッケージ名**：`com.ys.ehviewer`（独自署名のためオリジナル版と共存可能）
- **対応 OS**：Android 8.0+（GPU 画質強化は Android 13+）

| ABI | 備考 |
|-----|------|
| `arm64-v8a` | 推奨（多くの最新端末） |
| `armeabi-v7a` / `x86_64` / `universal` | 旧端末 / エミュレータ / ユニバーサル版 |

<a href="https://github.com/ikura23/EhViewer/releases">
<img alt="Get it on GitHub" src="https://github.com/FooIbar/EhViewer-art/blob/master/get-it-on-github.svg" width="200px"/>
</a>

# スクリーンショット

<p align="center">
  <img src="../assets/showcase-01-hot.png" width="23%" alt="人気・ランキング" />
  <img src="../assets/showcase-02-search.png" width="23%" alt="おすすめタグ検索" />
  <img src="../assets/showcase-03-downloads.png" width="23%" alt="ダウンロード管理" />
  <img src="../assets/showcase-04-me.png" width="23%" alt="マイページ" />
</p>

# 感謝

使用しているライブラリ

- [Arrow](https://arrow-kt.io/)
- [AOSP & AndroidX](https://source.android.com/)
- [Kotlin & KotlinX](https://kotlinlang.org/)
- [Material Icons](https://github.com/google/material-design-icons)
- [Ktor](https://ktor.io/)
- [Coil](https://coil-kt.github.io/coil/)
- [Compose Destinations](https://composedestinations.rafaelcosta.xyz/)
- [libarchive](https://www.libarchive.org/)

# ライセンス

    Copyright 2014-2019 Hippo Seven
    Copyright 2020-2022 NekoInverter
    Copyright 2022-2023 Tarsin Norbin
    Copyright 2023-2024 Foolbar

    EhViewer is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.

    EhViewer is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.

    You should have received a copy of the GNU General Public License along with EhViewer. If not, see <https://www.gnu.org/licenses/>.
