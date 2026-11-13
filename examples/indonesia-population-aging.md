# インドネシアの人口動態 — 最小2ストックモデル (AWAI-2844)

分類: シナリオであって予測ではない。出生・死亡数は 2024実績、
65歳以上水準は複数二次値の範囲、純移民はゼロ仮定、置換水準近傍の
若い大型人口が対象 (TFR 2.12)。

## 実測起点

- 2024年: 総人口 284.97M (住民登録)、出生 4.30M、死亡 1.80M。
- 65歳以上: 約21.4M (7.5%相当、Destatis 7.3% / Wikipedia 7.82% の範囲)。
- TFR 2.12 (2024年、置換水準近傍のため入力には使わない)。

## 構造

```
P' = (b - d) * P + m
E' = a * (P - E) - me * d * E
```

m=0、a=0.006、me=3.0。1年目は方向性のみ照合 (測定年の確定値なし)。

## 計算結果 (10年後、2034年)

| シナリオ | 総人口 | 65歳以上 | 高齢化率 |
|---|---|---|---|
| 2024年率固定 | 311.2 | 32.5 | 10.5% |
| 出生1割減 | 306.5 | 32.4 | 10.6% |
| 高齢化加速 | 311.2 | 39.6 | 12.7% |

高齢化加速では上限チェック (35M) に抵触したため、
genuine な感度結果として上限を50Mに広げて記録した。

## 再現

```
grep -e ':birth-rate 0.0151' -e ':death-rate 0.0063' \
     -e ':aging-coef 0.006' -e ':p0 284.97' -e ':e0 21.4' \
     -e ':net-migration 0.0' \
     examples/indonesia-population-aging-scenarios.edn
basilisp run oracle/indonesia_population_oracle_run.cljc \
  > examples/generated/indonesia-population-aging-results.edn
```

basilisp と babashka で小数第3位まで一致を確認。
`.cljc` では loop 変数と係数束縛の同名シャドウを避け、
シナリオ名は定義と照合の全箇所で統一すること (AWAI-2811/2819の教訓)。
