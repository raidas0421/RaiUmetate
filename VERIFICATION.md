## 2.2.2 `timer` changes take effect at once (2026-10-12, QA only)

- `/umetate timer <秒>` (1–3600): applies immediately; a running win countdown restarts from the new length.
- `/umetate timer add <秒>`: adds or subtracts (negative allowed), result clamped to 1–3600. `/umetate timer reset`: back to config.yml `win-seconds`. Changes are not written to config.yml (restart returns to `win-seconds`); recreating the box keeps the changed length. Both accept a trailing name like other commands.
- `umetate.state` (RaiLive) gains `winSeconds` (current win-countdown length).
- Code: UmetateGame (`timer`/`timerAdd`/`timerReset`), UmetateCommand, WinClock, RaiLiveLink, README. These were already in commit 6640f80; this release only bumps the version (pom.xml, plugin.yml) and records it.
- Tests: GameIntegrationTest, NameArgumentTest, RaiLiveLinkTest cover timer/add/reset and `winSeconds`. 172 tests pass (work/v222-verify.log). SHA256 EEB252AFD9C1E7F58AF5F9657B6F52980EEEEC71F7A05ECB83F5B453A15EF6C4. Not deployed to production.

## 2.2.1 `alltnt` command (2026-10-12, QA only)

- `/umetate alltnt [名前]`: every solid, non-container content block becomes a plain TNT block; nothing is ignited, occupancy is unchanged (TNT is solid), no double-time copies. Runs as a box-wide edit (few ticks, ≤20 ms each).
- Frame damage comes only when such TNT later explodes, through the existing vanilla TNTPrimed handling.
- Chain safety: `TNTPrimeEvent` inside the box is cancelled once `effects.max-chain-tnt` (default 100, 1–1000) primes happened within 80 ticks. Verified on Paper (work/alltnt1.log, 49×21 full box → alltnt → one vanilla explosion on top): peak 103 TNTPrimed, heaviest tick 54.8 ms (first run) / 12.0 ms (second), only ~170 blocks removed — cancelled blocks stay as unlit TNT, the chain stops. Frame HP −3 (explosion far from the frame).
- Tests: AllTntTest (solids only, containers/air untouched, fill count unchanged; cap and window; command name, no double time). 169 tests pass (work/v221-verify.log). SHA256 542C0D5CBAFE691C9F5C016C005A082089C284FF092913EA9947778A5495094A. Not yet staged.

## 2.2.0 Gifter names on every command (2026-10-08, QA only)

- Request: every command must accept a trailing name (STE `{nickname}`), and summoned effects must show it.
- `UmetateCommand`: each command reads its required arguments, then optional numbers (`numbers()`), and the rest is the name (joined, control/§ stripped, 48 chars). Extra trailing words no longer fail with "引数の数が違います". `hp` strips the name before the strict frame-health parser; `doubletime` likewise.
- Names shown on: tnt, supertnt (was always blank), randomtnt, tntring (each of 12), tntrocket (TntRocket launches), zeustnt and meteor (replace the default label), comets (each comet), salmon and enderman (unchanged). Other commands log `操作（名前）` via `UmetateGame.note`. Old no-name overloads remain.
- Fixes found on the way: `salmon 3 太郎` previously failed (name parsed as seconds).
- Incident: a first patch attempt wrote `UmetateCommand.java` through a failing UTF-8 encode, truncating it to 0 bytes. Restored verbatim from the earlier read; recompiled class was byte-identical to `UmetateCommand.class` in the shipped 2.1.9 JAR before applying the change. Later patches write to a temp file and rename.
- Tests: NameArgumentTest (command parsing for all summon and non-summon commands, hp/doubletime, numeric options), NamedSummonTest (names on displays, primed TNT, comets, rocket TNT); 2 tests updated for the extra help line and `tntRocket(count, name)`. 162 tests pass (work/v220-verify.log). SHA256 112369E2D2E87BE632E7D03F73AD4D74BA3869752366D7953912CB42527BA1B0. Staged 15:51 JST to production `plugins\update\RaiUmetate.jar` (hash matches); active 2.1.9 JAR and data backed up to `backups\RaiUmetate-v2.2.0-20261008-155107`. Server not restarted.

## 2.1.9 Phase 5: frame collapse without falling-block storm; save retry (2026-10-08, QA only)

- Benchmark `-Dumebench.collapse=true` (create 49×21, 3× clear → fill → hp heal -1e9 → 700-tick wait). 2.1.8: each collapse kept ticks at 60–70 ms average for 260–300 ticks (max 101–177 ms). JFR (work/collapse2.jfr): FallingBlockEntity ticking/movement dominated; plugin code <1 %. Cause: ~50,000 contents converted to FallingBlocks at 200/tick, falling through the removed floor.
- User decision: contents vanish in place (no falling). Release removes contents within `performance.instant-max-ms-per-tick` per tick with a sparse debris particle (1 in 40); containers and liquids are kept as before; `frame-health.release-blocks-per-tick` is now unused (commented). Frame removal/rebuild timing unchanged.
- 2.1.9 result: collapse windows max 70.9 ms (first, cold) then 20.6 / 20.5 ms with no tick over 30 ms.
- Found while benchmarking: on Windows a freshly written save file was briefly held by another process (QA tree is under OneDrive), the atomic rename failed with AccessDeniedException and the plugin stopped in safe mode until restart. `ArenaStore.replace` now retries AccessDeniedException up to 40× at 25 ms on the writer thread. Verified that an open reader really blocks the rename on this machine; regression test holds the destination open for 200 ms.
- 156 tests pass (work/v219-verify.log). SHA256 DC9D7901D05A604724F0E7E4ED54D5034D9B02CF265BA20966A29442D14D92B3. Staged 10:59 JST to production `pluginsPdateRaiUmetate.jar` (hash matches); active 2.1.8 JAR and data backed up to `backupsRaiUmetate-v2.1.9-20261008-105935`. User restarted 11:00 JST: `Enabling RaiUmetate v2.1.9`, no errors/exceptions, active JAR hash matches, update folder empty, config.yml unchanged, rai_0421 joined.

