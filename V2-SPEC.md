# RaiUmetate v2.0.0 改修設計書(改訂版)

## 0. 前提
- 対象: Paper用プラグイン RaiUmetate(Maven、パッケージ `jp.rai42.umetate`、現行 v1.7.0)。
- 既存ソースの実装を正とする。以下のクラス名は配布JARから確認したもの(`UmetateCommand` `UmetateGame` `FrameHealth` `WinClock` `FillState` `BedrockLid` `TntRocket` `Box` `BlockReach` `FrameOverlayServer` など)。実在しない・名前が違う場合は近いものに読み替える。
- 既存の挙動は、この設計書で変更を明示した箇所以外は維持する。
- 現行は `rwin win 1` / `rwin lose 1` をコンソールコマンドとして dispatch し、別プラグイン RWin(v1.1.0)に任せている。v2.0.0 ではこの方式をやめ、RWin の機能を RaiUmetate 内部に取り込む(§2.2)。移植元の RWin のJAR(`RWin-1_1_0.jar`)を参照できる場合は、そのクラス `RWinPlugin` `SteTransport` `WinRequest` を正とする。
- 将来、配信管理用の独自ツールを作る可能性がある。そのため Win の送信は「内部サービス + 差し替え可能な送信先(Sink)」の構造にする。

## 1. 変更一覧
| # | 内容 |
|---|------|
| 1 | RaiMetalGravity への依存・連携コードを削除 |
| 2 | RWin の機能を統合(STEへのWin/Lose送信、コマンドでの増減) |
| 3 | `create` 時の外壁・床の初期素材を岩盤からガラスへ |
| 4 | 勝利カウントの表示 2→1 の間だけ 1.5 秒 |
| 5 | `fill` / `fillblock` を即時配置に変更 |
| 6 | 噴水演出つきの `fillfoun` / `fillblockfoun` を新設 |
| 7 | TNT系と爆発シャケの召喚位置を変更(shieldがあればその上、なければブロックの上) |
| 8 | サイズ表記を半径から直径に統一。最小・最大の追加と体積上限の撤廃 |
| 9 | コマンドの名前空間表示を隠し、`/umetate` `/win` `/hp` を使えるようにする |
| 10 | 枠HPのボスバー表示内容の変更 |
| 11 | `/hp heal` `/hp limit set` `/hp limit add` の追加 |
| 12 | 枠破壊時に title「-1 Win」/ subtitle「枠が壊れた！」を表示 |

## 2. 詳細仕様

### 2.1 RaiMetalGravity 依存の削除
- `plugin.yml` の `softdepend` から `RaiMetalGravity` と `RWin` を削除する(`softdepend` 自体を消してよい)。
- `FrameHealth` にある、他プラグインのリスナーをリフレクションで探して直接通知する処理(`gravityListener` / `notifyGravity` 周辺)と、関連する警告メッセージを削除する。
- 枠崩壊時に内容ブロックを落下ブロックとして解放する処理(`release-blocks-per-tick`)は残す。通常の物理更新と本プラグイン内蔵の重力処理(`GravityFall` など)だけで成立させる。

### 2.2 Win機能の統合(STEへの送信)
RWin v1.1.0 の機能を RaiUmetate に取り込む。RWin の動作は次のとおり。これを再現する。

**RWin の現行動作(移植元)**
- STE のスマートバーの Win 数を増減するだけで、Win 数そのものは持たない。STE へは「+1」「-1」のイベントを送る。
- 通信は WebSocket(`java.net.http.WebSocket`)。接続先は `websocket-url`(既定 `ws://127.0.0.1:50707`、STEのレガシーランチャーブリッジ)。認証つきのモバイル用ポート 7704 ではない。`ws://` または `wss://` のみ許可。
- 送信メッセージは WebSocket のテキストで、`{"type":"win"}`(+1)または `{"type":"lose"}`(-1)。これを回数ぶん1件ずつ送る(Win 5 なら5メッセージ)。
- コマンドごとに接続し、全メッセージを送ったら閉じる。結果は `completed` / `sent`(送れた件数) / `requested`(依頼件数) / `error` で返る。
- 送信は専用のスレッド(`RWin-STE-Sender`、デーモン)で行い、メインスレッドでは待たない。結果は `BukkitScheduler.runTask` でメインスレッドに戻して、コマンド実行者へ表示する。
- 待ち行列には上限がある(`max-queued-commands`、既定100)。超えたら「送信待ちが上限です」で受け付けない。1コマンドの数は `max-count-per-command`(既定1000)まで。
- 送信モードは `burst`(間隔なし、既定)と `paced`(`send-interval-ms`、既定350ms の間隔)。`connect-timeout-ms` は既定5000。
- サーバー停止時は送信を中止する。
- コマンドは `/rwin <win|lose> [数字]`(数字省略時は1)。タブ補完は `win` / `lose`、その後に `1` `5` `10`。

