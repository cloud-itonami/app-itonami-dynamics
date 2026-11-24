# バングラデシュの人口・高齢化 — 最小2ストックモデル (AWAI-3361)

分類: シナリオであって予測ではない。総人口・65歳以上・出生率・死亡率・国際純移民率は
いずれも Bangladesh Bureau of Statistics (BBS) の公的系列で揃える。
起点は Population and Housing Census 2022 の列挙人口 (Enumerated)。
高齢化係数は 2011→2022 の両国勢調査の変化から中点法で導出した実測。
老年死亡倍率 3.0 は既存 Tier A/B 共通の感度仮定で実測ではない。

## 必須3シナリオの定義（互いに異なる）

| 本 | 定義 | net-migration | birth-rate |
|---|---|---|---|
| `baseline-2022-rates`（基準） | 2022 実測率固定 | -0.600682 M/年（SVRS実測率の導出） | 0.0198 |
| `no-migration`（純移民ゼロ） | 基準に対する反実仮想の感度 | 0.0（純移民ゼロ） | 0.0198 |
| `lower-fertility`（出生率変化） | 基準に対する出生率感度 | -0.600682 M/年 | 0.01782 (×0.9) |

追加本として `faster-aging`（aging-coef ×1.5、感度）を4本目に置く。

**なぜ基準が純移民を含むか**: バングラデシュの国際純移民はゼロではない
(SVRS 2022: 流入 2.972 / 流出 6.609 per 1000、ネット -3.637 per 1000)。
基準が純移民ゼロと一致しないため、純移民ゼロは別本 (`:no-migration`)
として定義する。基準は「2022 年の実測状態をそのまま固定したもの」であり、
純移民ゼロ本は「もし純移民がなかったら」という反実仮想の感度である。
このため基準と純移民ゼロ本は定義が異なる別本となり、必須3本は互いに異なる。
逆にナイジェリア (AWAI-3358) は基準自体がゼロ仮定だったため基準＝純移民ゼロであった。
高齢化加速本は必須3本の後に追加した感度であり、実測ではない。

## 出典のpin

- 出典1 (起点・年齢構造):
  BBS *Population and Housing Census 2022: National Report (Volume I)*
  (Publication Date: November 2023, Upload: 15-11-2023)。
  Table 3.2.8 (21階級の絶対数)・Table 3.2.10 (0-14/15-64/65+・依存指数52.63)・
  Table 3.1.5 (列挙/調整人口)・Table 3.2.1 (2011総人口)。
- URL: https://objectstorage.ap-dcc-gazipur-1.oraclecloud15.com/n/axvjbnqprylg/b/V2Ministry/o/office-bbs/2024/12/9ce5bd160bb14a1ab1eabe886adddb9a.pdf
- 取得日時: 2026-11-24T11:38:00Z (UTC)
- sha256: `1c315d497629a71df9805b0ff7b3debec033a174dbf8418e4b5912326f86eac6`
  (10,307,919 bytes)

- 出典2 (百分率の照合):
  BBS *Population and Housing Census 2022: Preliminary Report (English)* (August 2022)。
  Table 2.12 (年齢別百分率。65+ 合計 5.89% の照合用)。
- URL: https://objectstorage.ap-dcc-gazipur-1.oraclecloud15.com/n/axvjbnqprylg/b/V2Ministry/o/office-bbs/2024/12/64cfb2e2a63042c9b96acfc276275836.pdf
- 取得日時: 2026-11-24T11:38:00Z (UTC)
- sha256: `d4a36f5b3990fe58ac1eea70dd066a33098142e100c80f36c057b8478fd00e77`
  (36,965,905 bytes)

- 出典3 (出生・死亡・国際移民率):
  BBS *Bangladesh Sample Vital Statistics 2022 (SVRS)*
  (December 2023, SVRS in Digital Platform Project)。
  Key Findings (CBR 19.8 / CDR 5.8 / RNI 1.40%)・Chapter 8 Tables 8.7/8.8
  (国際流入 2.972 / 流出 6.609 per 1000)。
- URL: https://objectstorage.ap-dcc-gazipur-1.oraclecloud15.com/n/axvjbnqprylg/b/V2Ministry/o/office-bbs/2024/12/940e736ebf414618977fd3d4e7b56e45.pdf
- 取得日時: 2026-11-24T11:39:00Z (UTC)
- sha256: `0b55ccb6eb7fee48e1f8898270848f12a8ff16d86495249da65e678cfb03d98a`
  (11,268,203 bytes)

- 出典4 (2011年65+、係数導出用のみ):
  BBS *Population Monograph: Age-Sex Composition of Bangladesh Population*
  (Census 2011 based)。Table-5.1 (000単位の年齢別男女数)。
  2011年65+ = 6,834 thousand のみ使用。
