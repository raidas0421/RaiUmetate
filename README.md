# RaiUmetate 2.6.0

Paper 26.1.2 / Java 25用の日本語埋め立て妨害プラグインです。箱を満杯にして勝利カウントを完了すると中身を消去し、STEへWinを1件送ります。枠HPが0になるとLoseを1件送り、内容を落下させて枠を再生成します。

## 更新・導入

1. サーバーを停止し、既存JARと `plugins/RaiUmetate` をバックアップします。
2. `target/RaiUmetate.jar` を配置します。稼働中の更新予約にはBukkitの `plugins/update/RaiUmetate.jar` を使います。
3. RWinは内蔵化されました。旧 `RWin-1.1.0.jar` は停止中にplugins外へ保管してください。残っている場合は起動後に警告します。RaiMetalGravityは不要です。
4. サーバーを起動します。Javaクラスを変更するため、プラグインのreloadだけでは反映しません。

更新対象: `C:\妨害配信\09-28_ume\plugins`。本番STEへテスト送信は行っていません。

```text
/umetate create 11 9
/umetate fillblock 100
/umetate fillblockfoun 100
/umetate tnt 5 名前
/hp
/win status
```

`create` の直径は**外壁を含まない内側の一辺**です。直径11・高さ9で1,089ブロック。直径3～49の奇数、高さ3～30。初期素材はガラスで、`default-boundary-material` で変更できます。サイズ変更時は自動TPしません。新規作成と `tp` では移動します。

旧 `/bedrock`、`radiusup`、`radiusdown` は廃止しました。連携ツールの登録コマンドも `/umetate` と直径へ変更してください。`/rwin win 1` は `/win win 1` の互換エイリアスとして実行できますが、補完には表示しません。`raiumetate:` で始まる候補も非表示です。

上限補正の例:

```text
/umetate create 100 100   # 通常は直径49・高さ30で作成
/umetate diameter 100    # 幅をその場所の最大値へ
/umetate heightup 100    # 高さをその場所の最大値へ
```

## 枠のひびと崩壊（2.6.0）

- 枠HPが減るほど、枠のブロック（床と壁）にひびが入ります。掘るときと同じ見た目で、HPが少ないほど濃くなります。ブロックごとに入り始めが少しずつ違うので、均一ではなく自然に広がります。`frame-health.cracks: false` で消せます。
- 普通の重力は2.5.0のまま即着（一番下の空きマスへ一瞬で移動）です。砂のように落ちるのは崩壊のときだけです。
- 崩壊時は上限（旧 `frame-health.collapse.max-launch`、既定3000）をなくし、中身を全部、下の段から砂のように真下へ落とします。全部が着地してから `rebuild-delay-ticks` 後に枠を作り直します（着地しないものがあっても10秒で打ち切り）。古い config.yml の `max-launch` は無視されます。

## 重力の負荷対策（2.1.0）

砂と同じFallingBlockの落下挙動を維持し、支えのあるブロックではBlockStateを取得しないようにしました。`gravity.max-work-ms-per-tick`（既定2ms、0.5～10ms）の時間予算と既存の個数上限を併用し、走査・生成の残りを次回へ継続します。一つのサーバーAPI呼び出しを途中で中断する上限ではありません。

着地先が塞がった場合はキャッシュされた空き候補を最大32件確認し、箱全体を毎回調べる処理を廃止しました。候補が見つからなければ従来どおり追加待ちへ戻します（満杯なら追加なし）。通常の着地・層への材質変換・コンテナ保護は維持します。大量の崩落では落下開始が分散します。枠崩壊時の別の放出処理は今回の変更対象外です。

## 埋め立てと重力

- `fill` / `fillblock` は演出なしの直接配置。`performance.changes-per-tick` の予算内で連続配置します。
- `fillfoun` / `fillblockfoun` と `repair` は中央から飛び出す噴水です。既定4個/tick、飛行30tick。直接配置と同じ空きマス・予約を共有します。
- 氷結中は両方とも待機します。満杯に達した時点で残りの待機・飛行を打ち切ります。満杯時の追加を無視し、後で穴が開いても古い要求を再開しません。
- 浮いた固体は、その列の一番下の空きマスへ即座に移ります（2.5.0、上に積まれた列もまとめて詰めます。FallingBlockは出しません）。チェストなどは動かさず、TNTはTNTのまま移します。内部重力のみを使用し、他プラグインの重力リスナーを呼びません。
- 箱が満杯になると既定15からカウントダウンします。各数字20tick、**2だけ30tick**。初期設定なら15.5秒です。中央のタイトルと音で更新し、穴が開けば中断・再開します。
- ボスバーは枠HPのみ。例: `枠HP 8,500 / 10,000 (85%) | 直径11 高さ9 | カウント 12秒`。停止・妨害中は中断、未開始は待機中です。
- 常時アクションバー表示や通常操作の成功チャットはありません。ヘルプ・状態・HP変更結果・Win送信結果・入力エラーは表示します。

