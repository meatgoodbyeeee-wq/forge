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
| `docs/combat-drill/decks/` | 検証用デッキ（キーワードだけのクリーチャー＋基本土地） |

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
- 各陣営のクリーチャーが `COMBAT_DRILL_MAX_CREATURES`（既定 6）体以下
- 手札・マナ・インスタント・ドローは考えない（基本エンジンの前提のまま）

## 2. 照合結果

`gen.js` で作ったランダム盤面 400 件（1〜4体ずつ、キーワードは全種類、相手のタップあり、maxPly 4〜8）で、全攻撃パターンの評価値が JS 版と **400/400 で完全一致**。`choose()` の最善値も全件一致。

```sh
DRILL_ENGINE=/path/to/combat-drill/src/engine.js node docs/combat-drill/tools/gen.js 400 > cases.txt
javac -d out forge-ai/src/main/java/forge/ai/combatdrill/CombatDrillSolver.java docs/combat-drill/tools/*.java
java -cp out xc.XCheck cases.txt
```

## 3. 速度（Java、ランダム盤面、1回の判断）

| 盤面 | ply 4 | ply 6 | ply 8 |
|---|---|---|---|
| 3対3 | 12ms | 30ms | 89ms |
| 4対4 | 43ms | 0.45s | 1.8s |
| 5対5 | 0.7s | 4.4s（半数が5秒超） | — |
| 6対6 | 4.6s（8割が5秒超） | — | — |

ブロックの全割り当て（ブロッカーごとに「ブロックしない＋攻撃クリーチャーの数」通り）が盤面の大きさで爆発するのが主因。

## 4. 最初の自己対戦（2026-11-16、各20試合、`-mc off`）

デッキ：`drill-wg.dck`（白緑）対 `drill-br.dck`（黒赤）。

| 構成（WG / BR） | WG の勝ち | BR の勝ち | 備考 |
|---|---|---|---|
| Default / Default | 9 | 11 | 基準 |
| Drill / Default | 12 | 8 | 差し替え 24回（判断 309回中、探索できたのは 108回） |
| Default / Drill | 9 | 11 | 差し替え 16回 |
| Default / DrillDecisive | 8 | 12 | 差し替え 8回（勝ちを発見 3、負けを回避 5） |

- 20試合では誤差（±11%程度）の範囲で、まだ効果は言えない
- **攻撃判断の約3分の2が「クリーチャーが多すぎる」で対象外**。終盤の盤面が大きいほど探索できない
- ドリルが見つけた例：相手のライフ 3、こちらに飛行 3体（1/1・1/1・2/3先制）、相手の飛行ブロッカー 1体 → 元の AI は攻撃しなかったが、2ターンで勝ちきれる攻撃を発見

## 5. 次にやること（案）

1. **探索の高速化**（効果が一番大きい）：同じクリーチャーの対称性の除去、ブロック割り当ての枝刈り・並べ替え、オブジェクト生成の削減
2. 試合数を増やして（数百試合、何回かに分けて）勝率差を確かめる
3. always と decisive の比較
4. ブロックの差し替え（`AiBlockController`）
5. インスタント用エンジン（engine2.js）の移植

## 6. 実行方法

```sh
mvn -B -pl forge-gui-desktop -am install -DskipTests -Dcheckstyle.skip
cd forge-gui
java -Xmx3g -jar ../forge-gui-desktop/target/forge-gui-desktop-2.0.17-SNAPSHOT-jar-with-dependencies.jar \
  sim -D ../docs/combat-drill/decks -d drill-wg.dck drill-br.dck -n 20 -a Drill Default -mc off -q
```

`-a` は AI プロファイルをデッキと同じ順に指定する。差し替えの中身を見たいときは、プロファイルで `COMBAT_DRILL_VERBOSE=true` にする。CPU 2コアの環境では sim を同時に2本走らせると極端に遅くなるので、1本ずつ回す。