## 2.1.8 Phase 4: frame damage without the ray storm (2026-10-08, QA only)

- Benchmark (work/ume-bench `-Dumebench.tnt=true -Dumebench.power=N`: create 49×21, then 5× clear → fill → `tnt(20, N)` → 400-tick wait; no player). JFR (work/tnt*.jfr) on 2.1.7 at power 20: ~80% of the 1.4–1.7 s explosion tick was `FrameHealth.damageAt` + `World.rayTraceBlocks` — with a full box nearly every ray is blocked, so the strongest-hit early exit never triggers (~8,000 frame blocks × 64 rays per TNT).
- Exact shortcut: `FillState` tracks occluding cells (material occluding **and** a single full 1×1×1 collision box, cached per material; e.g. soul sand excluded). `UmetateGame.occluders()` reports runs of fully occluding interior layers as boxes. A ray crossing such a box (shrunk by 1e-3) must hit a full cube there before reaching the frame. `FrameHealth.damageAt`: frame blocks whose 8 extreme sample rays all cross one occluder are dropped (the set of targets whose ray crosses a convex region is convex, so all 64 rays are blocked → damage exactly 0); remaining rays skip the world trace when they cross an occluder; a candidate stops once even all remaining rays visible could not beat the strongest hit. Candidates are enumerated from floor/wall columns only and sorted after dropping hidden ones. Frame material check avoids `Material.valueOf` on real servers.
- Parity: new FrameDamageParityTest compares against a copy of the original algorithm with a full-cube ray tracer on 96 random layouts/origins/powers (empty, half, full, 97 % full) plus a full-box top blast: identical damage every time, never more rays, and >4× fewer rays for the full box.
- Results (heaviest tick, runs 1/2–5): power 20 ×20 — 2.1.7: 1412 / 961–1122 ms → 2.1.8: 165 / 41–62 ms. Power 4 ×20 — 2.1.7: 131 / 11–28 ms → 2.1.8: 46 / 11–16 ms.
- 155 tests pass (work/v218-verify.log). SHA256 ADE0CA69634F48D060517170EE35AE8271E2B534A9230447D6C8ED2688A2363A. Staged 10:30 JST to production `pluginsPdateRaiUmetate.jar` (hash matches); active 2.1.7 JAR and data backed up to `backupsRaiUmetate-v2.1.8-20261008-103039`. User restarted 10:31 JST: `Enabling RaiUmetate v2.1.8`, no errors/exceptions, active JAR hash matches, update folder empty, config.yml unchanged, rai_0421 joined.

## 2.1.7 Box-wide edits in a few ticks (2026-10-08, QA only)

- Player-online comparison of 2.1.6 vs s2e (work/compare-run5-v216-events.tsv), heaviest tick: create 32.6 vs 9.1, fill 71/57 vs 5.3, clear 71.4 vs 8.8, delete 146/86 vs 5–45, supertnt 101.5 vs 94.8 ms.
- Floor of the Bukkit API (work/place-probe, whole tick incl. lighting, 46,389 blocks, layered palette, warm): fill 25–33 ms, clear 38–44 ms. 2.1.6 was already at this floor, so s2e (5 ms) presumably bypasses the API (not verified; s2e code not inspected).
- User chose to spread box-wide edits over 2–3 ticks. `performance.instant-max-ms-per-tick` (default 20, 0.5–1000) bounds each tick of fill/clear/wall/layer/create/delete/resize/cleanup/reconcile in instant mode; direct fill now also honours the deadline. Pre-checks use already loaded chunks immediately instead of one async load per tick.
- Automated benchmark (work/ubench7.log, 49×21, no player), warm runs: create 8–11 ticks / max 21.6–31.1 ms, fill 2 ticks / 20.8 ms, clear 2 ticks / 20.4–21.1 ms, delete 4–6 ticks / 23–25 ms (2.1.6: delete 27 ticks). First run after start is slower (JIT/chunk load: create max 237 ms).
- 154 tests pass (work/v217-verify.log). SHA256 196F48ED65D484DD60A97C53141FA9C5BD72BB39A8A8433CC289D0924BFDD034.
- User QA run with player online (work/qa-server/compare-plugins/CompareRecorder/events.tsv): create 211.7 ms (first create after server start), fill 28.4/22.2, clear 22.2, delete 25.6, supertnt 20 122.5 ms. User: "気にならない".
- Staged 09:35 JST to production `plugins\update\RaiUmetate.jar` (replacing the staged 2.1.4); active 2.1.3 JAR and data backed up to `backups\RaiUmetate-v2.1.7-20261008-093545`. User restarted at 09:37 JST: `Enabling RaiUmetate v2.1.7`, no errors/exceptions, active JAR hash matches, update folder empty; config.yml only gained the two new defaults (`instant-box-changes: true`, `instant-max-ms-per-tick: 20`).

## 2.1.6 Profiled bulk edits (2026-10-08, QA only)

- User QA run of 2.1.5 (work/compare-run4-v215-events.tsv, player online): delete 347 ms, create 52.7, fill 144 (now one tick), clear 74, supertnt 120/35 ms.
- Automated benchmark (work/ume-bench: drives RaiUmetate by reflection with a proxy player, no player online, 49×21, 4 repetitions) + JFR 1 ms sampling (work/ubench*.jfr, work/jfr_hot.py / jfr_callers.py). Warm repetitions 2–4:
  - 2.1.5: create 22–30, fill 68–78 (first 150), clear 34–36, delete 62–73 ms. JFR: FillState.finish dominated fill (BitSet.clear → recalculateWordsInUse, quadratic in bulk); create/delete built an 80k HashSet for protection.
  - 2.1.6: create 25–28, fill 41–47, clear 46, delete 70–78 ms. Remaining time is Minecraft internals (Level.setBlock, Starlight light queue, sendBlockUpdated).
