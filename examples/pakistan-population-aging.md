# パキスタンの人口・高齢化 — 最小2ストックモデル (AWAI-3360)

分類: シナリオであって予測ではない。総人口Pと65歳以上Eの2ストック。
起点はPBS第7回国勢調査2023 (2023年3–5月実施、CCI承認2023-08-05) の
実測・詳細系列 (detailed population)。出生・死亡率はPBSの域内人口動態調査
PDS-2020 (2020年基準)。純移民はPBSが全国純移民率を公表しないためゼロ仮定
(基準がそのまま純移民ゼロシナリオを兼ねる)。高齢化係数は2017→2023の両国勢調査の
変化から中点法で導出した実測。高齢死亡倍率3.0はTier A/B共通の感度仮定で実測ではない。

## 出典のpin

- PBS 7th Population and Housing Census 2023, Table 5 (national):
  https://www.pbs.gov.pk/wp-content/uploads/census_tables/tables/table_5_national.pdf
  版: Census 2023 final detailed tables。取得: 2026-11-24T11:36:00Z
  sha256 `b7b38b56c31c294019a704c78ad3535c852d1f6141db42cbf6ecff214278cc71`
  (27,111 bytes)。総人口 240,458,089、UNDER 15 97,533,739、
  15–64 134,393,166、65+ 8,531,184。
- PBS 6th Census 2017, Table 5 (Table05n):
  https://pbs.gov.pk/wp-content/uploads/2020/07/Table05n.pdf
  取得: 2026-11-24T11:37:00Z
  sha256 `bbc2357131f3e230ddc1ecced2685a04b8cbb33b3c17dee5a09ced6868157df1`
  (104,311 bytes)。総人口 207,684,626、15–64 116,255,314、65+ 7,712,816。
- PBS Census 2017, Table 4 (Table04n):
  https://pbs.gov.pk/wp-content/uploads/2020/07/Table04n.pdf
  取得: 2026-11-24T11:37:00Z
  sha256 `a65fd70662d58a4ae44c5ac72604d9f8627d3bdc358cc84055e532a15641a56f`
  (123,837 bytes)。15歳単歳 4,433,568 (2017の0–14残差の検算用)。
- PBS Census 2023 Key Findings Report:
  https://pbs.gov.pk/wp-content/uploads/2020/07/Key_Findings_Report.pdf
  取得: 2026-11-24T11:36:00Z
  sha256 `f322aa885724d91d36b73e01179c3024e2f7282af4861812cc08b6e71b4c217f`
  (26,122,114 bytes)。総数 241,499,431 vs 詳細 240,458,089
  (差 1,041,342 は head-count only、年齢詳細なし)、Table 2.12 年齢シェア。
- PBS Pakistan Demographic Survey 2020 (November 2022):
  https://pbs.gov.pk/wp-content/uploads/2020/07/Pakistan_Demographic_Survey-2020-3.pdf
  版: PDS-2020 (reference year 2020)。取得: 2026-11-24T11:37:00Z
  sha256 `4b97b43273b92811a5190ddf551bb451e210545bc4ff773ae15df45fd958fe27`
  (6,552,734 bytes)。Table 3.1 CBR 27‰、Table 4.1 CDR 6.7‰、RNI 2.0%、TFR 3.7。

capturesはローカルのままコミットしない (本worktree外の /tmp/pak)。

## 2ストックの数値表

世界モデル (Tier B WPP2024) と同じ変数名・同じ単位:
総人口・65歳以上は million persons、出生/死亡率は fraction (‰÷1000)、
純移民は million persons / year。

| 変数 | 値 | 単位 (換算前 → 換算後) | 出典系列 |
|---|---|---|---|
| `:p0` | 240.458089 | million persons (240,458,089人 ÷ 1e6) | PBS Census 2023 Table 5 (detailed) |
| `:e0` | 8.531184 | million persons (8,531,184人 ÷ 1e6) | PBS Census 2023 Table 5 (65 & ABOVE) |
| `:birth-rate` | 0.027 | fraction (27‰ ÷ 1000) | PBS PDS-2020 Table 3.1 (2020) |
| `:death-rate` | 0.0067 | fraction (6.7‰ ÷ 1000) | PBS PDS-2020 Table 4.1 (2020) |
| `:net-migration` | 0.0 | million / year | 仮定 (下記) |
| `:aging-coef` | 0.0013875793 | 1/year | 2017→2023両国勢調査から中点法で導出 (下記) |
| `:elder-death-multiple` | 3.0 | 倍率 | 感度仮定 (Tier A/B共通、実測ではない) |
| `:dt` / `:sim-years` | 0.05 / 10 | year | Tier A/Bと同一 |

