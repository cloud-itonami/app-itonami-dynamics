# ナイジェリアの人口・高齢化 — 最小2ストックモデル (AWAI-3358)

分類: シナリオであって予測ではない。総人口・65歳以上・出生率・死亡率は
同一出典系列 (全て National Population Commission 2022年推計、NBS
Demographic Statistics Bulletin 2022 経由) で揃える。純移民は未公表のため
ゼロ仮定 (残差 ~0.010M/年で正当化)。高齢死亡倍率3.0は既存Tier A/B共通の
感度仮定で実測ではない。2006年以来の国勢調査は実施されていないため、
「実測」はNPCの2006センサス起点コーホート推計系列を指す。

## 出典のpin

- 出典: National Bureau of Statistics (Nigeria),
  *Demographic Statistics Bulletin 2022* (FINAL, December 2023)。
  年齢別人口は Annex 3、出生・死亡率は §5.2 Table 7。
  年齢別人口の出典行: National Population Commission, 2022
  (2006年国勢調査起点のコーホート・コンポーネント推計系列)。
- URL: https://nigerianstat.gov.ng/pdfuploads/DEMOGRAPHIC_BULLETIN_2022_FINAL.pdf
- 版: NPC 2022年推計系列 (2018–2022年列、基準年は2022年)
- 取得日時: 2026-11-22T22:15:12Z (UTC)
- sha256: `b1ee55e5952a8d992f64a63adda1af1eb42a81fd0e5659b19b9581cc9812150d`
  (1,493,492 bytes、37ページPDF)

照合のみ (モデル入力ではない):

- DHS Key Indicators (Nigeria DHS 2023–24, PR157):
  https://dhsprogram.com/pubs/pdf/PR157/PR157.pdf
  sha256 `94af6e60f7ed2651d192aee50593786ca5732b844a49f4d6003145d22fd820f8`
  (4,320,224 bytes)。TFR 4.8・CBR 33‰ (調査前3年) のみ照合用に引用。
  系列と期間がNPC推計と異なるため入力に混ぜていない。
- World Bank API (WPP系):
  `SP.POP.TOTL` sha256 `b6b3200bd74b96fd8c7359ccebb9431939fc4f09266ce04f09d5b8e6019255e6`、
  `SP.POP.65UP.TO.ZS` sha256 `7863085add2d94236548619812910426c9fb3b44ca8b8dd53b265af3d262ca00`。
  2022年: 総人口 223,150,896人、65+比率 3.0079%。
  Tier B (WPP2024) との差は系列・年の違いとして報告し、調整しない。

## 2ストックの数値表

世界モデル (Tier B WPP2024) と同じ変数名・同じ単位:
総人口・65歳以上は million persons、出生/死亡率は fraction (‰÷1000)、
純移民は million persons / year。

| 変数 | 値 | 単位 (換算前 → 換算後) | 出典系列 |
|---|---|---|---|
| `:p0` | 216.783381 | million persons (216,783,381人 ÷ 1e6) | NPC 2022 Annex 3 Total |
| `:e0` | 6.152284 | million persons (6,152,284人 ÷ 1e6) | NPC 2022 Annex 3: 65-69+70-74+75-79+80+ |
| `:birth-rate` | 0.03608 | fraction (36.08‰ ÷ 1000) | NPC 2022 Table 7 CBR |
| `:death-rate` | 0.011115 | fraction (11.115‰ ÷ 1000) | NPC 2022 Table 7 CDR |
| `:net-migration` | 0.0 | million / year | 仮定 (下記) |
| `:aging-coef` | 0.002192767 | 1/year | NPC 2021→2022 の65+変化から導出 (下記) |
| `:elder-death-multiple` | 3.0 | 倍率 | 感度仮定 (Tier A/B共通、実測ではない) |
| `:dt` / `:sim-years` | 0.05 / 10 | year | Tier A/Bと同一 |

導出 (全てコードで計算、値を手書きしない):
`a = (E2022 − E2021 + m·d·E2021) / (P2021 − E2021)` (Tier Bと同一式)。
入力はNPC 2021→2022の変化のみ (P2021=211,493,324人、E2021=5,898,135人)。
`test/nigeria_check.cljc` が `|stored − derived| < 1e-8` を検証する。

基準年の派生量 (コードで再計算、Table 7の従属人口比率 0.838 とは定義が
異なるため流用しない):

- 65+比率: 2022年 2.8380% (2021年 2.7888%)
- 老年従属人口指数 E/(15-64): 0.0515、E/(P−E): 0.0292

