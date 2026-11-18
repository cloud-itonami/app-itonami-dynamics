# Tier B population models (AWAI-3384)

One generated population model per country/area, built by a single procedure
from one panel: UN World Population Prospects 2024, medium variant, base year
2023. These are scenarios, not forecasts. The 18 hand-written national models
in `examples/*-scenarios.edn` (Tier A) remain the deeper source. Where the two
differ, the difference is reported below and not reconciled.

## Result

- 237 countries/areas are in the panel and 236 get a model.
- Coverage is 99.5337% of the WPP world population in 2023. The numerator and
  denominator come from the same panel and the same year.
- No model for Ukraine (0.47% of world population). Its 65+ population is
  smaller in 2023 than in 2022, so the aging coefficient derived below comes out
  negative (−0.00116). It is marked `:aging-unidentified` rather than clamped.

## Files

| File | What it is |
|---|---|
| `wpp2024-tier-b-inputs.edn` | Per-country inputs (millions, per-1000 rates) with the source file names and sha256 digests |
| `wpp2024-tier-b-models.edn` | Generated models: parameters, status and a 10-year baseline at years 0/1/5/10 |
| `../../oracle/tier_b_population.cljc` | Engine and parameter derivation (pure `clojure.core`) |
| `../../tools/tier_b/build_inputs.clj` | Inputs from the two WPP CSV captures (babashka) |
| `../../tools/tier_b/generate.clj` | Models from the inputs (babashka) |
| `../../test/tier_b_check.cljc` | Checks and negative controls (babashka or JVM Clojure) |

## Method

Every model uses the same two-stock Euler integration as the Tier A oracles:

    dP/dt = (b − d)·P + M
    dE/dt = a·(P − E) − m·d·E

The parameters for each country are:

- P0 and E0 are the total and 65+ population on 1 July 2023.
- b and d are the 2023 crude birth and death rates.
- M is net migration in 2023.
- m = 3.0 is the elder death multiple. This is the same assumption the Tier A
  models use, not a measured value.
- a is the aging coefficient. It is derived from the observed change of E
  between 2022 and 2023 under that same assumption:
  a = (E2023 − E2022 + m·d·E2022) / (P2022 − E2022).

Because a is derived from one year of change, it carries the assumption about m
and that year's noise.

## Checks

    bb test/tier_b_check.cljc [--raw <dir with the WPP captures>]

| Check | What it requires |
|---|---|
| `countries-sum-to-world` | The country rows add up to the WPP World row |
| `published-matches-regeneration` | The committed models equal a fresh generation |
| `coverage-at-least-99` | Coverage ≥ 99% |
| `engine-reproduces-tier-a` | The engine gives back every published Tier A result (17 models, 51 scenarios) |
| `tier-a-comparison-listed` | Base-year values compared for all 18 Tier A population models with a national counterpart |
| `no-ranking-or-score` | No rank or score keys |
| `raw-digests-match`, `raw-rebuild-identical` | With `--raw`: the capture digests match, and rebuilding the inputs gives the same bytes |

Five negative controls must fail in the expected way:

- Removing China and India drops coverage to 64.18%.
- An elder multiple of 3.1 breaks the Tier A reproduction.
- A missing birth rate gives `:incomplete`.
- A falling 65+ stock gives `:aging-unidentified`.
- A tampered published model is detected.

The checks were run on babashka 1.12.218 and on JVM Clojure 1.12.5 (Java
27.0.1). Both runs passed and gave identical output. The JVM run used a
directory with only Clojure on its deps, because the repository `deps.edn`
fails to build its classpath (a kotoba `bytes` gitlibs conflict). The US Tier A
model uses a different elder equation (`:elder-growth`), so it is in the
comparison table but not in the engine reproduction.

## Tier A vs Tier B base-year values

Tier A base years are 2024 or 2025 and Tier B is 2023, so each difference mixes
source, definition and one to two years of change. Values are in millions. The
percentage is (Tier A − Tier B) / Tier B.

| Area | P Tier A | P Tier B | ΔP | E Tier A | E Tier B | ΔE |
|---|---:|---:|---:|---:|---:|---:|
| AUS | 27.300 | 26.451 | +3.2% | 4.500 | 4.598 | −2.1% |
| CAN | 41.529 | 39.299 | +5.7% | 8.100 | 7.608 | +6.5% |
| CHN | 1408.280 | 1422.585 | −1.0% | 220.230 | 203.658 | +8.1% |
| DEU | 83.580 | 84.548 | −1.1% | 19.000 | 19.269 | −1.4% |
| EU27 (Tier A europe) | 449.000 | 450.658 | −0.4% | 96.980 | 97.382 | −0.4% |
| FRA | 68.930 | 66.439 | +3.7% | 14.930 | 14.450 | +3.3% |
| GBR | 69.300 | 68.683 | +0.9% | 13.100 | 13.213 | −0.9% |
| IDN | 284.970 | 281.190 | +1.3% | 21.400 | 19.820 | +8.0% |
| ITA | 58.930 | 59.499 | −1.0% | 14.510 | 14.410 | +0.7% |
| JPN | 123.802 | 124.371 | −0.5% | 36.253 | 36.766 | −1.4% |
| KOR | 51.220 | 51.749 | −1.0% | 10.240 | 9.488 | +7.9% |
| NLD | 18.050 | 18.093 | −0.2% | 3.790 | 3.647 | +3.9% |
| SGP (Tier A residents) | 4.181 | 5.789 | −27.8% | 0.753 | 0.758 | −0.7% |
| SWE | 10.588 | 10.551 | +0.3% | 2.180 | 2.167 | +0.6% |
| THA | 65.950 | 71.702 | −8.0% | 7.940 | 10.553 | −24.8% |
| TWN | 23.400 | 23.317 | +0.4% | 4.450 | 4.274 | +4.1% |
| USA | 340.100 | 343.477 | −1.0% | 61.200 | 59.874 | +2.2% |
| VNM | 101.100 | 100.352 | +0.7% | 9.600 | 8.652 | +11.0% |

Okinawa has no national counterpart. India's Tier A model is about
urbanization and has no 65+ stock.

Only one large gap has a known cause. For Singapore, Tier A counts residents,
while WPP counts the total population.

The cause of the Thailand gap (−8.0% P, −24.8% E) has not been investigated. It
is left for review in the Thailand Tier A model's sources.

## Boundaries

There is no personal data, no ranking or score, and no prescription. A missing
or unidentified value is reported as such, never as 0.