内側の最高非空気ブロックYを列ごとにキャッシュし、TNT系と爆発シャケはそのY+3から召喚します。空の場合は床Y+3。シールド中は岩盤のふたの上面Y+3です。ワールド上限内へ切り詰めます。TNTロケットは従来どおり足元、隕石・彗星・ブラックホール・エンダーマンの位置は変えません。

層素材は上から3段ずつ、ダイヤモンド・金・鉄・エメラルド・アメジスト・銅。その下は全てクォーツです。`layer` による指定が優先します。手置きは `autoreplace` の設定に従い、無効時はその層の素材のみを許可します。自動配置は常に層の素材を使います。

`shield <秒>` は消費回数方式ではなく実物の岩盤のふたです。効果中に再使用すると延長します。`reach` はブロック操作距離を0～64で指定し、設置・破壊・右クリックに適用します。0は通常距離です。

## Win送信とHP

管理コマンドは全て `raiumetate.admin`（既定OP）。コンソールから使用できます。作成・TPにはメインユーザーが必要です。

| コマンド | 動作 |
|---|---|
| `/win win [n]` / `/win add [n]` | STEへ+1をn件（既定1） |
| `/win lose [n]` / `/win remove [n]` | STEへ-1をn件 |
| `/win status` | 接続先、待ち件数、直近sent/requested、起動中の送信Win/Lose合計 |
| `/hp` | 現在HP / 最大HP |
| `/hp reset` | 全回復 |
| `/hp heal <n>` | HPを増減、負数可、0～最大HPへ制限 |
| `/hp limit set <n>` | 最大HPを設定 |
| `/hp limit add <n>` | 最大HPを増減、結果1未満は拒否 |

`/umetate hp ...` も同じ処理です。HPの数値は絶対値1～1,000,000,000。最大HPを上げても現在HPは回復せず、下げた時だけ超過分を切り詰めます。最大HPは `frame-health-state.yml` に保存し、`frame-health.max-health` は初期値として使用します。HP0で同じワールド全員に赤い `-1 Win` / `枠が壊れた！` を表示し、従来の崩壊・再生成へ進みます。

Win送信は `WinService` → `WinSink` の構成です。`win.sink: ste` はWebSocket、`none` は外部送信なし。`none` の送信済み合計は0のままです。STEが絶対Win数を持ち、プラグインのローカル勝利回数とは別管理です。`/win set` はありません。

既定接続先は `ws://127.0.0.1:50707`（STEのレガシーランチャーブリッジ）。認証付きモバイル用7704ではありません。1依頼ごとに接続し、`{"type":"win"}` または `{"type":"lose"}` をn回送り、切断します。RWinと同じ接続後300ms待機、burst後150ms待機を維持しています。

単一デーモンワーカーのFIFOキューで送信し、Bukkitメインスレッドを待たせません。既定は1依頼1,000件、待ち100依頼。`burst` は送信間隔なし、`paced` は既定350ms間隔。接続・送信タイムアウトは既定5秒です。部分失敗は未送信分だけ最大3回再試行（初回を含め最大4試行）。送信完了した分は再送しません。最終失敗は実行者とログへ通知し、ゲームは続行します。停止時は中止し、再起動後に未送信分を再開しません。

### Rai Live へ送る（win.sink: rlc）

`win.sink: rlc` にすると、Win/Lose を Rai Live の `POST {rai-live.url}/api/wins`（`{"delta":±n,"source":"RaiUmetate"}`）へ送ります。既定の `rai-live.url` は `http://127.0.0.1:4180`（VPSでは Rai Live のSSH逆方向転送）。あわせて `/api/signals` へ次の合図を送ります。送信は別スレッドで、失敗しても試合は止まりません（届かない間はログに1回だけ警告）。

