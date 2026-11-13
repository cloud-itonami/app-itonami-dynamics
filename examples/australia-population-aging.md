# 豪州の人口・高齢化 — 最小2ストックモデル (AWAI-2853)

分類: シナリオであって予測ではない。出生・死亡は ABS 2024実績、
65歳以上水準は二次集計、高齢化係数と高齢死亡倍率は感度仮定。
高移民国の事例。

## 実測起点 (ABS)

- 2024年9月末: 総人口 27.3M。
- 2024年: 出生 0.292M、死亡 0.187M、自然増 +0.105M。TFR 1.481 (過去最低)。
- 純移民 +0.341M/年。
- 65歳以上: 約4.5M (約16.5%、二次集計)。

## 構造

```
P' = (b - d) * P + m
E' = a * (P - E) - me * d * E
```

m=0.341、a=0.016、me=3.0。1年目は総人口と高齢人口で照合。

## 計算結果 (10年後、2034年)

| シナリオ | 総人口 | 65歳以上 | 高齢化率 |
|---|---|---|---|
| 2024年率固定 | 31.85 | 7.10 | 22.3% |
| 移民半減 | 30.11 | 6.98 | 23.2% |
| 高齢化加速 | 31.85 | 7.88 | 24.7% |

## 再現

```
grep -e ':birth-rate 0.01071' -e ':death-rate 0.00686' \
     -e ':aging-coef 0.016' -e ':p0 27.3' -e ':e0 4.5' \
     -e ':net-migration 0.3408' \
     examples/australia-population-aging-scenarios.edn
basilisp run oracle/australia_population_oracle_run.cljc \
  > examples/generated/australia-population-aging-results.edn
```

basilisp と babashka で小数第3位まで一致を確認。
`.cljc` では loop 変数と係数束縛の同名シャドウを避け、
シナリオ名は定義と照合の全箇所で統一し、
emit 文の文字列結合ミスに注意すること (AWAI-2811/2819/2845の教訓)。