- Fixed a hang introduced in 2.1.5 (QA only, never staged): with instant fill a non-solid block entity (e.g. a sign) inside the box was reserved and retried forever in one tick. Direct fill now visits each free cell at most once per tick without reservation bits (`FillState.nextFree`/`placeDirect`), regression test added.
- Also: per-layer BlockData in fill, plain get/put cache, lazy envelope with arithmetic protection for create/delete (no 80k HashSet), delete restores in world order and skips per-block occupancy updates.
- Driving s2e the same way failed (vanilla dispatcher rejects proxy senders; direct executor call NPEs inside s2e), so s2e numbers remain from the player-online run; a fair comparison needs another player-online run.
- 153 tests pass (work/v216-verify.log). SHA256 ABD28F25F3567BF171290F1CF34603FA1F1CDF2F926FA4A81C7D86364F14B53A, QA only.

## 2.1.5 Bulk edits as light as s2e (2026-10-08, QA only)

- Large-box QA comparison (both ~47×21, work/compare-run3-large-events.tsv), heaviest tick s2e vs RaiUmetate 2.1.4: create 36.9 vs 91.2 ms, fill 19.5 ms at once vs gradual (~2.4 s), clear 11.2 vs 81.8 ms, supertnt 20 101.2 vs 98.2 ms. User asked for fill to complete at once like s2e.
- QA micro benchmark (work/place-probe, 46,389 blocks, warm): setType 9.5–12 ms, cached setBlockData 2.6–4.8 ms, read+write ~3 ms. So per-block bookkeeping, not world writes, dominated.
- Changes: cached default BlockData per material for all non-physics writes; gravity checks queued only when a block can actually fall (new block over a gap / block over a new gap); `FillState.reserve` resumes from a cursor instead of scanning from 0 (was quadratic for bulk fill); direct fill uses the instant budget (whole request in one tick); clear and round cleanup skip per-block occupancy updates, rebuild occupancy at the end and reconcile only if containers were left.
- 152 tests pass (work/v215-verify.log). SHA256 A0EF5AFC0CB0E93498405B3E8DE55F50A1C8A579D9C4F7EA074921E6B93437DE, QA only; production still has 2.1.4 staged.

## 2.1.4 Feel closer to s2e-bedrock-box (2026-10-08, QA only)

- User feedback on 2.1.3: `/umetate clear` took ~9 s at max size, no message reached players, TNT felt slow. Goal restated: personal remake of s2e-bedrock-box; responsiveness first.
- `performance.instant-box-changes: true` (default): structural edits, pre-checks and runtime reconcile finish in one tick after the checkpoint is written (`false` restores the time-sliced mode). `tell()` now also messages every online player (`[埋め立て] …`).
- Black-box comparison on the QA server (work/compare-recorder, events in work/compare-run1-events.tsv; original JAR run unmodified, not decompiled): s2e spawns real TNTPrimed at box top+1 in the command tick and all explode exactly 30 ticks later; RaiUmetate used a BlockDisplay starting only 3 blocks above the highest block with t² easing, and staggered supertnt (30–46 ticks). Clear was instant in both (max tick 4.4 ms vs 6.7 ms on 11×9).
- Plain TNT (tnt/supertnt/randomtnt/tntring) is now a real TNTPrimed dropped from box top+1 (or the lid top), detonated by the plugin's own explode() on the same 30-tick schedule, no stagger; vanilla fuse is longer so it never self-detonates. Meteor/comet/Zeus keep displays. Effect loop stops after an explosion that starts a frame collapse (it clears the list).
- Tests: 150 pass (work/v214-verify.log). SHA256 A8044C3C08A54D68D9C43C3A16EC3E64FE269050C81024E21D55C5508CFD65FF, installed on QA only.
- Second QA run (user, 08:45): user reports the same feel as the original. Both spawn all TNT in the command tick (5 and 20). Slow ticks >50 ms occurred only around the original's explosions (84.6, 61.9, 51.8 ms); none around RaiUmetate's. Both boxes were 11×9 (s2e `create 11 9`, RaiUmetate default 11/9); larger sizes not compared yet.
- User asked for no chat messages at all: `tell()` is console-only again. Final 2.1.4: 150 tests pass (work/v214-final-verify.log), SHA256 735FE2B9F81B4B20CEE9ABD6305D112448FE582DB63BCF04D0D6778C311DC387. Staged to production `plugins\update` 08:51 JST; v2.1.3 JAR and data backed up to `backups\RaiUmetate-v2.1.4-20261008-085117`. Server not restarted.

## 2.1.3 Phase 3 implementation (2026-10-08)

- Scope (§2.5 + leftover B7): `Box.cellX/Y/Z(i)` and `blockAt(i)`/`update(i,block)` remove the index→Cell→Block→index round trip on hot paths. Gravity sweep walks `FillState` occupied bits (`nextFallCandidate`) and reads the world only to confirm candidates; stale candidates correct the cache; queued checks skip world reads when the cached cell below is occupied. `gravity.scan-per-step` now bounds confirmed candidates. Reconcile scan has a time budget; default `performance.reconcile-per-tick` 500→64 (existing config values respected; preparation now uses `changes-per-tick` so create/resize pre-checks are not slowed). Runtime `reconcileAll` (frame restore, layer, round cleanup) is time-sliced and keeps the game busy until done; startup load stays synchronous (before ticks). `BedrockLid.contains` is arithmetic. `wall` iterates only the shell, `layer` only its layers, `clear`/round cleanup only the interior, all lazily (no 80k-cell lists); container checks keep their previous ranges.
- Behaviour notes: blocks changed outside game events (e.g. WorldEdit) now start falling after the reconcile scan sees them (max size at 64/tick: up to ~56 s), not via the gravity sweep. Round-cleanup message is sent after the reconcile finishes; clock reset timing is unchanged so the saved checkpoint matches runtime.
- Tests: 4 existing gravity tests updated to reconcile externally placed blocks first. New PhaseThreeTest (8): index/iterator coverage, lid parity, BitSet candidates and reservations, full supported arena sweep with zero world reads, stale-candidate correction, multi-tick budgeted reconcile staying busy, Settings default/respect, wall touches only the shell.
- `mvn -o -ntp verify`: 147 tests, 0 failures/errors (work/phase3-verify.log). No live/Spark measurement yet.
- SHA256 (target/RaiUmetate.jar): C1ECC70400070CE02A65A20FAFC1DF21BEE7B44F4C5F38FF73F98216B802C98F. Staged 08:22 JST to `plugins\update\RaiUmetate.jar` (hash matches); v2.1.2 JAR (943466…) and data backed up to `backups\RaiUmetate-v2.1.3-20261008-082253`. User restarted at 08:23 JST: `Enabling RaiUmetate v2.1.3`, no errors/exceptions, active JAR hash matches, update folder empty, config.yml content unchanged, rai_0421 joined. In-game checks (round clear, layer, wall, gravity after TNT) pending.
- Production config keeps `reconcile-per-tick: 500`; lower it to 64 manually to get the new default.