導出 (全てコードで計算、値を手書きしない):
`a = (ΔE/6年 + m·d·E中) / (P中 − E中)` (Brazil型の6年中点法、Tier Bの1年式の一般化)。
入力はPBS 2017→2023の変化のみ (P2017=207,684,626人、E2017=7,712,816人)。
`test/pakistan_check.cljc` が `|stored − derived| < 1e-8` を検証する。

基準年の派生量 (コードで再計算):
- 65+比率: 2023年 3.5479% (公表 3.55%、四捨五入で一致)、2017年 3.7137%
- 老年従属人口指数 E/(15–64): 2023年 0.0635 (6.3479 per 100)、高齢化指数 E/(0–14): 8.7469 per 100

### 同一出典系列と混合の注記

- ストック (P・E・年齢階級) は PBS Census の Table 5 系列で2017年と2023年の
  同一表 (detailed population) から取得し、合計は両年とも人口と完全一致する。
  詳細系列の総人口 (2023: 240,458,089) はPBS公表総数 (241,499,431) と異なり、
  差の1,041,342人は年齢詳細がなく head-count のみ (Key Findingsに明記)。
  2ストックの合計整合を保つため詳細系列を起点とし、総数は照合情報として記録する。
- 出生・死亡率は別のPBS調査製品 (PDS-2020、2020年基準) から取る。Census 2023は
  出生・死亡者数を公表しないため、同一年・同一表という単一系列は存在しない。
  出版者 (PBS) は共通だが調査製品と基準年 (2023 vs 2020) は異なる。
  影響: 率は起点より3年古い2020年値であり、2020–2023年の急速な低下/改善は反映されない。
  PDHS 2017–18のCBR 29‰ (PDS-2020 Table 3.1の比較列) は参考値であり入力にはしない。
  WPP2024 (CBR 27.806‰、CDR 6.466‰) とは近いが、系列・年次が異なるため比較のみとし、
  入力に混ぜていない。

### 純移民ゼロ (必須シナリオ)

- 基準の `:net-migration` は 0.0 であり、必須シナリオ「純移民ゼロ」を兼ねる。
  別本は定義しない。`test/pakistan_check.cljc` の `baseline-is-zero-migration`
  で担保する。
- ゼロは実測ではなく仮定。PBSは全国純移民率を公表しない。
  Census 2017→2023の残差 (年平均 ΔP=5.462M − (b−d)·P中=4.549M = +0.914M/年)
  は、純移民の実測として採用できない (後述の通りカバレッジ変化で汚染)。
  チェックは residual が正 (残差として implausible) であることだけを検証し、
  justification としては使わない。

## 構造

```
P' = (b − d) * P + m
E' = a * (P − E) − me * d * E
```

m=0.0、a=0.0013875793 (導出値)、me=3.0。1年目は自然増のみで
人口 +4.93M/年 ( (0.027−0.0067)×240.458M ≈ 4.89Mと同程度)、
65+ +0.15M/年で方向・規模は自然増と一致する (Tier B方針通り水準は調停しない)。

## 必須3シナリオ

| シナリオ | 定義 | 10年後 (2033年) 総人口 | 65歳以上 | 高齢化率 |
|---|---|---|---|---|
| `baseline-2023-rates` (基準=純移民ゼロ) | 2023起点・PDS-2020率固定、m=0 | 294.548 | 10.222 | 3.5% |
| `lower-fertility` (出生率変化) | 出生率×0.9 (0.027→0.0243) | 286.709 | 10.174 | 3.5% |
| `faster-aging` (感度、任意の追加本) | 高齢化係数×1.5 (0.0013876→0.0020814) | 294.548 | 11.828 | 4.0% |

若い人口構造 (65+ 3.5%) のため、10年でも高齢化率は大きくは動かない。
baselineは純移民ゼロと一致するため重複シナリオは作らない。

## 整合のassert結果

```
bb test/pakistan_check.cljc
```

