# インドの人口ボーナスと都市化 — 最小2ストックモデル (AWAI-2811)

分類: シナリオであって予測ではない。出生・死亡は SRS 2024 実測、
都市化係数は感度仮定。

## 実測起点

- 2024年: 総人口 1450.936M、都市人口 530.387M (36.6%)、出生率 18.3‰、死亡率 6.4‰ (SRS 2024)。
- 参考: TFR 1.9、15–64歳 68% (2025年)、ピーク約17.0億・2060年代初頭 (UN WPP 2024)。

## 構造

```
P' = (b - d) * P
U' = u * (P - U) + (b - d) * U
```

P: 総人口(百万)、U: 都市人口(百万)。u=0.008 は仮定。
1年目は引用の2025年推定範囲 (1455–1480M) 内を assert。

## 計算結果 (10年後、2034年)

| シナリオ | 総人口 | 都市人口 | 都市化率 |
|---|---|---|---|
| SRS 2024年率固定 | 1634.2 | 677.1 | 41.4% |
| 低出生 | 1597.1 | 661.7 | 41.4% |
| 都市化加速 | 1634.2 | 714.6 | 43.7% |

## 再現

```
grep -e ':birth-rate 0.0183' -e ':birth-rate 0.0160' \
     -e ':urbanization-coef 0.008' -e ':p0 1450.936' \
     -e ':u0 530.387' \
     examples/india-population-urbanization-scenarios.edn
basilisp run oracle/india_population_oracle_run.cljc \
  > examples/generated/india-population-urbanization-results.edn
```

basilisp と babashka で小数第3位まで一致を確認。
移植時の注意: loop 変数と係数束縛の同名シャドウは Basilisp で誤動作した
(係数を `uc` に改名して解決)。`.cljc` では束縛名の重複を避けること。