## 2.1.2 Phase 2 implementation (2026-10-08)

- Scope: immutable `Settings` snapshot (hot paths and DoubleTimeManager no longer read config; rebuilt on config-changing commands without resetting the double-time countdown), `TileGuard` per-Material cache replacing every `getState() instanceof TileState/Container`, ChunkLoad early return outside the arena world / box ±2 chunks, `Box` validation unified with `SizeRules` (legacy saved sizes still load via `Box.restored`). Phase 3+ not implemented.
- Red first: `distantAndOtherWorldChunksDoNotLoadEntities` failed before the change (work/phase2-red.log).
- `mvn -o -ntp verify`: 139 tests, 0 failures/errors (work/phase2-verify.log, rerun work/phase2-verify-claude.log).
- Live TileGuard parity on QA Paper 26.1.2-65 (work/qa-server, `--plugins phase2-plugins`): CHEST, BARREL, OAK_SIGN, SPAWNER, PLAYER_HEAD, WHITE_BED, HOPPER, STONE, AIR, DIAMOND_BLOCK, BEEHIVE, DECORATED_POT, ENDER_CHEST, SHULKER_BOX all match the old `getState()` classification (14/14, work/phase2-tile-live2.log). The first attempt (work/phase2-tile-live.log) left a hung QA process holding the world lock; it was stopped and the probe rerun.
- SHA256 (target/RaiUmetate.jar): 943466703392605F4B791B904F55CBF28DCA82C108EF61DC9223942CB876B2D6
- Staged 2026-10-08 08:10 JST: `C:\妨害配信\09-28_ume\plugins\update\RaiUmetate.jar` (hash matches build). Previous v2.1.1 JAR (2080AA23…) and plugin data backed up to `backups\RaiUmetate-v2.1.2-20261008-081050`. User restarted the server at 08:11 JST: log shows `Enabling RaiUmetate v2.1.2`, startup message, no errors/exceptions; active JAR hash matches; update folder emptied; config.yml content identical to backup; arena.yml/frame-health-state.yml written at shutdown. rai_0421 joined. Spark comparison for Phase 2 not taken.

## 2.1.1 Phase 1 implementation and comparison complete (2026-10-08)

- Scope: split immutable restoration data from small YAML state, asynchronous saves, time-sliced resize, debounced FrameHealth saves, cached frame materials. Phase 2+ not implemented.
- `mvn -o -ntp -Dmaven.repo.local=... -f umetate-plugin/pom.xml verify`: 130 tests, 0 failures/errors. work/phase1-verify.log.
- Regression baseline: 3 failing probes before changes; all pass after. Added max-size binary round-trip, generation fallback, truncated data, legacy backup/migration, interrupted terrain restoration, pending-state-save progress and boundary cache checks.
- No `.join()` or YAML save remains in UmetateGame/FrameHealth. Shutdown waits for the ordered writer. Runtime writes are nonblocking; structural edits await durable checkpoints.
- Baseline Spark: dedicated measurement use of the user-authorized existing arena in 09-28_ume, rai_0421 online, v2.1.0. Six scenarios including doubletime start 120 2 recorded locally. Combined profile: 3,720 ticks / 189.652 seconds; final rolling 1-minute MSPT max 940.0126, mean 6.0097, median 3.9831, p95 5.7191. These are rolling Spark stats, not per-command latency.
- RCON round trips (not MSPT): stop 474.79ms, resume 431.04ms, shield 467.90ms, sizemax 936.28ms. Logs in work/phase1-baseline.
- At TNT100, existing effects.max-active rejects following randomtnt/salmon. A separate doubled-before profile uses TNT40 + randomtnt + salmon10 so additional copies actually launch.
- Candidate JAR staged and hash checked: 2080AA238EE7262123AF81773CCA1A142A44A35421A2C6333E01A7C874C6E34C.
- Live migration, after-Spark comparison and final normal-operation restart verified. Main profile window max: 940.0126 -> 157.2311 ms; actual doubled effects profile: 174.9027 -> 52.2141 ms. Global 100ms target NOT met; no Phase 2+ changes. See PHASE1-REPORT.md.
- Measurement Win sink restored to `ste`; final restart verified at 07:28 JST. No Win test message sent externally. Paths and state: work/phase1-baseline/measurement-state.json.

## 2.1.0 妨害倍率タイム (2026-10-08)

- Maven verify: 119 tests, 0 failures, 0 errors. Log: work/double-time-verify.log.
- Verified timer replacement, extension, expiry, explicit end reasons, main-thread restriction, staggered launches without recursion, exclusions, cap handling through the real TNT entry, busy/delete lifecycle, world bossbar membership, titles, shutdown suppression, commands, message replacement and additive OBS JSON.
- Existing singleton limits for comets/blackhole remain: additional active instances are skipped.
- Built and staged plugins/update/RaiUmetate.jar; active/pending JARs and plugin data backed up under backups/RaiUmetate-v2.1.0-*. Build/deployed hashes match.
- SHA256: E7D76D391E9A8B96215F47AE2C8FBBE88A21E0DCAB04BD808D92FB7CDC1E4C57
- Server was not restarted. In-game titles/sounds, OBS rendering and live gameplay remain unverified.

## 2.0.4 Gravity start latency (2026-10-08)