- URL: https://objectstorage.ap-dcc-gazipur-1.oraclecloud15.com/n/axvjbnqprylg/b/V2Ministry/o/office-bbs/2026/7/ae96bbdb-a471-47ad-8fa0-41859e1e2871.pdf
- 取得日時: 2026-11-24T11:38:00Z (UTC)
- sha256: `317ba921dc4ad78e303d75590a9794ff6fd9fa00245cef0aec8099d139ad2bad`
  (34,831,909 bytes)

キャプチャPDFはコミットしない（session scratch のみ。digest のみEDN/MDに記録）。
出典は BBS 公開ページ（bbs.gov.bd）に掲載された公式ファイルのみで、
ログイン・有料・規約違反の取得は行っていない。

照合のみ（モデル入力ではない）:

- WPP 2024 (Tier B): 下記「Tier A/B比較」参照。系列と年の違いとして報告し、調整しない。

## 2ストックの数値表

世界モデル (Tier B WPP2024) と同じ変数名・同じ単位:
総人口・65歳以上は million persons、出生/死亡率は fraction (‰÷1000)、
純移民は million persons / year。

| 変数 | 値 | 単位 (換算前 → 換算後) | 出典系列 |
|---|---|---|---|
| `:p0` | 165.158616 | million persons (165,158,616人 ÷ 1e6) | Census 2022 NatVol1 Table 3.2.8 Total |
| `:e0` | 9.707301 | million persons (9,707,301人 ÷ 1e6) | NatVol1 Table 3.2.8: 65+ 8階級合計 |
| `:birth-rate` | 0.0198 | fraction (19.8‰ ÷ 1000) | SVRS 2022 Key Findings CBR |
| `:death-rate` | 0.0058 | fraction (5.8‰ ÷ 1000) | SVRS 2022 Key Findings CDR |
| `:net-migration` | -0.600682 | million / year ((2.972-6.609)/1000 × p0、導出) | SVRS 2022 Tables 8.7/8.8（率のみ実測。絶対数は導出＝`:unmeasured`） |
| `:aging-coef` | 0.00272885 | 1/year | Census 2011→2022 の65+変化から導出（下記） |
| `:elder-death-multiple` | 3.0 | 倍率 | 感度仮定 (Tier A/B共通、実測ではない) |
| `:dt` / `:sim-years` | 0.05 / 10 | year | Tier A/Bと同一 |

導出（全てコードで計算、値を手書きしない）:
`a = (ΔE/年 + m·d·E中) / (P中−E中)` (Tier Bと同一式の中点法、11.25年化:
2011-03-15 → 2022-06-15)。
入力は Census 2022 列挙人口 (P=165,158,616人、E=9,707,301人) と
Census 2011 列挙人口 (P=144,043,697人、E=6,834,000人)。
`test/bangladesh_check.cljc` が `|stored − derived| < 1e-6` を検証する。

基準年の派生量（コードで再計算）:

- 65+比率: 2022年 5.8776% (Prelim Table 2.12 の65+合計 5.89% と四捨五入で一致)
- 依存人口指数 (0-14+65+)/15-64: 52.63 (NatVol1 Table 3.2.10 の公表 52.63 と一致)
- 老年従属人口指数 E/(15-64): 0.0897、高齢化指数 E/0-14: 0.2055

## 構造

```
P' = (b − d) * P + m
E' = a * (P − E) − me * d * E
```

m=-0.600682、a=0.00272885 (導出値)、me=3.0。1年目は方向性のみ照合する
(列挙人口とSVRS推計・調整人口で水準が異なるため、ブラジル/ナイジェリアと同じ扱い)。

## 計算結果（10年後、2032年）

| シナリオ | 総人口 | 65歳以上 | 高齢化率 |
|---|---|---|---|
| 2022年率固定（基準） | 183.523 | 12.249 | 6.7% |
| 純移民ゼロ | 189.968 | 12.329 | 6.5% |
| 出生1割減 | 179.866 | 12.204 | 6.8% |
| 高齢化加速 (係数1.5倍) | 183.523 | 14.256 | 7.8% |

純移民ゼロ本は基準より10年後 +6.445M 人口が大きい（単純な |m|×10=6.007M より
複利分だけ大きい。`Δ' = (b−d)Δ + Δm` のため）。
出生の差は総人口に 3.657M 効き、高齢化率は出生より係数で決まる。
faster-aging は感度であり実測ではない。

## 整合のassert結果

```sh
bb test/bangladesh_check.cljc      # 22 checks, 0 failures
bb test/tier_b_check.cljc          # 12 PASS, 0 FAIL (BGD登録済、比較数 21)
bb test/examples_edn_check.cljc    # 58 EDN files, 0 failures
```

- `required-scenarios-present` / `no-migration-is-zero` / `lower-fertility-is-birth-x09`:
  必須3シナリオの存在と定義の区別を検証（基準≠純移民ゼロ）。
