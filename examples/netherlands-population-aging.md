# オランダの人口・高齢化 — 最小2ストックモデル (AWAI-2848)

分類: シナリオであって予測ではない。出生・死亡・総人口は CBS 2024実績、
65歳以上水準は二次集計、純移民は残差、高齢化係数と高齢死亡倍率は感度仮定。
EU高出生(1.42)と低出生の橋渡し。

## 実測起点 (CBS)

- 2024年: 総人口 18.05M、出生 0.165M、死亡 0.172M、自然増減 -0.0065M。
- TFR 1.43 (2025年1.42)。65歳以上: 約3.79M (21%相当、二次集計)。
- 純移民 +0.1095M/年 (総増103千と自然増減-6.5千の残差)。

## 構造

```
P' = (b - d) * P + m
E' = a * (P - E) - me * d * E
```

m=0.1095、a=0.014、me=3.0。1年目は総人口と高齢人口で照合。

## 計算結果 (10年後、2034年)

| シナリオ | 総人口 | 65歳以上 | 高齢化率 |
|---|---|---|---|
| 2024年率固定 | 19.08 | 4.60 | 24.1% |
| 移民半減 | 18.53 | 4.57 | 24.6% |
| 高齢化加速 | 19.08 | 5.06 | 26.5% |

## 再現

```
grep -e ':birth-rate 0.00914' -e ':death-rate 0.0095' \
     -e ':aging-coef 0.014' -e ':p0 18.05' -e ':e0 3.79' \
     -e ':net-migration 0.1095' \
     examples/netherlands-population-aging-scenarios.edn
basilisp run oracle/netherlands_population_oracle_run.cljc \
  > examples/generated/netherlands-population-aging-results.edn
```

basilisp と babashka で小数第3位まで一致を確認。
`.cljc` では loop 変数と係数束縛の同名シャドウを避け、
シナリオ名は定義と照合の全箇所で統一し、
emit 文の文字列結合ミスに注意すること (AWAI-2811/2819/2845の教訓)。
