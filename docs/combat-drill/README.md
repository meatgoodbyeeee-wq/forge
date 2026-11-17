# コンバットドリルの戦闘判断を Forge に移植する（作業メモ）

元ネタ：[combat-drill](https://github.com/meatgoodbyeeee-wq/combat-drill) の `src/engine.js`（基本エンジン：手札なし・盤面だけ）。引き継ぎメモは combat-drill 側の `docs/forge-port.md`。

## 1. 入れたもの

| 場所 | 内容 |
|---|---|
| `forge-ai/src/main/java/forge/ai/combatdrill/CombatDrillSolver.java` | `engine.js` の `ccCombat`／`ccAssign`／`ccDeals`／`ccValue`／`makeSolver` を Java に移植。Forge の型に依存しない純 Java |
| `forge-ai/src/main/java/forge/ai/combatdrill/CombatDrillAttack.java` | 変換層。Forge の盤面をドリルのクリーチャー形式に変換し、対象の盤面なら攻撃宣言を差し替える |
| `forge-ai/src/main/java/forge/ai/combatdrill/CombatDrillStats.java` | sim の最後に出す集計 |
| `AiController.declareAttackers` | 元の判断（＋Flat Monte Carlo）の後に `refineAttackWithCombatDrill` を呼ぶ |
| `AiProps` の `COMBAT_DRILL_*` | 設定。既定はオフ |
| `forge-gui/res/ai/Drill.ai`／`DrillDecisive.ai` | Default.ai ＋ドリル有効のプロファイル（mode が always／decisive） |
| `docs/combat-drill/tools/` | JS 版との照合（`gen.js`＋`XCheck.java`）と速度計測（`Bench.java`） |
| `docs/combat-drill/decks/` | 検証用デッキ（キーワードだけのクリーチャー＋基本土地、除去入り版） |
| `docs/combat-drill/tools/runall.sh`・`analyze.py`・`diag.py` | まとめて sim を回すスクリプト、勝率の集計、差し替えの分類（`COMBAT_DRILL_VERBOSE=true` のログから） |

### Java 移植で足したもの（engine.js にはない）

- `rootNoAttack`：今のターンに攻撃できない自分のクリーチャー（召喚酔い・タップ中など。`CombatUtil.canAttack` で判定）
- `rootStayTapped`：今タップしている自分のクリーチャーは、相手の次のターンもタップのまま（ブロックできない）
- `choose(root, preferred)`：元の AI の攻撃を先に評価し、**厳密に良い**攻撃が見つかったときだけ差し替える
- 時間制限（反復深化：ply 4→6→8、時間切れなら最後に読み切った深さの答え。ply 4 も間に合わなければ元の判断のまま）

### 適用条件（`CombatDrillAttack.refine`）

- 2人対戦で、防御側は相手プレイヤーだけ（プレインズウォーカー・バトルがあれば対象外）
- 両者の戦場のクリーチャーがすべて、モデル化済みのキーワード（飛行・到達・先制攻撃・二段攻撃・接死・トランプル・威迫・警戒・絆魂・破壊不能・防衛・呪禁・護法・速攻・瞬速）しか持たない
  - 起動型能力（マナ能力と、手札からだけ使えるものは除く）・誘発型能力（自身の ETB は除く）・常在型能力・置換効果があれば対象外
  - ダメージを負っているクリーチャーがいれば対象外
- 各陣営のクリーチャーが `COMBAT_DRILL_MAX_CREATURES`（既定 8）体以下
- 手札・マナ・インスタント・ドローは考えない（基本エンジンの前提のまま）

## 2. 照合結果

`gen.js` で作ったランダム盤面 400 件（1〜4体ずつ、キーワードは全種類、相手のタップあり、maxPly 4〜8）で、全攻撃パターンの評価値が JS 版と **400/400 で完全一致**。`choose()` の最善値も全件一致。同じクリーチャーを多めに入れた最大5体ずつの 150 件でも 150/150 で一致。

```sh
DRILL_ENGINE=/path/to/combat-drill/src/engine.js node docs/combat-drill/tools/gen.js 400 > cases.txt
# 同じクリーチャー入り・最大5体ずつ（対称性の除去の確認用）
DRILL_ENGINE=/path/to/combat-drill/src/engine.js DUP=1 MAXN=5 node docs/combat-drill/tools/gen.js 150 > cases_dup.txt
javac -d out forge-ai/src/main/java/forge/ai/combatdrill/CombatDrillSolver.java docs/combat-drill/tools/*.java
java -cp out xc.XCheck cases.txt
```

## 3. 速度（Java、ランダム盤面、1回の判断）

### 高速化（2026-11-16）

値は変えずに（JS 版との照合は 400/400＋同じクリーチャー入りの 150/150 で完全一致のまま）次の3つを入れた。

- **戦闘結果のキャッシュ**：戦闘の結果は「誰が攻撃し、誰が誰をブロックしたか」だけで決まるので使い回す。反復深化の各深さでも共有
- **同じクリーチャーの対称性の除去**：能力・P/T がまったく同じクリーチャーは、攻撃では「先頭から何体」だけを、ブロックでは割り当て先が並び順に増えていくものだけを調べる
- **ブロックの並べ替え**：1対1で見て守る側に得なブロック（相手を倒せて自分は死なない等）から試し、αβ の枝刈りを早く効かせる

| 盤面 | ply 4 | ply 6 | ply 8 |
|---|---|---|---|
| 3対3 | 12ms → 3ms | 30ms → 5ms | 89ms → 17ms |
| 4対4 | 43ms → 5ms | 0.45s → 11ms | 1.8s → 18ms |
| 5対5 | 0.7s → 9ms | 4.4s → 65ms | → 0.24s |
| 6対6 | 4.6s → 0.1s | → 0.9s | → 2.4s |
| 7対7 | — → 0.6s | → 3.8s | — |
| 8対8 | → 2.4s（3割が5秒超） | — | — |

これに合わせて `COMBAT_DRILL_MAX_CREATURES` の既定を 6 → 8 に上げた。

試したがやめたもの：攻撃の候補を「全員攻撃」から試す（6対6 で40倍遅くなった）、攻撃する体数の少ない順（効果は誤差程度）。

## 4. 自己対戦

デッキ：`drill-wg.dck`（白緑）対 `drill-br.dck`（黒赤）。どちらもキーワードだけのクリーチャー＋基本土地。`-mc off`。

### 試合数を増やした検証（2026-11-17）

除去入りのデッキ（`drill-wg-rm.dck`／`drill-br-rm.dck`：クリーチャー17＋除去6＋土地17）を追加。白緑の除去は噛みつき系・《払拭の光》・《巨怪の撃滅》、黒赤は《ショック》《稲妻の一撃》《殺害》《地獄の握撃》（インスタントの火力が多い）。

| デッキ | ドリルの席 | ドリル側の勝率 | 基準（元のAI同士）での同じ席 | 差 |
|---|---|---|---|---|
| クリーチャーだけ | 白緑 | 62.5%（80試合） | 46.0%（100試合） | **+16.5** |
| クリーチャーだけ | 黒赤 | 57.5%（80試合） | 54.0% | +3.5 |
| 除去入り | 白緑 | 45.7%（300試合） | 51.5%（200試合） | **−5.8** |
| 除去入り | 黒赤 | 65.0%（300試合） | 48.5% | **+16.5** |
| 除去入り（decisive） | 白緑 | 46.0%（100試合） | 51.5% | −5.5 |
| 除去入り（decisive） | 黒赤 | 50.0%（100試合） | 48.5% | +1.5 |

- 両方の席をならした効果：クリーチャーだけ **+10.0ポイント**（標準誤差 5.2）、除去入り **+5.3ポイント**（標準誤差 3.2）。どちらも良くなっていそうだが、統計的にはまだ境界線（z≈1.7〜1.9）
- 除去入りで**黒赤側は大きくプラス（+16.5、z≈3.7）、白緑側はむしろマイナス（−5.8、z≈1.3）**。席によって効き方がはっきり違う
- decisive（勝ちの発見と負けの回避だけ差し替え）は 100試合で差し替え 12〜23回しかなく、効果はほぼ見えない。always の効果は主に「読み切れない局面の評価の差」による差し替えから来ている
- 除去入りでは探索できた割合が 9割以上（盤面が詰まらないため）。1回の判断は平均 30〜150ms

#### 白緑側でマイナスになる理由の仮説（未検証）

- 白緑ドリルが差し替えた場面の約3分の2で、相手（黒赤）は**アンタップの土地を1枚以上残していた**。黒赤はインスタントの火力（《ショック》《稲妻の一撃》）を4枚持っており、基本エンジンはこれを考えない。ブロック後の火力や、こちらのブロッカーを焼いてから攻撃される展開に弱い可能性がある
- 逆に黒赤ドリルの相手（白緑）のインスタントは《噛み砕き》《巨怪の撃滅》の3枚で、戦闘中の不意打ちになりにくい
- 確かめ方の案：「相手にアンタップの土地と手札があるときは decisive だけにする」設定を作って、白緑側の勝率が基準に戻るか見る

### 高速化後（2026-11-16、各40試合）

| 構成（WG / BR） | WG の勝ち | BR の勝ち | ドリル側の勝率 |
|---|---|---|---|
| Default / Default（基準） | 22 | 18 | — |
| Drill / Default | 26 | 14 | 65% |
| Default / Drill | 19 | 21 | 52.5% |

- 基準（前回の20試合と合わせて60試合）は WG 51.7%／BR 48.3%。ドリル側は WG で +13ポイント、BR で +4ポイント、合わせて 80試合で 47勝（58.8%）
- 標準誤差は ±5.5% 程度なので、**良くなっていそうだが、まだ確定とは言えない**（約1.6σ）
- 1試合あたり攻撃判断の約4割で探索できるようになった（前回は約3分の1）。残りの約6割は「クリーチャーが多すぎる」（各陣営 9体以上）。このデッキは除去がなく盤面が詰まりやすいので、実際のリミテッドより大きい盤面が多い
- 差し替えは 40試合で約65回（1試合あたり 1.6回）。うち「勝ちを発見」「負けを回避」は合わせて 6〜10回で、ほとんどは読み切れない局面の評価値の差による差し替え

### 最初の自己対戦（高速化前、各20試合）

| 構成（WG / BR） | WG の勝ち | BR の勝ち | 備考 |
|---|---|---|---|
| Default / Default | 9 | 11 | 基準 |
| Drill / Default | 12 | 8 | 差し替え 24回（判断 309回中、探索できたのは 108回） |
| Default / Drill | 9 | 11 | 差し替え 16回 |
| Default / DrillDecisive | 8 | 12 | 差し替え 8回（勝ちを発見 3、負けを回避 5） |

## 5. 次にやること（案）

1. 白緑側のマイナスの原因を確かめる（相手の構えたマナと手札を考慮して差し替えを控える設定を試す）。その後、インスタント用エンジン（engine2.js）を移植して、相手の火力・トリックを探索に入れる
2. 9体以上の盤面への対応：攻撃の候補を絞る近似探索（その場合は「完全一致」の照合から外れる）
3. always と decisive の比較
4. ブロックの差し替え（`AiBlockController`）

## 6. 実行方法

```sh
mvn -B -pl forge-gui-desktop -am install -DskipTests -Dcheckstyle.skip
cd forge-gui
java -Xmx3g -jar ../forge-gui-desktop/target/forge-gui-desktop-2.0.17-SNAPSHOT-jar-with-dependencies.jar \
  sim -D ../docs/combat-drill/decks -d drill-wg.dck drill-br.dck -n 20 -a Drill Default -mc off -q
```

`-a` は AI プロファイルをデッキと同じ順に指定する。差し替えの中身を見たいときは、プロファイルで `COMBAT_DRILL_VERBOSE=true` にする。CPU 2コアの環境では sim を同時に2本走らせると極端に遅くなるので、1本ずつ回す。