| 合図 | いつ | data |
|---|---|---|
| `umetate.state` | `rai-live.state-interval-ticks`（既定5）ごとに見て、変わったときと5秒ごと | hp, maxHp, lastDamage, filled, capacity, fillPercent, diameter, height, countdown（勝利カウントの残り秒、無ければnull）, winSeconds（今の勝利カウントの秒数）, doubleTime{active, multiplier, secondsLeft} |
| `umetate.doubletime.start` / `.end` | 妨害倍率タイムの開始・終了 | multiplier, seconds / reason |
| `umetate.fill_complete` | 埋め立て成功でWinを送るとき | delta |
| `umetate.frame_broken` | 枠HP0でLoseを送るとき | delta |

ゲームの進み方（勝利カウント、HP0で-1Win、コマンド、既存の設定）は変わりません。`ste` と `none` もそのまま使えます。Rai Live の2倍タイム延長（5分ずつ）が上限に当たらないよう、`double-time.max-seconds` の既定を3600にしました（既存のconfig.ymlは自動では変わりません）。

他プラグイン向け: `UmetatePlugin.winService()` の `win/lose(int, Reason)` はメインスレッドから呼んでください。キュー受付時に `WinRequestedEvent`（delta、reason）を同スレッドで発火します。これは送信依頼イベントであり、STEでの受信確認ではありません。

## サイズと保存の移行

`default-radius` / `max-radius` は新しいキーが明示されていない場合だけ `2×半径−1` へ換算します。旧最大半径50（換算99）は新しい最大49、旧最大高さ40は30へ移行します。上限を超えたサイズ指定は、その時作れる最大値へ補正します。通常は直径49・高さ30。ワールド境界や天井（演出用12ブロック分を確保）で制約される場所では、それより小さい最大値へ補正します。`create`、`diameter`、`diameterup`、`heightup`、各max/min/resetに適用します。上限内の偶数直径や最小値未満は従来どおりエラーです。`max-volume` は廃止・無視します。

保存済み `arena.yml` のradius形式は維持します。新しい範囲を超える旧アリーナも地形復元のため読み込み、変更コマンドでは新しい制限を適用します。`sizereset` で既定サイズへ戻せます。**Phase 1からサイズ変更は時間分割**します。事前検査・チャンク準備、復元データの非同期チェックポイント保存、予算付き構造変更、完了状態の非同期保存の順で進みます。`changes-per-tick`・`reconcile-per-tick`・`max-work-ms-per-tick` を尊重し、完了まで箱の変更コマンドを受け付けません。プレイヤーは自動TPしません。

幅だけの変更では既存の内側ブロックを再配置せず、同じ素材の枠も不要な再設定を省きます。既にその場所の最大サイズなら変更処理自体を省きます。復元用データを保存できない場合は枠の変更を開始せず、チェスト等がある範囲は変更前に拒否します。大きな変更・未読み込みチャンク・低速ディスクでは完了までの時間が増えますが、I/O完了をメインスレッドで待ちません。クライアント画面への反映速度は通信と描画にも依存します。

全体の占有確認は `performance.reconcile-per-tick` で巡回し、ゲーム自身の配置・破壊・落下はその場で反映します。

OBSのHTMLとプロトコルは変更しません。既定では無効、ポート17967です。

## 埋め立てコマンド一覧

どのコマンドも、必要な引数の後ろに名前を付けられます（STEの `{nickname}` でギフターの名前を渡す用途）。名前は空白を含んでもよく、最大48文字です。

- 召喚する妨害（tnt・supertnt・randomtnt・tntring・tntrocket・zeustnt・meteor・comets・salmon・enderman）は、その名前を頭上に表示します。
- それ以外のコマンドは名前を受け付け、サーバーログに `操作名（名前）` として記録します。
- 省略できる数値（`create` の直径・高さ、`fill` の層数、`tnt`・`tntrocket` の個数、`salmon` の秒数・威力、`doubletime start` の秒・倍率）は先に数値として読まれます。名前が数字だけになる可能性があるときは、省略できる数値も書いてください（例: `/umetate tnt 1 {nickname}`、`/umetate salmon 3 5 3 {nickname}`）。