**RaiUmetate 側の構成**
- `WinService`: Win/Lose の依頼を受け取り、`WinSink` に渡す。
  - API: `win(int n, Reason reason)`, `lose(int n, Reason reason)`。
  - `Reason`: `FILL_COMPLETE`(埋め立て成功) / `FRAME_BROKEN`(枠破壊) / `COMMAND`(手動)。
  - 依頼は1本の送信キュー(単一ワーカースレッド)に積み、順序を保つ。メインスレッドをブロックしない。
- `WinSink`(インターフェース): `Result send(WinRequest request)` の形にする。
  - `SteWinSink`: 上記の RWin の動作(`SteTransport` 相当)を移植したもの。
  - `NoopWinSink`: 何も送らない。テスト用、またはSTEを使わない運用向け。
  - `config.yml` の `win.sink: ste | none` で選ぶ。
- Bukkit カスタムイベント `WinRequestedEvent`(delta、reason)をメインスレッドで発火する。将来の独自ツール連携で、他プラグインからも利用できるようにするため。
- 将来の独自ツール向け Sink(Webhook、別の WebSocket など)は今回は実装しない。`WinSink` を差し替えるだけで追加できる構造にとどめる。

**Win数の扱い**
- STE が Win 数を保持する。RaiUmetate は Win 数の絶対値を持たず、同期もしない。そのため `/win set` は作らない。
- RaiUmetate 内の既存の勝利回数(`wins`、`status` の「勝利 n 回」)は従来どおり別管理とする。STE の Win 数とは連動しない。
- `/win status` には、接続先URL、送信待ち件数、直近の送信結果(sent/requested)、今回の起動中に送った Win/Lose の合計件数を表示する。

**送信失敗時**
- RWin は再試行をしない。RaiUmetate では、`Result` の `requested - sent` に当たる未送信分だけを、最大 `win.retry.max-attempts` 回(既定3)まで再試行する。すでに送れた分は再送しない(Win/Lose は冪等ではないため)。
- 再試行でも失敗したら、コンソールに警告を出し、コマンド実行者にも送信件数と未送信件数を通知する。ゲーム進行は止めない。
- 再起動をまたぐ未送信キューの永続化はしない(後から意図しない Win 増減が起きるのを防ぐため)。

**Win/Lose が発生するタイミング**
- 埋め立て成功(勝利カウント完了)時: `win(1, FILL_COMPLETE)`。従来の `rwin win 1` 相当。
- 枠破壊時: `lose(1, FRAME_BROKEN)`。従来の `rwin lose 1` 相当。
- `/win` コマンドによる手動増減。

**コマンド `/win`**(権限 `raiumetate.admin`、コンソール可)
- `/win <win|lose> [n]`: n 件(省略時1、範囲 1〜`win.ste.max-count-per-command`)を送る。
- 同義語として `add`(=win)、`remove`(=lose)も受け付ける。
- `/win status`: 上記の状態表示。
- 互換のため `win` コマンドにはエイリアス `rwin` を持たせる(他のプラグインやツールが `rwin win 1` のようにコマンドを dispatch していても動くように)。`rwin` は §2.3 の方法で補完候補からは隠す。
- 不正な引数は RWin と同様に使い方を表示してエラーにする。タブ補完は `win` / `lose` / `status`、その後に `1` `5` `10`。
- RWin プラグインが同じサーバーに残っていると二重送信になり、STE のブリッジ接続も競合し得る。起動時に RWin プラグインを検出した場合は「RWinは不要です。二重送信防止のため削除してください」と警告ログを出す。

### 2.3 コマンド体系と名前空間の非表示
- `plugin.yml` のコマンドは `umetate`、`win`(エイリアス `rwin`)、`hp` の3つ。
- 旧エイリアス `bedrock` は廃止する。ヘルプ文・使い方メッセージ内の `/bedrock` 表記はすべて `/umetate` に置き換える。
- Paper/Bukkit は plugin.yml のコマンドを必ず `raiumetate:umetate` のような名前空間つきでも登録し、これは plugin.yml の設定では無効化できない。そこで次の方法で「候補に出さない」ことを実現する。
  1. `PlayerCommandSendEvent`: プレイヤーに送るコマンドリストから、`raiumetate:` で始まるエントリと `rwin` を除去する。
  2. `AsyncTabCompleteEvent`(Paper): 補完候補から同じものを除去する。
  3. 名前空間つきコマンドの実行そのものはブロックしなくてよい(候補に出なければ目的達成)。