- `unmeasured-separated`: 絶対数と倍率は `:unmeasured` に分離され、千人率は実測側に残る。
- `raw-groups-sum-to-total`: 2022年21階級合計 165,158,616 = Total、差 0
- `e2022-from-raw`: 65+合計 9,707,301人
- `age-split-matches-natvol1`: 0-14 47,242,685 / 15-64 108,208,630 が Table 3.2.10 と一致
- `published-shares-match`: 65+比率 5.878% / 依存指数 52.63 が公表値と一致
- `aging-coef-derived` / `net-migration-matches`: 格納値と導出値の差 < 1e-6
- `results-reproduced`: Tier Bエンジンで4シナリオ×4チェックポイントが再現
- `bounds-and-ordering`: 基準 P10 > 出生減 P10、純移民ゼロ P10 > 基準、高齢化加速 E10 > 基準
- ネガティブコントロール: 係数改変の検出、e0捏造の検出、rank/score不在
- TDD: まず `bb test/bangladesh_check.cljc` で scenarios EDN 不在の RED
  (UNCOMPUTABLE missing) を確認してから GREEN にした。

Tier A (Census 2022 列挙) とTier B (WPP 2023) の差: P −3.7%、E −10.4%。
系列 (国勢調査列挙 vs WPP) と年の違いを含むため、調整せず報告する。
調整人口 (PEC 169,828,911人) はWPP 2023 (171,466,990人) と −0.95% で近いが、
モデル起点は列挙人口とし、PEC調整・SVRS 7月推計とは水準を一致させていない。

## 再現（相対パスのみで再現できる）

```sh
grep -e ':birth-rate 0.0198' -e ':death-rate 0.0058' \
     -e ':aging-coef 0.00272885' -e ':p0 165.158616' -e ':e0 9.707301' \
     -e ':net-migration -0.600682' \
     examples/bangladesh-population-aging-scenarios.edn
bb oracle/bangladesh_population_oracle_run.cljc \
  > examples/generated/bangladesh-population-aging-results.edn
bb test/bangladesh_check.cljc
```

## oracle bb/Basilisp 一致の記録

- babashka v1.12.218 / Basilisp 0.5.1、macOS arm64、日付 2026-11-24
- `basilisp run oracle/bangladesh_population_oracle_run.cljc` と
  `bb oracle/bangladesh_population_oracle_run.cljc` の出力がバイト一致 (diff なし)。
- 生成ファイル sha256 (bb出力):
  `e895eda4ad91512026c3041ec2a6fcffab7f74bb3501ec6f8fa5b6a793dfcb9e`

## 未取得 / 未検証の一覧 (unverified)

- 絶対純移民数 (M/年) は直接公表されていない。SVRS は千人率 (2.972/6.609)
  のみ公表し、-0.600682M/年は率差 × P0 の導出値として `:unmeasured` に保持する。
  基礎となる千人率自体は実測（公表値）である。
- aging-coef は異なる年の差から出ており、定義差の影響を受けうる。
  2011年65+は BBS Monograph Table-5.1 の千人丸め値 (000単位、70+集約) で、
  2022年は NatVol1 Table 3.2.8 の一人単位 5歳階級値であり、解像度が違う。
  係数 0.00272885 はこの定義差を含む。千人丸め (±最大2,000人) の係数への
  影響は約 ±1.3e-6 と小さいが、定義差の方向は評価していない。
- 出生・死亡率 (SVRS) と人口・年齢構造 (Census) は同一統計局 (BBS) の
  系列だが異なる調査instrumentである (サンプル標本 vs 全数列挙)。
  SVRS率は7月推計人口を分母とし、国勢調査夜人口とは定義が異なる。
- 2011年国勢調査 National Report の一人単位年齢表は取得していない
  (2011 Monograph の千人表で代替)。Prelim の 2011 年百分率からの
  参考値 6,827,671人との差 (~6,300人、0.09%) は確認した。
- 調整人口 (PEC) の水準は起点に使っていない。SVRS 2022 の 2022年7月推計
  (2011基準 171.74M / 2022基準 169.83M) と列挙人口 (165.16M) の差は
  年次・PEC調整・系列の違いを含むため、年1チェックは方向・規模のみである。
- 高齢死亡倍率 3.0 は実測ではなく感度仮定 (Tier A/B共通)。
- `faster-aging` 本は感度であり、実測の将来率ではない。
- SVRS 2022 の CBR は最終版 19.8 (weighted) を使用した。2023-06 の
  プレス発表 19.3 は速報値で最終版に更新済み (SVRS 2023 pocketbook の
  2022年列も 19.8)。速報値を入力に混ぜていない。
- WPP 2024 との差は系列・年の違いとして報告し、調整していない。

## PR

PR: (gh pr create の --head で作成後にURLを投入する)
