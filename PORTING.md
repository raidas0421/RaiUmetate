# RaiBR 枠HPシステムの移植記録

移植元は同じワークスペースの `raibr/`（README上のバージョン1.2.1）。移植元ファイルは変更していません。

| 移植元 | 埋め立て側 |
|---|---|
| `com/rai/raibr/BlastDamage.java` | 同じ数式、定数3.625、切り捨て、範囲外／露出0の処理を使用 |
| `com/rai/raibr/FrameHealth.java` | 実枠候補、64点サンプル、最大ダメージ1件、PrimeEventの記録、HP0・落下・再生成・保存を移植 |
| `com/rai/raibr/ArenaBounds.java` | 境界・内側の座標定義をそのまま使用 |
| `com/rai/raibr/FrameHealthSnapshot.java` | 同じ状態形式を使用 |
| `com/rai/raibr/FrameOverlayServer.java` / `frame-overlay.html` | OBS表示も同梱。既定は無効、ポート17967 |
| `tests/com/rai/raibr/BlastDamageTest.java` | 元の3テストを移植 |

## 接続のための変更

- パッケージ名を `jp.rai42.umetate` に変更。
- `BedrockMonitor` の代わりに `FrameArena` を使い、他プラグインの設定を推測せず現在の箱から同じ境界を渡す。
- 独自爆発を同じダメージ計算へ接続。通常TNTのHP計算威力は4。爆心を固体の内部に置かないよう着弾面の直上を使う。
- 岩盤のふたで防いだ爆発を除外。ふたは枠HPの対象面には含めない。
- 崩壊時の処理停止と復元後の埋め立て率再計算を `UmetateGame` へ接続。崩壊由来の落下ブロックにはタグを付け、既存の範囲保護で着地を妨げない。
- HP操作のコマンド名・権限・メッセージを埋め立て側へ変更。新規作成・削除時にアリーナの関連付けを初期化。
- 再起動直後にも枠を復元できるよう初回refreshを起動時に実行。
- ガラス破壊音は同じ `minecraft:block.glass.break` を文字列キーで呼び出す。
- HPと復元データは新しいプラグインのデータディレクトリに保存。

`WinService.lose(1, FRAME_BROKEN)` の1回依頼、再生成待機100tick、放出200マス/tick、落下ドロップ、コンテナ除外、元材質の復元、HP全回復は移植元の動作を維持しています。v2.0.0では `RaiMetalGravity` のリフレクション通知を廃止し、内蔵の重力を使用します。変更した埋め立てJARの実Paper上での挙動は、移植元の過去の実機検証結果とは別に確認が必要です。


## v2.0.0 Win送信の統合

`C:\妨害配信\07-17_umetate\plugins\RWin-1.1.0.jar` を静的に解析し、`SteTransport` と `WinRequest` を移植しました。解析中に元JARの通信処理は実行していません。`SteWinSink` はURI検証、接続後300ms待機、各テキストの送信完了件数、burst後150ms待機、close/abort、停止時中断を引き継ぎます。

新設 `WinService` は単一の上限付きFIFO、未送信分だけの再試行、メインスレッドの依頼イベントと結果通知を担当します。Win/Loseの絶対値は保持せず、今回起動中の送信件数のみを状態表示します。`NoopWinSink` は外部に何も送らず、送信済み件数0を返します。

HP最大値は状態ファイルへ保存するよう変更。HP計算式、64点露出、崩壊内容の落下・再生成は維持し、表示と管理コマンドを拡張しています。