- 結果として、タブ補完に出るのは `/umetate` `/win` `/hp` だけにする。

### 2.4 create の初期素材
- `/umetate create` 実行時の外壁・床の素材を `GLASS` にする。
- config に `default-boundary-material: GLASS` を追加し、これを読む。
- 枠破壊後の再生成も、その時点で選択されている素材(`glass` / `wood` / `rock` の切替結果)で行う。
- `glass` / `wood` / `rock` コマンドは従来どおり残す。

### 2.5 勝利カウントの 1.5 秒
- 勝利カウントは `win-seconds` 秒からカウントダウンし、各数字を通常 20tick(1秒)ずつ表示する。
- 表示が「2」から「1」に進む区間だけ 30tick(1.5秒)にする(つまり「2」を30tick表示してから「1」へ進む)。
- 結果として総カウント時間は `win-seconds` + 0.5秒になる(`win-seconds` ≥ 2 のとき)。`win-seconds` が1以下のときは変更なし。
- 妨害による中断・再開時の動作は従来どおり。
- `WinClock` の tick 判定(`willWinNextTick` 周辺)を修正する。ボスバー・タイトル・アクションバーの数字表示とも整合させる。

### 2.6 fill 系コマンド
**fill / fillblock(即時配置に変更)**
- `/umetate fill [層数]` と `/umetate fillblock <個数>`: 対象セルの選び方・換算(層数→セル、個数→セル)は既存の `FillState` のロジックをそのまま使う。変えるのは「噴水で飛ばして落とす」配信方法だけ。
- 対象セルに直接ブロックを配置する(噴水・飛行演出なし)。配置するブロックの素材は、従来の層パレットと `autoreplace` の仕様に従う。
- 大量配置でサーバーが固まらないよう、`performance.changes-per-tick` の上限内で tick 分割する。ただし演出なしで連続して処理する(他の演出待ちはしない)。
- 配置完了後に既存の「埋め立て完了」判定(勝利カウント開始判定)を実行する。重力の影響を受けるブロックが宙に浮かないよう、配置後に既存の落下処理で整える。
- 氷結(`freeze`)中の扱いは、従来の fill と同じ(追加待ち扱い)にする。

**fillfoun / fillblockfoun(新設)**
- `/umetate fillfoun [層数]` と `/umetate fillblockfoun <個数>`: 現行の fill / fillblock と同じ動作(噴水でばらまく演出あり)を提供する。
- 換算方法は fill / fillblock と同一(同じ `FillState` を使う)。違いは噴水演出の有無だけ。
- `fountain.*` の設定(`blocks-per-tick`, `flight-ticks`, `arc-height`, `max-displays`)は従来どおり効かせる。
- 修復波 `repair <個数>` は今回は変更しない(従来どおり噴水)。
- ヘルプ文・タブ補完に4コマンドとも追加する。

### 2.7 TNT・爆発シャケの召喚位置
- 対象: 通常TNT(`tnt`)、`randomtnt`、`supertnt`、`zeustnt`、`tntring` などTNTを召喚する効果と、爆発シャケ(`salmon`)。
- 召喚Y座標: `基準Y + 3`。基準Yは次のとおり。
  - 岩盤のふた(`shield`)が発動中: ふたの最上面のY。つまりふたの上に出す。
  - ふたが無い場合: 埋め立てられているブロックの最大高さ。箱の内側にある非空気ブロックのうち最も高いブロックのY。箱が空の場合は内側の床面のYを使う。つまりブロックの上に出す。
- X/Z の決め方は従来どおり。ワールドの最大高さを超える場合はそこで切り詰める。
- 最大高さは毎回全スキャンしない。列ごとの最高Yキャッシュを保持し、ブロックの設置・破壊・落下・fill・爆発で更新する。
- 対象外(従来どおり): `tntrocket`(足元から)、`meteor`、`comets`、`blackhole`、`enderman`。

### 2.8 サイズ表記の直径化、上限・下限、体積上限の撤廃
**用語**
- 「直径」= 箱の内側の一辺のブロック数(外壁は含まない)。中心ブロックを基準に作るため直径は奇数のみ(従来の `内側の一辺 = 半径 × 2 - 1` と同じ)。初期サイズの直径11は従来の半径6に相当する。
- プレイヤー・管理者に見える表記(コマンド、メッセージ、ヘルプ、ボスバー、`status`)はすべて「直径」にする。内部で半径を持つのは自由。