Changed blocks enqueue gravity checks; removed supports prioritize the cell above. These checks run every tick before the periodic sweep and entity monitoring. Native falling physics, entity limits and time budget remain. Regression failed before the fix; Maven verify passed 107 tests after it (work/gravity-delay-before.log, work/gravity-delay-after.log). Staged plugins/update/RaiUmetate.jar after backing up active/pending JARs and data. Server restart and in-game latency measurement not performed.

# v2.0.3 重力負荷対策（2026-10-08）

- 対象サーバーのpluginsに独立した重力JARはなく、RaiUmetate内蔵の通常重力を修正。
- 修正前、支持されたブロックの不要なBlockState取得と遅い生成の一括集中を実コードのテストで再現（work/gravity-before.log、2件失敗）。
- 支持確認を状態取得より前へ移動。gravity.max-work-ms-per-tick（既定2ms）で監視・走査・生成を分散し、カーソルを保持して残りを継続。
- 着地候補はFillStateの空き情報を使い、最大32候補をワールド再確認。ブロックごとの全域探索を廃止。
- 106テスト成功、失敗0・エラー0、Maven verify成功（work/gravity-after.log）。生成が分散されても対象49個が全て落下すること、満杯時の着地処理が全域を読まないことを検証。
- 砂と同じFallingBlock、素材変換、コンテナ保護、落下数上限、重力無効化、落下中断時の復元、サイズ即時変更の回帰確認を含む。
- 実サーバーのTPSとクライアント映像は未測定。大量崩落時は落下開始を分散する。枠崩壊の放出処理は変更していない。
- 配置: C:\妨害配信\09-28_ume\plugins\update\RaiUmetate.jar。バックアップ済み。サーバー再起動は未実施。
- SHA256: 635A15669751383A8A0D4B9D73B31A1588AB0EA44AE5C7F0345FDBA29E84B841（配置先一致）

---

# v2.0.2 検証（2026-10-08）

- サイズ変更はコマンドの呼び出し内で一括完了する方式へ変更。v2.0.1の時間分割はサイズ変更には適用しない。
- 先に「resizeから戻った時点でbusy=false」のテストを実行し、旧コードで失敗することを確認（work/instant-resize-before.log）。修正後は成功。
- 103テスト成功、失敗0・エラー0（work/instant-resize-tests.log）。旧時間分割専用テストを新仕様の即時反映・同一呼び出しでの縮小復元の検証へ変更。
- 変更直後の実ブロック材質、縮小での元地形復元、最新arena.yml、チェスト保護、保存失敗時の変更停止、自動TPなし、上限補正を検証。
- 本番Paperでの応答時間・描画速度は未測定。即時一括化は複数tickにわたる待機をなくすが、処理時間がゼロになるものではない。

配置: `C:\妨害配信\09-28_ume\plugins\update\RaiUmetate.jar`。次回起動で反映。既存JAR・更新予約・設定はサーバーのbackupsへ保管。サーバーの再起動は未実施。

SHA256: `DEEC2A87607CF1D73BC1405B79261B2C85983416A45EA3A2E48C87870489E845`（配置先一致）

---

# v2.0.1 検証（2026-10-07）

- 105テスト成功（失敗0・エラー0）。上限超過の整数・桁の大きい整数、世界の境界・高さ制限への補正、最大サイズでの無変更を検証。
- 幅変更の実コードに、保存用BlockData文字列変換を1件2msに遅延するテストを追加。修正前は196件が1tickに集中して失敗。修正後は最大1件/tickで成功。
- 該当再現テストの観測最大tickは修正前816ms、全テスト実行中の修正後18ms。ただしモック/JIT/OSの影響を受ける参考値であり、実サーバーの負荷比較ではない。回帰判定は時間の絶対値でなく1tickへの変換集中を検出する。
- ブロックのsetTypeを遅くしても更新がtick間に分散すること、非同期チャンク読み込みが終わるまでワールドを読まないことを検証。
- 復元データの書き込み完了前はブロックを変更しない。保存失敗なら操作停止。YAML生成が別スレッドで動き、書き込み順序を守って最終状態を保存することを検証。
- 勝利通知は復元用チェックポイントの保存完了後に実行する。正常クリア・次ラウンド・シールド・枠HP・Win送信の回帰テストも成功。
- 新設定 `performance.max-work-ms-per-tick: 2.0`。個数予算と時間予算を併用し、不要な同一材質の再配置を省く。
- 本番でのTPS・クライアント描画の滑らかさは未確認。負荷の集中を修正しているが、ディスク待ち・チャンク生成・端末の描画速度による影響をゼロと保証するものではない。

再現コマンド:

```powershell
mvn -o -ntp "-Dmaven.repo.local=C:\Users\rai42\AppData\Local\Temp\codex-m2" -f umetate-plugin/pom.xml "-Dtest=GameIntegrationTest#resizeMustNotSerializeWholeSnapshotInOneServerTick" test
```

ログ: `work/resize-lag-baseline.log`（修正前）、`work/resize-regression.log`（修正後）。


配布記録（2026-10-07 05:24 JST）:
- `plugins/update/RaiUmetate.jar` へv2.0.1を配置。サーバーは起動・再起動していません。
- 配置先: `C:\妨害配信\09-28_ume\plugins\update\RaiUmetate.jar`
- バックアップ: `C:\妨害配信\09-28_ume\backups\RaiUmetate-v2.0.1-20261007-052432`
- SHA256: `D163E745A2C93B107A8748355174106191DB91794206C4D1E6958583114468D5`（ビルド成果物と配置先で一致）
- JAR内のversion 2.0.1、ArenaStoreクラス、新しい時間予算設定を確認。
- 最終packageは機能コードの105テスト成功後、バージョン・文書更新だけを行ってテスト再実行なしで作成。

---

# v2.0.0 検証（2026-10-07）

