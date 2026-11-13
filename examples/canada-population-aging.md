# カナダの人口・高齢化 — 最小2ストックモデル (AWAI-2852)

分類: シナリオであって予測ではない。出生は StatCan 2024実績、
死亡は2024年推計、純移民は2024年実績、高齢化係数と高齢死亡倍率は感度仮定。
高移民国の事例。

## 実測起点 (StatCan)

- 2024年末: 総人口 41.529M、出生 0.366M、死亡 0.340M、TFR 1.25。
- 純移民 +0.725M/年 (全年成長の97.3%)。
- 65歳以上: 約8.1M (2024年末、19.5%相当)。

## 構造

```
P' = (b - d) * P + m
E' = a * (P - E) - me * d * E
```

m=0.724、a=0.016、me=3.0。1年目は2025年水準で照合。

## 計算結果 (10年後、2034年)

| シナリオ | 総人口 | 65歳以上 | 高齢化率 |
|---|---|---|---|
| 2024年率固定 | 49.05 | 11.39 | 23.2% |
| 移民半減 | 45.42 | 11.14 | 24.5% |
| 高齢化加速 | 49.05 | 12.54 | 25.6% |

## 再現

```
grep -e ':birth-rate 0.00881' -e ':death-rate 0.00819' \
     -e ':aging-coef 0.016' -e ':p0 41.529' -e ':e0 8.10' \
     -e ':net-migration 0.724' \
     examples/canada-population-aging-scenarios.edn
basilisp run oracle/canada_population_oracle_run.cljc \
  > examples/generated/canada-population-aging-results.edn
```

basilisp と babashka で小数第3位まで一致を確認。
`.cljc` では loop 変数と係数束縛の同名シャドウを避け、
シナリオ名は定義と照合の全箇所で統一し、
emit 文の文字列結合ミスに注意すること (AWAI-2811/2819/2845の教訓)。