**値の範囲**
- 直径: 最小3、最大49、初期11。
- 高さ: 最小3、最大30、初期9(最小3は仮の値)。
- `max-volume`(体積上限)は廃止する。設定・判定コードとも削除。旧キーが残っていても無視する。
- 範囲外の指定は、切り詰めずにエラーメッセージを返す(例: 「直径は3〜49の奇数で指定してください」「高さの最大は30です」)。
- 直径に偶数を指定した場合もエラーにする。

**コマンド**
- `/umetate create [直径] [高さ]`: 省略時は既定の11・9。
- `/umetate diameter <直径>`: 直径を指定値にする(奇数、3〜49)。
- `/umetate diameterup <数>` / `diameterdown <数>`: 直径を指定ブロック数だけ増減する。直径は奇数を保つ必要があるため、`<数>` は偶数のみ受け付ける。
- `/umetate heightup <段数>` / `heightdown <段数>`: 従来どおり。
- `/umetate diametermax` / `diametermin`: 直径を最大(49)/最小(3)にする。
- `/umetate heightmax` / `heightmin`: 高さを最大(30)/最小(3)にする。
- `/umetate sizemax` / `sizemin`: 直径と高さを同時に最大/最小にする。
- `/umetate sizereset`: 従来どおり初期サイズ(11・9)に戻す。
- 旧 `radiusup` / `radiusdown` は廃止する。
- サイズ変更後の挙動(追加待ち・発動中効果の解除、「箱のサイズを変更しました。内側 a×b×c」のメッセージ)は従来どおり。
- 作成・サイズ変更・復元(`delete`)・`fill` は tick 分割で処理し、メインスレッドを長く占有しない(`performance.changes-per-tick` / `reconcile-per-tick` を使う)。最大サイズは内側 49×49×30 で、作成前地形の保存(`arena.yml`)の形式は現行のままでよい。
- データ付きブロック(チェスト等)の検出、ワールド境界チェック、ワールド高さのチェックは従来どおり行う。

### 2.9 枠HPのボスバー
- 既存の `FrameHealth` のボスバーのタイトル表示を以下に変更する。
  - 表示項目: 枠の現在HP / 最大HP(パーセンテージ)、箱のサイズ(直径・高さ)、現在のカウント秒数。
  - 表示例: `枠HP 8,500 / 10,000 (85%) | 直径11 高さ9 | カウント 12秒`
- パーセンテージは小数切り捨ての整数%。ボスバーの進捗は現在HP/最大HPとする。色は従来の仕様に従う。
- サイズ表記の最大・最小:
  - 直径が最大のとき `直径49(最大)`、最小のとき `直径3(最小)` のように、数値の隣に付ける。
  - 高さも同様に `高さ30(最大)` / `高さ3(最小)` と表示する。
- カウント秒数: 勝利カウント中は表示中の整数秒(§2.5 の表示値)。カウントしていないときは `待機中`、中断中は `中断` と表示する。
- 枠再生成中は従来どおりの再生成中表示(「枠崩壊 — 再生成中」)を優先する。
- 更新タイミング: HP変化、サイズ変化、カウントの数字変化、カウント状態変化のたび。
- OBSオーバーレイ(`frame-overlay.html` / `FrameOverlayServer`)は今回は変更しない。

### 2.10 /hp コマンド(枠HPの操作)
権限は `raiumetate.admin`、コンソール可。トップレベルの `/hp` と `/umetate hp ...` の両方を残し、同じ処理を呼ぶ。

- `/hp`: 現在HP/最大HPを表示する(従来の `hp` と同じ)。
- `/hp reset`: 現在HPを最大HPまで全回復する(従来の `hp reset` を維持)。
- `/hp heal <n>`: 現在HPを n 増減する。n は整数で、負数なら減算する。
  - 結果は 0〜最大HP の範囲に切り詰める。
  - 結果が 0 になった場合は、通常のダメージで枠が壊れたときと同じ枠崩壊処理(§2.11)を実行する。
- `/hp limit set <n>`: 最大HPを n にする(n ≥ 1)。
- `/hp limit add <n>`: 最大HPを n 増減する(負数可)。結果が 1 未満になる指定は拒否する。
- limit を変更したとき、現在HPは変えない。ただし現在HPが新しい最大HPを超える場合は最大HPまで切り詰める。
- 最大HPの変更は永続化する(既存の `frame-health-state.yml`)。`config.yml` の `frame-health.max-health` は、状態ファイルが無い場合の初期値として使う。
- 数値は 1 〜 1,000,000,000 の範囲を受け付ける。不正値は使い方を表示する。
- 成功時は変更後の `現在HP / 最大HP` をコマンド実行者へ返す。ボスバー・OBSオーバーレイの表示も即時更新する。
- タブ補完: `heal`, `reset`, `limit` → `set` / `add`。