- Java 25 / Paper API 26.1.2.build.74-stable、Maven `verify` 成功。
- JUnit 97件、失敗0・エラー0。従来の重力・TNTロケット・ふた・爆発・層素材の回帰検証を含みます。
- 2だけ30tick、他20tickのカウント時間、中断・再開・1秒設定、直径検証・上下限・サイズコマンド・旧設定換算を検証。
- HPの正負heal、reset、limit set/add、範囲外拒否、最大HPの再読み込み、HP0時のLose1回・赤タイトル・時間指定を検証。
- 直接配置のtick上限・氷結・満杯時終了、噴水との予約共有、満杯後の要求破棄を検証。
- 最高Yキャッシュの空・一部・満杯・撤去・ふた・ワールド上限、実ゲームのTNT/シャケ生成位置・重力後更新を検証。
- FIFOと待ち上限、部分送信後の未送信分だけの再試行、最終失敗、停止中断、Noop、依頼イベント、コマンド引数、名前空間補完フィルターを検証。
- STE互換WebSocketは127.0.0.1の一時ポートの偽エンドポイントで検証。win/loseのJSON件数、burst/pacedの間隔、各依頼の切断を確認。本物のSTEには送信していません。
- JAR内のplugin.ymlが2.0.0で、softdependとbedrockエイリアスがないこと、および新クラスの同梱を確認。

配置予約: `C:\妨害配信\09-28_ume\plugins\update\RaiUmetate.jar`

バックアップ: `C:\妨害配信\09-28_ume\backups\RaiUmetate-v2-20261007-002527`

SHA256: `1EE48B413CBB338C3B60DBA33F678E4E7F2BF322F4BC84AF780E5459E8AF1369`（ビルド成果物と配置先で一致）

サーバーの停止・再起動はしていません。Javaプロセスの用途を確認できないため稼働中として扱い、Bukkitのupdate-folderを使用しています。旧RWin JARは残しています。停止時にplugins外へ移してから再起動してください。実Paperでの映像・最大サイズの実負荷・実STEの表示は未確認です。

受け入れ仕様との解釈: retry.max-attemptsは「再試行回数」とし、3なら初回＋再試行3回。旧max-radius=50の換算99は新しいハード上限49へ移行します。既存保存箱は復元保護のため旧サイズでも読み込み、新規サイズ指定には新制限を適用します。占有状態は設定予算で継続照合し、カウント末尾の全走査による待ち時間追加は行いません。

---

# 1.7.1 通常TNTの同期（2026-10-06）

- 通常TNTの個数指定で同じtickに全個体を生成し、飛行時間を30tickで統一。強化TNT・ロケットの既存タイミングは維持。
- Maven verify成功、71テスト。5個の同時生成・全個体のduration=30/age=0を検証。実ゲーム表示は未確認。
- 更新予約: C:\妨害配信\09-28_ume\plugins\update\RaiUmetate.jar
- バックアップ: C:\妨害配信\09-28_ume\backups\RaiUmetate-20261006-215418
- SHA256一致: 9A83B1769EE5DC9E1BE762148224B7D72EBC7C5439C957B206F0E9266BF796DE
- 再起動で反映。

# 1.7.0 参照版TNTロケット（2026-10-01）

- 07-17_umetateのs2e-bedrock-box 3.0.3を静的解析。Rename/Simplify/Strings/Vineflowerで復元したClass14のtntrocket分岐とClass30.member207を確認。参照JARを実行していない。解析結果はwork/bedrock-rocket-analysis/final-source。
- 初期20回・2tick間隔・足元に導火線0のTNT・メイン指定またはオンライン全員・クリエイティブ飛行解除・ノックバック耐性の一時解除・最終発射10tick後の復元を接続。表示を日本語化。地形破壊は箱内限定。
- Maven verify: 71テスト成功。タイミング・全TNTの導火線0・属性復元・重複実行・キャンセル・コマンド初期値を検証。実ゲームでの打ち上げ高さ等は未検証。
- 更新予約: C:\妨害配信\09-28_ume\plugins\update\RaiUmetate.jar
- バックアップ: C:\妨害配信\09-28_ume\backups\RaiUmetate-20261001-081636
- SHA256一致: 47EE2C76BF8051B1E0175B6C213B7F748C773114952D00E30E12FD96C33A1305
- サーバー再起動で反映。

# 1.6.0 爆発シャケ・満杯で追加終了（2026-10-01）

- RaiBRの鮭を移植: 個数・時間・威力・名前、独立タイマー、残り時間、警告演出、枠HP、エンティティへの爆発と箱内の破壊を分離。箱の破壊は既存のtick予算・重力へ接続。
- 満杯になったら追加待ちと飛行中の噴水を終了。満杯のカウントダウン中は追加を無視。穴が開いても旧要求は再開しない。
- Maven verify: 68テスト成功。独立した鮭の爆発と後始末、コマンド引数、満杯終了と無視を検証。実ゲームの描画・物理・爆発は未検証。
- 稼働中のため更新予約: C:\妨害配信\09-28_ume\plugins\update\RaiUmetate.jar
- 旧版と既存更新予約のバックアップ: C:\妨害配信\09-28_ume\backups\RaiUmetate-20261001-075546
- SHA256一致: 8A7402987F5ED027353CB9F63E07CC1644B3919D5CE5DCA77529556E9B0954C0
- サーバー再起動で反映。

# 1.5.2 サイズリセット（2026-09-30）

- sizeresetを追加。default-radius/default-heightを通常のresizeに渡す。現行設定は6/9。既存のサイズ変更保護とTPなしを維持。
- Maven verify成功、既存65テスト成功。実ゲームでの新コマンド実行は未検証。
- サーバー稼働中の可能性があるため更新フォルダに予約配置: C:\妨害配信\09-28_ume\plugins\update\RaiUmetate.jar
- 旧版バックアップ: C:\妨害配信\09-28_ume\backups\RaiUmetate-20260930-075349
- 更新JARのSHA256一致: CB5159DABAC3E06EDF222B90F4FE96CEC80AF06FCA51EC63656C0ADB5D13C71C
- 次回のサーバー再起動時に反映。稼働JARは上書きしていない。

# 1.5.1 個数付きTNT（2026-09-30）