- `required-scenarios-present`: 3シナリオ (baseline/lower-fertility/faster-aging)
- `baseline-is-zero-migration`: 基準 net-migration = 0.0 (必須の「純移民ゼロ」を兼ねる)
- `birth-rate-change-present`: lower-fertility は出生率を×0.9に下げた本
- `total-vs-age-sum-2023`: 0-14 97,533,739 + 15-64 134,393,166 + 65+ 8,531,184 = 240,458,089 (差 0)
- `total-vs-age-sum-2017`: 83,716,496 + 116,255,314 + 7,712,816 = 207,684,626 (差 0)
- `p0/e0-matches-conversion`: 換算前後が一致
- `aging-coef-derived`: 格納値と導出値の差 < 1e-8
- `results-reproduced`: Tier Bエンジンで3シナリオ×4チェックポイントが再現
- ネガティブコントロール: 係数改変の検出、e0捏造の検出、rank/score不在
- 合計 21 checks、0 failures

bb/Basilisp一致記録 (版・日付・sha256):
- babashka 1.12.218、basilisp 0.5.1、2026-11-24
- `bb` と `basilisp run` の出力は小数第3位まで一致 (diff なし、IDENTICAL)
- generated sha256: `c340105863ecd6c78d3312f857fb4e7d2f3cdd8f562dfb6b2a099e51b41ad073`

既存検査 (Tier Bエンジン再現チェックにパキスタンが加わり、
Tier A/B比較に PAK として追加):

```
bb test/tier_b_check.cljc     # 12 PASS, 0 FAIL
bb test/examples_edn_check.cljc # 58 EDN files, 0 failures
```

Tier A (PBS Census 2023) とTier B (WPP 2023) の差: P −2.8% (240.458 vs 247.504)、
E −17.9% (8.531 vs 10.386)。(WPP 2023: CBR 27.806‰、CDR 6.466‰、
net-migration −1.620M/年)。系列 (Census実測 vs WPP推計)、詳細人口 vs 総数、
および年の違いを含むため、調整せず報告する。特に65+の差はWPP推計 (約4.2%) が
Census実測 (3.55%) より高いことに由来する。

## 未取得・未検証の一覧 (unverified)

- パキスタン立憲的にはAJK/GBを含む全国推計: PBS Censusのdetailed表は
  4州+ICT (240.458M) のみ。1,041,342人の年齢詳細がなく、モデルには不使用。
  PBS公表総数 (241.499M) は照合情報として記録した。
- Census 2023の出生・死亡者数: Censusは公表しない。PDS-2020 (2020年) を
  代替とした。年次のずれ (2023 vs 2020) は上記の通り影響を記録した。
- PDHS (Pakistan Demographic and Health Survey) 原報告書: 取得していない。
  PDS-2020 Table 3.1の比較列 (CBR 29‰、2017–18) のみ引用し、入力にはしていない。
- 全国純移民率: PBSが公表していないためゼロ仮定。残差 +0.91M/年は
  2017–2023のカバレッジ変化 (detailed定義の差・1,041,342人) による
  汚染の可能性が高く、移民の実測として使えない。
  WPPの net-migration −1.62M/年のような大きな流出は、純移民ゼロ仮定では
  モデル化されない (baselineは純移民ゼロシナリオでもあるため、その影響は明示する)。
- 高齢死亡倍率 3.0: 実測ではなく感度仮定 (Tier A/B共通)。
- PDS-2020の標準誤差・使用率重み: 報告書の点推定のみを採用し、誤差評価はしていない。
- 2023 censusの単歳表 (Table 4): スカラー37.2M (0–4) の照合には使用したが、
  5歳階級の詳細表 (Table 5) のみをモデル入力とした。

## 再現

```bash
grep -e ':birth-rate 0.027' -e ':death-rate 0.0067' \
     -e ':aging-coef 0.0013875793' -e ':p0 240.458089' -e ':e0 8.531184' \
     -e ':net-migration 0.0' \
     examples/pakistan-population-aging-scenarios.edn
bb oracle/pakistan_population_oracle_run.cljc \
  > examples/generated/pakistan-population-aging-results.edn
basilisp run oracle/pakistan_population_oracle_run.cljc \
  > /tmp/pak/basil.out && diff /tmp/pak/basil.out \
  examples/generated/pakistan-population-aging-results.edn
bb test/pakistan_check.cljc
```

`.cljc` では loop 変数と係数束縛の同名シャドウを避け、
シナリオ名は定義と照合の全箇所で統一すること (AWAI-2811/2819の教訓)。

## PR

PR: (作成後に追記)
