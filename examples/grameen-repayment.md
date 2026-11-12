# グラミン返済ループ — 最小2ストックモデル (AWAI-2817)

分類: シナリオであって予測ではない。残高・累計・回収率は実測、
回転率は2025年12月フローに較正、年間実行額は感度仮定。
AWAI-2806 の原型に計算をつけたもの。

## 実測起点 (Grameen公式)

- 2025年12月: 貸付残高 1.42B USD (Annual Report 2025)、累計回収 39.10B USD、回収率 95.53% (Monthly Report Dec'25)。
- 月次フロー: 11→12月 +0.195B (年率約2.34B)。
- 2026年8月: 残高 1.51B USD (公式intro)。緩やかな残高増と整合。

## 構造

```
C = e * t * O
O' = D - C
R' = C
```

O: 残高(B USD)、R: 累計回収(B USD)。e=0.9553実測、t=1.725は月次フローに較正、
D=2.4B/年は感度仮定。定常 O* = D/(e*t) ≈ 1.456B。
1年目は残高1.48±0.15・回収41.44±0.5で照合 (2026年8月実測の方向と一致)。

## 計算結果 (10年後、2034年)

| シナリオ | 残高 | 累計回収 |
|---|---|---|
| 現状継続 | 1.456 | 63.06 |
| 実行拡大 | 1.942 | 70.58 |
| 回収悪化 | 1.546 | 62.97 |

回収悪化では残高が膨らむ一方で累計回収は減る。

## 再現

```
grep -e ':disbursement 2.4' -e ':recovery-rate 0.9553' \
     -e ':turnover 1.725' -e ':o0 1.42' -e ':r0 39.09848' \
     examples/grameen-repayment-scenarios.edn
basilisp run oracle/grameen_repayment_oracle_run.cljc \
  > examples/generated/grameen-repayment-results.edn
```

basilisp と babashka で小数第3位まで一致を確認。
`.cljc` では loop 変数と係数束縛の同名シャドウを避けること (AWAI-2811の教訓)。
