# ドイツの人口・高齢化 — 最小2ストックモデル (AWAI-2845)

分類: シナリオであって予測ではない。出生・死亡は 2024確定数、
総人口は Fortschreibung、高齢者数は二次集計、純移民は残差、
高齢化係数と高齢死亡倍率は感度仮定。自然減を移民が相殺する構造。

## 実測起点 (Destatis)

- 2024年12月31日: 総人口 83.58M (Fortschreibung)。
- 2024年: 出生 0.677M、死亡 1.008M、自然増減 -0.331M。TFR 1.35。
- 65歳以上: 約19.0M (約23%。1991年は15%)。
- 純移民 +0.46M/年 (総増と自然増減の残差)。

## 構造

```
P' = (b - d) * P + m
E' = a * (P - E) - me * d * E
```

m=0.46、a=0.015、me=3.0。1年目は総人口と高齢人口で照合。

## 計算結果 (10年後、2034年)

| シナリオ | 総人口 | 65歳以上 | 高齢化率 |
|---|---|---|---|
| 2024年率固定 | 84.85 | 21.29 | 25.1% |
| 移民半減 | 82.59 | 21.14 | 25.6% |
| 高齢化加速 | 84.85 | 23.73 | 28.0% |

移民水準が総人口を動かすが、高齢人数はほぼ変わらない。

## 再現

```
grep -e ':birth-rate 0.00810' -e ':death-rate 0.01206' \
     -e ':aging-coef 0.015' -e ':p0 83.58' -e ':e0 19.0' \
     -e ':net-migration 0.46' \
     examples/germany-population-aging-scenarios.edn
basilisp run oracle/germany_population_oracle_run.cljc \
  > examples/generated/germany-population-aging-results.edn
```

basilisp と babashka で小数第3位まで一致を確認。
`.cljc` では loop 変数と係数束縛の同名シャドウを避け、
シナリオ名は定義と照合の全箇所で統一し、
emit 文の文字列結合ミスに注意すること (AWAI-2811/2819/2845の教訓)。