- tnt <個数> [名前] に対応。1～100。名前のみ・引数なしの旧形式を維持。
- 65テスト成功。5個の生成と同一名付与、静かな成功処理、旧形式、個数の不正入力を検証。
- 配置先: C:\妨害配信\09-28_ume\plugins\RaiUmetate.jar
- バックアップ: C:\妨害配信\09-28_ume\backups\RaiUmetate-20260930-074555\RaiUmetate-before-1.5.1.jar
- 配置後SHA256一致: 0B14A583CF2C4A1486FC07484FA44B4B97B261627E467EB3ED2905B99C6221B2
- 実ゲームと配信ツールによる名前置換は未検証。再起動で反映。

# 1.5.0 連続噴水・砂型重力・操作表示（2026-09-30）

- Maven verify成功。63テスト、失敗0。
- 実際のfountainTickを通すテストで51tick目に着地数84に対し80となる途切れを再現。表示上限を発射数×飛行時間以上へ補正すると100tickまで段をまたいで毎tick4個の着地を確認。
- 同時表示上限以外は変更せず噴水の再現テストが成功することを先に検証。その後、残りの変更を統合。
- ネイティブFallingBlockの生成・着地・層素材の変更・競合先を上書きしないこと・噴水予約の支え待ち・保存済み落下エンティティの回復・停止前の復元・同時落下数制限をモックで検証。
- サイズ変更完了時にteleportMainが呼ばれないこと、埋め立て率BossBarを生成しないこと、fillの成功通知がプレイヤーに送られないことを検証。
- 容量を超える追加を待機し、満杯でも消さず穴が開くと再開。引数なしfillは予約・落下中を差し引いて不足数だけ追加。fill allは追加していない。
- 停止したサーバーの plugins/RaiUmetate.jar を1.5.0へ更新。
- 旧版バックアップ: C:\妨害配信\09-28_ume\backups\RaiUmetate-20260930-063808\RaiUmetate-before-1.5.0.jar
- 成果物と配置先のSHA256一致: 196DD133949F9B2B6267982EEC3A6F452E095215E5EECA93E9C0EA10C9989A01
- 実ゲームの描画・物理シミュレーション、実際のSTE通知は未検証。サーバー再起動が必要。
# 1.4.0 重力の追加（2026-09-29）

- Maven verify成功。58テスト、失敗0。
- 積み重なりの落下、層素材の変更、個数維持、枠・ふたの保護、噴水予約との競合、コンテナと水の保護、処理上限下での走査継続を検証。
- 上部設置ロックはキャンセルを維持し、プレイヤーへの文字送信がないことを検証。
- 停止したサーバーの plugins/RaiUmetate.jar を1.4.0へ更新。
- 旧版バックアップ: C:\妨害配信\09-28_ume\backups\RaiUmetate-20260929-130310\RaiUmetate-1.3.0.jar
- 成果物と配置先のSHA256一致: 757EEE0C3141C3B22DD86A28D94DBCC4E28FADBAC8A5D686B91CD99A52FA9FC6
- 実際のゲーム画面での落下表示は未検証。サーバー再起動が必要。
# 1.3.0 追加検証（2026-09-29）

- `mvn -o -ntp "-Dmaven.repo.local=C:\Users\rai42\AppData\Local\Temp\codex-m2" -f umetate-plugin/pom.xml verify`: 53テスト成功。
- 設置距離の変更を重ねても加算が累積しないこと、解除時に他の属性補正と基礎値を保持すること。
- 3秒カウントで中央タイトルが3→2→1、音が各1回だけ出ること。再カウントと停止時のタイトル解除。
- 満杯から勝利、`rwin win 1` が一度だけ、内部消去、枠維持、次ラウンドで再度1回だけ通知。
- 通知処理が例外でも中身を消去し、通知を自動再送しないこと。
- 実サーバー上の遠距離設置・音・タイトル・STEの受信は未検証。サーバー再起動後に確認が必要。

## 配置確認

- 2026-09-29 12:56 JST、サーバーJavaプロセスなし・正常停止ログを確認して `C:\妨害配信\09-28_ume\plugins\RaiUmetate.jar` を1.3.0へ更新。
- 旧1.2.0を `C:\妨害配信\09-28_ume\backups\RaiUmetate-20260929-125609\RaiUmetate-1.2.0.jar` に保存。
- ビルド成果物と配置先のSHA256一致: `09B645E5F7FCA39BAF6F5FEFF72F80214111426C5A19CD4A7EACAE7C6C940052`。
- 旧07-17_umetateサーバーから `RWin-1.1.0.jar` と `RWin/config.yml` を追加。JARのコピー前後SHA256一致。STE接続先は同一PCの50707番ポート。
- サーバーの起動、実ゲームでの検証、実際のWin通知は行っていない。

以下は1.2.0までの検証記録です。

# 検証記録

初版2026-09-28、最新更新2026-09-29。対象はPaper 26.1.2 / Java 25。

## 自動検証

- `mvn -o "-Dmaven.repo.local=C:\Users\rai42\AppData\Local\Temp\codex-m2" -f umetate-plugin/pom.xml verify` によるコンパイル、JUnit、JAR生成を実施。
- テストは座標と範囲、噴水の予約・競合・取消・満杯時の待ち処理、勝利までの連続維持と停止、引数の異常値、爆発の球形・箱外除外、噴水の弧の始点と終点を対象とする。
- サーバー上のエンティティ描画、イベント連携、ワールド復元は自動ロジックテストの対象外。
- 初版（1.0.0）：**BUILD SUCCESS、15件成功、失敗0、エラー0、スキップ0**。
- 参照JARのヘルプから抽出した33種類のサブコマンド名を照合し、欠落なしを確認。動作の同一性を確認するテストではない。
- JAR内の `plugin.yml`、`config.yml`、エントリーポイントと内部クラスを確認。
- ソース内の行末空白検査は検出0件。
- 初版（1.0.0）JARのSHA-256：`8F7BA12ADB9B81AE6CF7524B63D820ADBA96D468515A79DB4F5E7C36585D8088`。

## 岩盤のふたへの変更（1.1.0）

