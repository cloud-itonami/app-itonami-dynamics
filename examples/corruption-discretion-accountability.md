# 共産主義の汚職・賄賂が多い原因 — 最小system dynamicsモデル (JUN-1162)

分類: シナリオであって予測ではない。係数は実測ではなく感度仮定。
順序と介入の向きだけを読む。

## 構造 (2ストック)

- C: 汚職取引の占有率 (0..1)。初期値 0.05。
- H: 隠蔽能力 (資金・結束・ノウハウ、0..1)。初期値 0.10。

```
acc_eff  = accountability * (1 - shield * H)
pressure = monopoly * discretion * (1 + norm * C)
dC = b1 * pressure * (1 - C) - b2 * acc_eff * C
dH = a1 * C * (1 - H)      - a2 * accountability * H
```

共通係数 (仮定): b1 0.35, b2 0.50, a1 0.60, a2 0.70。dt 0.05年 x 10年。

## ループ

- R1 隠蔽シールド: C↑ → H↑ → acc_eff↓ → C↑ (強化)。
- R2 常態化: C↑ → pressure↑ → 新規発生↑ → C↑ (強化)。
- B1 摘発: C↑ → 処分↑ → C↓ (均衡)。
- Klitgaard式 (Monopoly + Discretion - Accountability) を発生圧力に落とした。

## なぜ共産主義体制で増幅されるか

単一政党・計画経済は monopoly・discretion を高め、政権交代・独立司法・
独立メディアの欠如は accountability を下げる。R1とR2が同時に回り、
B1だけでは追いつかない。これが体制固有の道徳ではなく構造の帰結。

## 計算結果 (10年後 C, H)

| シナリオ | C | H |
|---|---|---|
| 集中・低説明責任 | 0.939 | 0.835 |
| 改革中間 | 0.430 | 0.433 |
| 多中心・高説明責任 | 0.120 | 0.119 |

dt半分 (0.025) の再計算でも小数第3位まで一致し、順序は不変。

## 介入の優先度 (Meadows換算: 重み x 扱いやすさ)

- 独立監査の分離: band B 7 x 0.5 = 3.5。
- 裁量の縮小・許認可の透明化: 7 x 0.45 = 3.15。
- 目的・パラダイム転換: band A 10 x 0.2 = 2.0 (強いが難しい)。

Awaiの分離設計 (発行・運営・審査・開示の分離) は最初の介入に相当する。

## 再現

```
python3 examples/corruption_oracle_run.py
```

入力は `examples/corruption-discretion-accountability-scenarios.edn`、
出力は `examples/generated/corruption-discretion-accountability-results.edn`。
nbb経路は現行mainで依存解決が壊れており (kotoba.bytesのancestor競合)、
本モデルはその影響を受けない (src未変更)。

## 限界

体制別の実測係数は未投入。数値は体制の断定ではなく、
「集中+裁量+低説明責任が強化ループを回す」という向きの証拠。