## 構造

```
P' = (b − d) * P + m
E' = a * (P − E) − me * d * E
```

m=0、a=0.002192767 (導出値)、me=3.0。1年目は方向性のみ照合する
(NPC 2022推計の確定実績値はないため、タイ/インドネシアと同じ扱い)。

## 計算結果 (10年後、2032年)

| シナリオ | 総人口 | 65歳以上 | 高齢化率 |
|---|---|---|---|
| 2022年率固定 | 278.215 | 8.889 | 3.2% |
| 出生1割減 | 268.367 | 8.800 | 3.3% |
| 高齢化加速 (係数1.5倍) | 278.215 | 11.097 | 4.0% |

若い人口構造のため、高齢化率が3%台から10年で大きくは動かない。
入力率の差と同様に、2032年の軌跡はNPC推計系列の延長であって予測ではない。

## 整合のassert結果

```
bb test/nigeria_check.cljc
```

- `raw-groups-sum-to-total`: 2022年17階級合計 216,783,381 = Total、差 0
- `e2022-from-raw`: 65+合計 6,152,284人
- `p0/e0-matches-conversion`: 換算前後が一致
- `aging-coef-derived`: 格納値と導出値の差 < 1e-8
- `results-reproduced`: Tier Bエンジンで3シナリオ×4チェックポイントが再現
- `net-migration-zero-justified`: 残差 10,126人/年 (人口の0.005%)
- ネガティブコントロール: 係数改変の検出、e0捏造の検出、rank/score不在
- 合計 14 checks、0 failures

既存検査 (Tier Bエンジン再現チェックにナイジェリアが加わり、
Tier A/B比較に NGA として追加):

```
bb test/tier_b_check.cljc     # 12 PASS, 0 FAIL
bb test/examples_edn_check.cljc
```

Tier A (NPC 2022) とTier B (WPP 2023) の差: P −4.9%、E −10.8%。
系列 (NPC 2006起点推計 vs WPP) と年の違いを含むため、調整せず報告する。

## 未取得 / 未検証の一覧 (unverified)

- NPC 2006年国勢調査の原資料 (年齢別集計表): 未取得。本文書センターでは
  取得できず、NBS小冊子の140,431,790人の総数引用のみ。モデルには不使用
  (2006センサス自体はNPC 2022推計系列の起点としてNBS小冊子経由で記録)。
- NPC公式サイト (nationalpopulation.gov.ng): HTMLシェルのみ取得
  (sha256 `d08d5f695795f81013416deb8377a7352ce463c8f75ce88f8672b5ef2c71bb0a`)。
  数値表は含まれず、モデルには不使用。
- DHS 2023–24 全報告書の65+総数: Key Indicatorsに全国65+総数は掲載なし。
  DHSは照合のみで、入力に混ぜていない (unverified としてモデリングしない)。
- NBS Annex 7/8 の州別CBR/CDR: 一次書類に取得済みだが、州別人口表が
  同書にないため人口加重全国値にできない。全国値はTable 7を採用。
- 純移民: NPCが全国純移民率を公表していないためゼロ仮定。残差は
  約+10k人/年 (2022年率を2021年基数に適用した場合) で、10年で約0.1M/年
  の感度にとどまる。
- 高齢死亡倍率 3.0: 実測ではなく感度仮定 (Tier A/B共通)。
- 2023年国勢調査: 実施済み実績はなく、最新は2006年 (NBS小冊子に明記)。
  NPC 2022「推計値はセンサス実測ではなく、2006起点の推計である」ため、
  本モデルを「予測」と称しない。

## 再現

```
grep -e ':birth-rate 0.03608' -e ':death-rate 0.011115' \
     -e ':aging-coef 0.002192767' -e ':p0 216.783381' -e ':e0 6.152284' \
     -e ':net-migration 0.0' \
     examples/nigeria-population-aging-scenarios.edn
basilisp run oracle/nigeria_population_oracle_run.cljc \
  > examples/generated/nigeria-population-aging-results.edn
bb test/nigeria_check.cljc
```

basilisp と babashka で小数第3位まで一致を確認。
`.cljc` では loop 変数と係数束縛の同名シャドウを避け、
シナリオ名は定義と照合の全箇所で統一すること (AWAI-2811/2819の教訓)。

## PR

<!-- PR-URL-TO-BE-FILLED-AFTER-CREATION -->

PR作成まで。マージしない。checksが落ちた場合は自分のPRが報告できる範囲で
修正し、報告時に状態を書く。