| コマンド | 内容 |
|---|---|
| `/umetate create [直径] [高さ]` | 現在位置に箱を作成（既定11・9） |
| `/umetate fill [層数]` | 全体／指定層数分を直接配置 |
| `/umetate fillblock <個数>` | 指定個数を直接配置 |
| `/umetate fillfoun [層数]` | 全体／指定層数分を噴水で追加 |
| `/umetate fillblockfoun <個数>` | 指定個数を噴水で追加 |
| `/umetate clear [1/2]` | 箱の中を空にする。既定はエンダードラゴンが箱の上を通過し、通った列から消える（`effects.clear-dragon`）。`1` は即時、`2` はドラゴン |
| `/umetate delete` | 箱を削除し作成前の地形に戻す |
| `/umetate stop` | 勝利タイマーの自動開始を停止 |
| `/umetate start` | 勝利タイマーを再開 |
| `/umetate timer <秒>` | 勝利カウントの秒数を変更（1～3600）。カウント中なら新しい秒数から数え直す |
| `/umetate timer add <秒>` | 勝利カウントの秒数を増減（負数可、結果は1～3600に切り詰め） |
| `/umetate timer reset` | 勝利カウントの秒数を config.yml の `win-seconds` に戻す |
| `/umetate tnt [個数] [名前]` | 指定数の名前付きTNTを落とす（1～100、省略時1） |
| `/umetate tntrocket [回数] [名前]` | 足元の連続TNTで打ち上げ（既定20回） |
| `/umetate randomtnt [名前]` | ランダムな強さのTNT |
| `/umetate supertnt <個数> <強さ> [名前]` | 強化TNT（強さ1～20） |
| `/umetate meteor [名前]` | 巨大隕石を落とす |
| `/umetate comets <秒> <間隔tick> [名前]` | 彗星の雨（20tick=1秒） |
| `/umetate zeustnt [名前]` | 雷神TNT |
| `/umetate tntring [名前]` | 外周のTNTが同時爆発 |
| `/umetate alltnt [名前]` | 箱の中身（固体・チェスト類以外）をすべてTNTブロックに置き換える。点火はしない。連鎖点火は `effects.max-chain-tnt`（既定100）で制限 |
| `/umetate blackhole <秒>` | 周囲のプレイヤーを引き寄せて爆発 |
| `/umetate enderman <体数> [名前]` | 15秒間ブロックを盗む |
| `/umetate glass_prison <秒>` | メインプレイヤーをガラスの牢獄に拘束 |
| `/umetate freeze <秒>` | 氷結：手置き・噴水を一時停止 |
| `/umetate repair <個数>` | 修復波：空きマスへ噴水で追加 |
| `/umetate shield <秒>` | 指定秒数、岩盤のふたで上からのTNTを防ぐ |
| `/umetate glass` | 外壁・床をガラスにする |
| `/umetate wood` | 外壁・床を木材にする |
| `/umetate rock` | 外壁・床を岩盤にする |
| `/umetate edit` | 外壁・床の手動編集を切替 |
| `/umetate autoreplace` | 設置ブロックを層の材質へ変換するか切替 |
| `/umetate fireworks` | 爆発時の花火演出を切替 |
| `/umetate toplock` | 箱より上への設置制限を切替 |
| `/umetate tp` | 箱の縁に移動 |
| `/umetate layer <層数> <素材>` | 下から指定層数の素材を変更 |
| `/umetate color <cancel / win> <色>` | 中断・勝利の表示色を変更 |
| `/umetate heightup <段数>` | 高さを増やす |
| `/umetate heightdown <段数>` | 高さを減らす |
| `/umetate diameter <直径>` | 直径を奇数で指定 |
| `/umetate diameterup <偶数>` | 直径を増やす |
| `/umetate diameterdown <偶数>` | 直径を減らす |
| `/umetate diametermax` | 直径を最大にする |
| `/umetate diametermin` | 直径を最小にする |
| `/umetate heightmax` | 高さを最大にする |
| `/umetate heightmin` | 高さを最小にする |
| `/umetate sizemax` | 直径・高さを最大にする |
| `/umetate sizemin` | 直径・高さを最小にする |
| `/umetate salmon <数> [秒数] [威力] [名前]` | 爆発シャケ（既定5秒・威力3） |
| `/umetate sizereset` | 枠を設定上の初期サイズに戻す（既定11・9） |
| `/umetate set_main_user <プレイヤー名>` | コンソールからの操作対象を設定 |
| `/umetate remove_main_user` | 操作対象を解除 |
| `/umetate status` | 埋め立て数・追加待ち・勝利数などを表示 |
| `/umetate reach <距離>` | 届く距離を0～64で指定（0は通常） |
| `/umetate hp [reset / heal <数> / limit <set / add> <数>]` | 枠HPを表示・変更 |

