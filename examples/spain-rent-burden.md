# スペインの住宅家賃 — 最小2ストックモデル (AWAI-2836)

分類: シナリオであって予測ではない。家賃上昇率は Idealista・Fotocasa
2024実績(二次報道引用)、負担率起点は Eurostat 実測、賃金上昇率と結合
係数は感度仮定。2023-2024年の負担率低下(雇用・構成効果)は未モデル化
の限界として明記する。

## 実測起点

- 2024年: 家賃指数 100 (基準年)。上昇率 Idealista +11.5%/年 (El País引用)、Fotocasa +14%/年 (同社blog)。
- 2024年: 住宅費負担率 16.7% (Eurostat ilc_mded01、全世帯)。2021年18.2%から低下中。
- 貧困ライン以下世帯の負担率は 34.0%、単身世帯は 25.6% (同)。

## 構造

```
R' = gR * R
B' = k * (gR - gW) * B
```

R: 家賃指数、gR: 上昇率。B: 負担率(%)、gW=3.5%、k=0.5 は仮定。
1年目は R≈112.2±4・B≈17.4±3 で照合。

## 計算結果 (10年後、2034年、指数/負担率%)

| シナリオ | 家賃指数 | 負担率 |
|---|---|---|
| Idealista並み | 314.8 | 24.9% |
| Fotocasa並み | 403.5 | 28.2% |
| 沈静化(+5%) | 164.8 | 18.0% |

Fotocasa並みが続くと家賃は4倍、負担世帯は3割に迫る。
沈静化でも負担率は現状より上がる。

## 再現

```
grep -e ':rent-growth 0.115' -e ':rent-growth 0.14' \
     -e ':wage-growth 0.035' -e ':coupling 0.5' \
     -e ':r0 100.0' -e ':b0 16.7' \
     examples/spain-rent-burden-scenarios.edn
basilisp run oracle/spain_rent_oracle_run.cljc \
  > examples/generated/spain-rent-burden-results.edn
```

basilisp と babashka で小数第3位まで一致を確認。
`.cljc` では loop 変数と係数束縛の同名シャドウを避け、
シナリオ名は定義と照合の全箇所で統一すること (AWAI-2811/2819の教訓)。