- `shield` の引数・ヘルプ・進捗表示を秒数に変更。
- 回数消費と妨害コマンドの無効化を撤去し、実ブロックの屋根と落下物の衝突判定に変更。
- 残り時間・連続使用時の延長、連続被弾、高速落下、ふたの外側／下側、斜めの衝突、元ブロックの復元と中断後の復元を自動テストに追加。
- 最終結果：**BUILD SUCCESS、27件成功、失敗0、エラー0、スキップ0**（既存15件＋岩盤のふた12件）。
- 更新版 `target/RaiUmetate.jar` のSHA-256：`980807CAD1560A9CACBB97132A682C4281E212B04544E166C2680790933EE5AF`。
- 旧回数消費処理・回数指定の説明の残留なしを検索で確認。ソースの行末空白も検出0件。
- ブロック復元テストはメモリ内のブロック表を使用。実Paperでの保存・再起動・描画は未検証。

## 層・2本のバー・RaiBR枠HP移植（1.2.0）

- `mvn -o -ntp "-Dmaven.repo.local=C:\Users\rai42\AppData\Local\Temp\codex-m2" -f umetate-plugin/pom.xml verify`：**BUILD SUCCESS、49件成功、失敗0、エラー0、スキップ0**。
- 内訳：岩盤のふた12、既存ルール15、RaiBRの爆発式3、層4、枠HP12、ゲームへの接続3。
- 層：上から各3段、深さ19以降のクォーツ、低い箱、高さ変更、既存ブロックの材質更新を検証。
- 枠HP：1爆発1最大ダメージ、64点の遮蔽、範囲外、ふた、PrimeEventの事前計算、直接破壊保護、色の閾値を検証。
- 崩壊：敗北コマンド1回、次tickの開始、落下化、コンテナ除外、放出後100tickの待機、元枠復元、HP全回復を検証。
- 保存：実際に一時YAMLを書き出し、新しいFrameHealthインスタンスでHPの継続と中断された崩壊からの復元を確認。
- 接続：UmetateGameが別々の埋め立て率／枠HPバーを生成することと、崩壊中の追加拒否・勝利カウント中断を確認。
- `BlastDamage.java`、`ArenaBounds.java`、`FrameHealthSnapshot.java` はパッケージ名を除き移植元と一致。接続部分の変更は `PORTING.md` に記録。
- JARのバージョン1.2.0、枠HP・層のクラス、設定とOBSページの同梱を確認。行末空白は検出0件。
- 更新版JARのSHA-256：`09A085E8D12662CCBE9C8E61775368BA626EB641473B023C9EDF912096C86B35`。
- Paperのワールド・イベント・スケジューラはモック。RWinへのコマンドは呼出しの記録だけで、実サービスへ送信していない。実Paperの描画・落下・重力プラグイン連携・OBSは未確認。

## ゲーム内確認（未実施）

実サーバーは停止・変更していない。以下は導入前にテスト用ワールドで確認する項目。

1. `create 6 9`：岩盤の箱、「埋め立て率」と「枠HP」の2本が表示され、縁へ移動する。
2. `fillblock 30`：中央から弧を描いて飛び、着地後に30個増える。飛行中は進捗が先に増えない。
3. 連続した追加中に着地先へ手置きする：重複上書きせず別の穴へ振り替える。
4. `fill`：全マスが埋まり、15秒後に一度だけ勝利する。
5. 勝利カウント中に `tnt`：穴が開いて中断し、再度埋めるとカウントがやり直される。
6. 各妨害：TNT、ランダムTNT、強化TNT、隕石、彗星、雷、リング、ブラックホール、エンダーマン、牢獄をそれぞれ実行する。
7. `shield 10` → `supertnt 10 5`：実際の岩盤のふたが出て、TNTが上で爆発する。繰り返しTNTを落としてもふたは消耗せず、10秒後に解除される。
8. `freeze 5` 中に追加：噴水が止まり、解除後に続行する。手置きも制限される。
9. `repair 50`：緑の演出と噴水で穴が埋まる。
10. `heightup 2` / `radiusup 1` → 縮小：重なる内側のブロックが残り、縮小した外側は元の状態になる。
11. `glass` / `wood` / `rock`、`edit`、`toplock`、`autoreplace`、`layer`、`color` を確認する。
12. `stop`：満杯でも勝利しない。`start`：再開する。
13. コンソールから `bedrock set_main_user <名前>` と各妨害・支援を実行する。
14. 再起動：箱・進捗が復元され、表示エンティティが残留しない。
15. `delete`：箱作成前の地形へ復元され、噴水や妨害が残留しない。
16. 大量支援・強化TNTを実行してtick時間を計測し、必要なら設定の発射数・表示上限を調整する。
17. ふたの有効中に `shield 5`：残り時間が5秒増える。`freeze` と `enderman` はふたがあっても発動する。
18. ふたの発動前にその高さにガラスを置く：解除後にガラスへ戻る。チェストの場合は発動を拒否する。
19. ふたの有効中に高さ変更・削除・停止・再起動：岩盤が残留せず、元のブロックへ戻る。異常停止からの復帰も別途確認する。
20. ふたがある間に `fillblock` と `repair`：追加処理が継続し、ふたのブロック自体は埋め立て率に含まれない。
21. 高さ21の箱に追加：上からダイヤ・金・鉄・エメラルド・アメジスト・銅が各3段、その下はクォーツになる。高さ変更後も新しい上端基準になる。
22. 枠の近くでTNT：遮蔽に応じてHPが減る。岩盤のふたで受け止めた爆発ではHPが減らない。
23. テスト用の低い最大HPで崩壊：敗北通知が1回だけ送られ、中身が落下し、放出完了から5秒後に元の枠と全HPが戻る。実RWin連携は別途確認する。
24. `hp reset`：通常時は全回復、再生成中は拒否。再生成中の追加・勝利カウントも止まる。

元JARとのゲーム画面・動作タイミングの比較は未実施。公開コマンドを基準にした新規実装であり、完全な挙動一致を保証する検証ではない。