`timer` の変更は config.yml に書き込みません（サーバー再起動で `win-seconds` に戻ります）。箱を作り直しても変更した秒数のままです。

## ビルドと確認

```powershell
mvn -ntp "-Dmaven.repo.local=C:\Users\rai42\AppData\Local\Temp\codex-m2" -f umetate-plugin/pom.xml verify
```

成果物は `umetate-plugin/target/RaiUmetate.jar`。検証記録は `VERIFICATION.md`。実際のPaper上の映像、最大サイズの負荷、STE側の表示は実機確認が必要です。


## 妨害倍率タイム（2.1.0）

- `/umetate doubletime start`：既定30秒・2倍で開始。
- `/umetate doubletime start 60 3`：60秒・3倍。発動中は残り時間と倍率を置き換えます。
- `/umetate doubletime add 30` / `add -10`：残り時間を増減。
- `/umetate doubletime stop`：終了。`status`：状態表示。

管理者権限 `raiumetate.admin` が必要です。コンソールでも実行できます。既定の最大は600秒・10倍です。設定は `double-time` 以下で変更できます。

対象の妨害は同じ引数で追加発動します（既定5tick間隔）。威力や各効果の継続時間は変更しません。追加分には既存の同時実行上限を適用し、数指定のTNT・シャケ・エンダーマン等は空き分だけ追加します。彗星・ブラックホールは既存の同時1件制限により、発動中の追加を省略します。牢獄・氷結は既定で除外、支援の修復・シールドは常に対象外です。

専用の紫色ボスバーと開始・終了タイトルをアリーナのワールド全員へ表示します。文言・開始終了サウンドは設定可能です。枠の崩壊・再生成、サイズ変更、クリア、氷結中もタイマーは進みます。削除・停止・期限切れ・サーバー停止で終了し、再起動後には持ち越しません。片付け時は待機中の追加妨害を取り消します。期限切れだけの場合は、その直前に受け付けた追加分も実行します。

外部プラグインは `UmetatePlugin.doubleTimeService()` または Bukkit ServicesManager の `DoubleTimeManager` から取得できます。変更操作はメインスレッドで呼んでください。`DoubleTimeStartEvent` と `DoubleTimeEndEvent` が通知されます。HTTPから読む状態は不変の `snapshot()` として公開しています。既存OBSの `/state` に `doubleTime` を追加し、HTMLにも発動中の倍率と残り秒数を表示します。OBS無効時に新しいサーバーは起動しません。


## Phase 1: 保存・サイズ変更の最適化（2.1.1）

- `arena.yml` は状態のみ、復元ブロックは `arena-originals.dat`（gzip・パレット・連長）に分割します。
- 二つのファイルは世代IDで対応付けます。入れ替え途中の停止に備え、直前のバイナリを `arena-originals.dat.previous` に保持します。対応世代が見つからない場合は操作を停止します。
- 旧 `arena.yml` は読み込み後に移行し、`arena.yml.bak` を保存します。旧版へ戻す際は、必ず停止中にJARと対応する保存ファイル一式をバックアップから復元してください。古いJARでは新しい分割形式を読めません。
- ブロック文字列は復元データ取得時にキャッシュし、通常の状態保存では全セルを再取得しません。小さな状態保存中もゲームtickは継続します。
- 復元データが変わる構造変更は、チェックポイントの非同期保存成功後に開始します。保存失敗は `ioFailed` で停止します。
- 枠HP保存は `frame-health.save-interval-ticks: 40` が既定です。崩壊開始・復元完了・中断・停止時は直ちに非同期書き込みを要求し、停止時に書き込み完了を待ちます。通常のHP変更は、突然の強制終了時に最大で保存間隔分失われ得ます。
- 枠の素材判定は起動時にEnumSetへキャッシュします。ダメージ計算、Win/STE、OBSの既存項目と `doubleTime`、妨害倍率の動作は維持します。

Phase 2（Settingsとdouble-time設定のキャッシュ等）以降は含みません。変更前のSpark記録、実行コマンドとRCON往復時間は `work/phase1-baseline` に保存しています。RCON往復時間をMSPTとして扱わないでください。