### 2.11 枠破壊時の title
- 枠HPが0になって枠が崩壊した時点で、アリーナのワールドにいる全プレイヤーに title を表示する。
  - title: `-1 Win`(赤系)
  - subtitle: `枠が壊れた！`
  - 時間: フェードイン 5tick / 表示 40tick / フェードアウト 10tick
- 同時に `WinService.lose(1, FRAME_BROKEN)` を呼ぶ(§2.2)。
- 従来の「枠崩壊 — 再生成中」のメッセージ表示、内容ブロックの解放、再生成は維持する。
- 再生成中に再度HP0扱いにならないよう、既存の二重発火防止を維持する。

## 3. config.yml の変更
```yaml
# サイズは直径(内側の一辺のブロック数、奇数のみ)。外壁は含まない。
default-diameter: 11     # 旧 default-radius: 6 に相当
min-diameter: 3
max-diameter: 49         # 旧 max-radius: 50
default-height: 9
min-height: 3
max-height: 30           # 旧 40
default-boundary-material: GLASS   # 新規: create時の外壁・床の初期素材
# 削除: default-radius, max-radius, max-volume

win:
  sink: ste              # ste | none
  retry:
    max-attempts: 3
  ste:
    websocket-url: 'ws://127.0.0.1:50707'   # STEと同じPC。別PCなら到達できるLANアドレス。認証つきの7704ではない
    send-mode: burst                         # burst | paced
    send-interval-ms: 350                    # pacedのときのみ
    connect-timeout-ms: 5000
    max-count-per-command: 1000
    max-queued-commands: 100
```
- 旧 config に `default-radius` / `max-radius` だけが残っている場合は、`直径 = 半径 × 2 - 1` で換算して読み込む(新キーがあればそちらを優先)。未設定の新キーはコード側の既定値を使う。
- 同梱の `config.yml` は上記に更新する。

## 4. plugin.yml
- `version: '2.0.0'`
- `softdepend` を削除。
- `commands`: `umetate`(エイリアス `bedrock` は削除)、`win`(エイリアス `rwin`)、`hp`。いずれも `permission: raiumetate.admin`。
- `permissions` は既存の `raiumetate.admin`(default: op)のまま。

## 5. テスト(JUnit5 + Mockito、既存の test スコープを使用)
- `WinClock`: 2→1 の区間のみ30tickになること、中断・再開、`win-seconds` が1以下のときの挙動。
- サイズ: 直径の奇数・範囲チェック、`diameterup/down` の偶数チェック、最大・最小コマンドの結果、`(最大)` `(最小)` 表示の判定、旧 `default-radius` からの換算。
- `/hp`: `heal` の正負と上下限の切り詰め、`limit set/add` の現在HPの切り詰め、不正値の拒否、HP0での崩壊発火。
- ボスバー文字列: 通常、最大、最小、再生成中、カウント待機/中断の各状態。
- TNT・シャケの召喚Y: 空の箱、一部が埋まっている箱、満杯、`shield` 発動中(ふたの上)、ワールド上限での切り詰め。
- `WinService` / `SteWinSink`: `{"type":"win"}` と `{"type":"lose"}` が回数ぶん送られること、`burst` と `paced` の違い、キュー上限での拒否、`sent < requested` のときに未送信分だけ再試行すること、コマンドの引数検証、`WinRequestedEvent` の発火。WebSocket は偽のエンドポイントで検証する。
- コマンド名前空間: `PlayerCommandSendEvent` / `AsyncTabCompleteEvent` で `raiumetate:` 系と `rwin` が除去されること。

## 6. 実装順序(推奨)
1. RaiMetalGravity連携の削除、plugin.yml・コマンド登録の整理(§2.1, 2.3, 4)
2. `WinService` / `WinSink` / `/win`(§2.2)と、既存の `rwin` dispatch の置き換え
3. 初期素材・カウント・直径化とサイズ上限下限・体積上限撤廃(§2.4, 2.5, 2.8)
4. fill系(§2.6)と TNT・シャケの召喚位置(§2.7)
5. ボスバー、`/hp`、枠破壊 title(§2.9–2.11)
6. テスト整備と全体の動作確認