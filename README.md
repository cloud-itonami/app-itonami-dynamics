# dynamics

Stock-flow-loop system dynamics primitives and Donella Meadows leverage-point
scoring, as a portable `.cljc` library. No orchestration prefix (per
`kotoba-lang/loop-ux-kaizen`'s `resources/repository-rules.edn` taxonomy): this
repository owns the domain math only, the same way `design-quality` or
`hinshitsu` own their domains. The continuous observe/evaluate/decide/act
orchestrator that runs this math against real entity data lives in
[`kotoba-lang/loop-system-dynamics`](https://github.com/kotoba-lang/loop-system-dynamics).

## Why this exists

`ADR-2607203000` (com-junkawasaki/root) needed to answer two related
questions with real numbers instead of vibes:

1. Where in a growth loop's stock-flow structure does an intervention have
   outsized leverage, per Meadows' 1999 "Leverage Points" hierarchy?
2. Structurally -- not morally -- why do extractive systems (surveillance
   ad-tech, speculative derivatives, aggressive recruitment) so often
   out-compete value-aligned commons/mutual-aid systems?

Both questions turn out to need the same primitives: stocks, flows, loops,
and a transparent, auditable scoring formula over them.

## Design principle: no entity is categorically out of scope

The schema admits any organization or system as an entity given the minimum
stock+flow facts. What is finite at any point in time is which entities have
been fed real, dated, sourced data -- not which entities the model can
represent. Coverage grows as more entities are observed; it is never capped
by policy. See `ADR-2607203000` for the full statement of this as a
repo-wide rule, not just a design note here.

"Computed" always means instantiated against real facts. This library never
fabricates a number and presents it as measured -- see
`dynamics.core/loop-structural-strength`, which returns `nil` rather than a
guessed value when a loop's cycle time has never actually been observed.

## Usage

```clojure
(require '[dynamics.core :as d])

(d/leverage-score {:id :reframe-goal :band :band/A :tractability 0.8})
;; => {:id :reframe-goal, :band :band/A, :tractability 0.8, :base-score 8.0}

(d/compare-archetypes)
;; => {:ranked [[:speculative-crypto-derivatives 5.2e8] ...]
;;     :unmeasured [:etzhayyim-adherent-loop]}
```

## This workspace's own loops are in the catalog too (2026-08-06)

`loop-archetypes` is not only a set of external comparators. Four entries are
loops owned by this workspace, cited to dated files under
`90-docs/business/metrics/`:

| entry | one cycle would be | strength |
|---|---|---|
| `:nexus-x402-facilitator-take-rate-current` | a settlement | `nil` |
| `:net-kotobase-subscription-current` | a paid workspace | `nil` |
| `:cloud-itonami-saas-current` | a paying org | `nil` |
| `:cloud-murakumo-credits-current` | a credits purchase | `nil` |

All four are `nil` rather than a low number, because
`loop-structural-strength` refuses to compute a cycle time that has never been
observed. **The four nils do not have the same cause**, and the entries are
kept separate so that the difference survives:

- **x402** — the settle leg is *rejecting*: 3 submissions, 3 rejections,
  0 settlements. A wiring result, not a demand result. Separately, its take
  rate on internal sellers is 0 *by design*, so repairing the rejections would
  make payments work without making the loop compound.
- **kotobase** — the signup leg fired (0 → 12); checkout is 0/12. The loop
  advanced one stage and stopped at the next.
- **itonami** — checkout is verified live end to end and 5 external tenants
  have never opened it. Nothing is broken; nobody is buying.

Each note quotes a 95% upper bound from `upper-bound-rate-from-zero-events`,
and `own-loops-upper-bounds-are-computed-not-asserted-test` recomputes every
quoted figure so the prose cannot drift from the function that produced it.
Read those bounds as *how little has been tested* — 0 of 5 bounds the rate at
45.1% because 5 trials is almost no evidence, not because the product is
half-likely to convert.

## Real simulation and structural modeling: `dynamics.xmile` / `dynamics.sysml`

**Correction (2026-07-21):** `kotoba-lang/org-oasis-open-xmile` (OASIS XMILE
1.0, a real, ADR-authoritative system-dynamics engine with an actual
Euler/RK4 simulator) already existed, and was already the designated
computational substrate for system dynamics in kotoba-lang (`ADR-2607072350`,
2026-07-07), when this repository's own `stock`/`flow`/`loop*`/
`loop-structural-strength` were built two weeks later (`ADR-2607203000`,
2026-07-20) without checking for it first -- exactly the "did you check for
existing infrastructure before building new" failure mode this workspace's
own AGENTS.md repeatedly warns about elsewhere (BMC/Lean Loop tracking,
design-quality scoring, coscientist loops). `loop-structural-strength`
itself stays (it is a genuinely different, cheaper question -- comparative
ranking from 4 coarse parameters, no full equation model needed per
archetype), but any caller who wants an actual projected TRAJECTORY over
time, not a single comparative score, should use `dynamics.xmile` instead of
inventing another one-off simulator.

- **`dynamics.xmile`** -- `acquisition-model` builds a real XMILE stock/flow
  model (a constant real inflow rate feeding a stock through a constant
  real conversion rate) that `xmile.execute/run` (from
  `kotoba-lang/org-oasis-open-xmile`) actually simulates; `project` returns
  a small summary at chosen checkpoint days.
- **`dynamics.sysml`** -- `acquisition-system` builds the STRUCTURAL
  counterpart, real OMG SysML v2 (via `kotoba-lang/org-omg-sysmlv2`):
  Source/Conversion/Sink parts wired by real connections, with real,
  traceable `RequirementUsage`s (e.g. citing an actual Charter clause) that
  `sysml.validate` checks structurally.

Both are thin, honest convenience layers: they take the needed builder fns
from the real standard library as a map argument (`xmile-model-ns`/
`sysml-model-ns`), rather than re-implementing or hard-depending on them,
so this repo's own `deps.edn` stays dependency-free and callers wire the
real libraries in via `--classpath` (see CI workflow for the exact sibling
checkout + classpath shape).

```clojure
(require '[dynamics.xmile :as dx] '[xmile.model :as m] '[xmile.execute :as execute])

(def xmile-ns {:model m/model :sim-specs m/sim-specs :aux m/aux :flow m/flow
               :stock m/stock :add-variable m/add-variable})

(def projection
  (dx/project execute/run
              (dx/acquisition-model xmile-ns {:name "acq" :inflow-rate 264 :conversion-rate 0.000178
                                               :initial-stock 1 :sim-days 3650})
              [365 1825 3650]))
;; => {:initial 1, :checkpoints {365 18.2, 1825 87.0, 3650 172.8}}
```

`dynamics.xmile` also has `percentage-rate-model`, the complementary shape:
not a constant ADDITIVE inflow (`acquisition-model`), but a constant
PROPORTIONAL/exponential rate applied to the stock itself (`Stock' = Stock
* Annual_Rate`, rate may be negative) -- the right shape for a real,
already-measured year-over-year percentage change on a stock/level, as
opposed to a flow feeding an accumulator. Using the wrong shape for a given
real fact is a modeling error even when both "run". Note this integrates
the CONTINUOUS exponential closed form (`S0 * e^(rt)`), not discrete annual
compounding (`S0 * (1+r)^t`) -- the two diverge slightly (e.g. 10 years at
-4.94%/yr: continuous gives 14.10, discrete compounding gives 13.92) and
callers should be aware of which one they're getting.

```clojure
;; real 2025->2026 ACA marketplace enrollment: 24.3M -> 23.1M, -4.938%/yr
(def rate (- (/ 23.1 24.3) 1))
(def decline (dx/percentage-rate-model xmile-ns {:name "aca" :initial-stock 23.1 :annual-rate rate :sim-years 30}))
(dx/project execute/run decline [10])           ;; => {:checkpoints {10 14.1}} (millions)
(dx/crossing-year execute/run decline rate 12.15) ;; => 13.1 (years to fall below half the 2025 peak)
```

`dynamics.xmile` also has `bass-diffusion-model`, a THIRD real shape: Frank
Bass's 1969 innovation-diffusion model (`Adoptions' = (p + q*Stock/M) *
(M - Stock)`), the standard textbook model for adoption spreading through a
bounded population via an EXTERNAL channel (`p`, e.g. broadcast reach) and
an INTERNAL/imitation channel (`q`, existing adopters reaching new ones --
word-of-mouth, or, structurally analogous, one evangelized agent/node
reaching another). `p` alone decelerates from t=0 like `acquisition-model`;
a nonzero `q` produces the classic S-curve (slow start, accelerating
middle, saturation). Verified against Bass's own closed-form solution
(`A(t) = M * (1-e^-(p+q)t) / (1+(q/p)e^-(p+q)t)`) to 3+ decimal precision in
tests. **This function does not supply real `p`/`q`** -- for a loop that
has never fired (no measured adoption data exists), callers MUST label
`p`/`q` as an explicit SCENARIO, never as a claimed real rate, same
discipline as `acquisition-model`'s own docstring on feedback extensions.

```clojure
(def bass (dx/bass-diffusion-model xmile-ns {:name "adoption" :market-size 1000
                                              :p-coefficient 0.03 :q-coefficient 0.38
                                              :initial-adopters 0 :sim-time 20}))
(dx/project execute/run bass [5 10 15])
;; => {:checkpoints {5 331.2, 10 812.8, 15 971.6}} -- slow, then fast, then saturating
```

## Kotoba stack network effect + entry barrier (XMILE)

`network-effect-barrier-model` adds the missing fourth shape: an executable,
multi-stock model for Kotoba's developer/provider/component loop, the
Itonami↔Murakumo↔Kotobase cross-stack loop, and an entrant attempting to
replicate the moving asset frontier.

The distinction between **owned complements** and an **independent network** is
load-bearing. The 57 owned `capability-*` repos, 465 owned
`cloud-itonami-isic-*` components, and 10 operator fleet nodes raise current
utility and replication work. They do **not** count as independent developers,
publishers, customers, or compute suppliers. Those independent stocks start at
the observed values (currently zero except 5 active external organizations),
then grow only through the explicit scenario flows.

The principal equations are:

```text
Developer_Adoption =
  (external rate + developer network coefficient
   * independent developers * bounded Kotoba complement index)
  * remaining developer market fraction

Organization_Adoption =
  (external rate + stack network coefficient
   * active organizations * bounded full-stack complement index)
  * remaining organization market fraction

Barrier_Asset_Units =
  technical base
  + provider weight * all providers
  + component weight * all components
  + receipt weight * verified receipts
  + organization weight * active organizations
  + node weight * all compute nodes

New_Entrant_Catchup_Years =
  Barrier_Asset_Units / entrant replication throughput
```

Every adoption coefficient, productivity, asset weight, replication throughput,
and dollar cost is caller-supplied. The library has no hidden "moat constant".
`network-effect-summary` compares the network-enabled run with the same model
after setting the three reinforcing coefficients to zero. This makes the
network-attributed uplift explicit instead of reporting all baseline acquisition
as a network effect.

The committed scenario inputs are in
[`examples/kotoba-network-effect-scenarios.edn`](examples/kotoba-network-effect-scenarios.edn).
They combine dated observed starting stocks with named conservative/base/upside
assumptions. The generated OASIS XMILE document contains all three scenarios and
their zero-feedback counterfactuals:

- [`examples/generated/kotoba-network-effect-scenarios.xmile`](examples/generated/kotoba-network-effect-scenarios.xmile)
- [`examples/generated/kotoba-network-effect-results.edn`](examples/generated/kotoba-network-effect-results.edn)

Headline sensitivity result at year 10:

| scenario | independent developers (no-network) | developer uplift | active orgs (no-network) | new-entrant catch-up | new-entrant cost |
|---|---:|---:|---:|---:|---:|
| conservative | 20.38 (19.98) | 1.02× | 10.15 (9.96) | 2.80 years | $8.00M |
| base | 61.10 (49.88) | 1.23× | 16.27 (14.90) | 14.35 years | $38.87M |
| upside | 164.13 (99.50) | 1.65× | 30.38 (24.70) | 115.86 years | $286.07M |

These are **scenario outputs, not forecasts**. In particular, the dollar and
catch-up range is intentionally wide because Kotoba has no observed competitor
replication throughput or independently calibrated network coefficient yet.
Weights also differ across scenarios, so compare the explicit outputs and
not their raw barrier indexes across scenarios.

### Replacing assumptions with measured coefficients

[`examples/kotoba-network-effect-calibration.edn`](examples/kotoba-network-effect-calibration.edn)
is the measurement boundary. It currently says `:unobserved` for all three
reinforcing flows and contains no competitor replication observations. This is
deliberate: zero independent exposure cannot identify a network coefficient.

`calibrate-reinforcing-flow` inverts the exact XMILE flow equation from a
measured interval:

```text
coefficient =
  (observed flow / remaining market fraction - external rate)
  / (average feedback stock * average complement index)
```

It returns `:unidentifiable` when feedback exposure is zero and preserves a
negative raw estimate while bounding the reinforcing-only model coefficient at
zero. `calibrate-entry-replication` estimates throughput and variable cost only
from completed comparable work. `calibrate-network-effect-params` applies only
identified values and lists every scenario assumption it retained, so partial
evidence cannot relabel the whole model as measured.

```clojure
(require '[clojure.edn :as edn]
         '[dynamics.xmile :as dx])

(def calibration
  (edn/read-string (slurp "examples/kotoba-network-effect-calibration.edn")))

;; base-params is the merged :common + :base scenario map.
(dx/calibrate-network-effect-params base-params (:evidence calibration))
;; => {:status :unobserved, :applied {}, :assumptions-retained [...]}
```

Regenerate and validate the six-model XMILE document:

```bash
kbb -Sdeps '{:paths ["src" "examples"]
                  :deps {io.github.kotoba-lang/org-oasis-open-xmile
                         {:local/root "../org-oasis-open-xmile"}}}' \
  -M -m generate-kotoba-network-effect
```

**`dynamics.sysml` also has a second, distinct generic shape**: `fleet-model` +
`add-fleet-requirement`, for a real population of N same-kind members
(rather than `acquisition-system`'s fixed 3 roles) that need per-member
compliance tracing -- e.g. cloud-itonami's 797 per-ISIC/ISCO-code blueprint
repos, each individually either registered in `com-junkawasaki/root`'s
`manifest/west.yml` or not. `fleet-model` builds one PartDefinition with N
PartUsage members nested under a Fleet usage; `add-fleet-requirement`
attaches one shared RequirementDefinition with a per-member RequirementUsage
(subject = that member), adding a SatisfyRequirementUsage only where the
caller's real data says `:satisfied?` is true -- and accepts a real SUBSET
of members for a requirement that legitimately does not apply to all of
them (omitted members get no RequirementUsage at all for that requirement,
keeping "not applicable" structurally distinct from "measured and failing"):

```clojure
(require '[dynamics.sysml :as ds] '[sysml.model :as sm])

(def fleet
  (ds/fleet-model sysml-ns {:fleet-name "CloudItonamiCodes" :member-definition-name "ClassificationBlueprint"
                             :members [{:name "cloud-itonami-isic-6419"} {:name "cloud-itonami-isco-1321"}]}))

(def traced
  (ds/add-fleet-requirement sysml-ns fleet
                             {:name "RegisteredInWorkspace" :req-id "WEST-REG"
                              :members [{:name "cloud-itonami-isic-6419" :satisfied? true}
                                        {:name "cloud-itonami-isco-1321" :satisfied? false}]}))
```

## Test

```bash
# Current dsl-core source authority is .kotoba; use the sibling standards
# libraries while their pinned dsl-core dependency supplies the compiled CLJ
# validation surface.
kbb -Sdeps '{:deps {io.github.kotoba-lang/org-oasis-open-xmile
                        {:local/root "../org-oasis-open-xmile"}
                        io.github.kotoba-lang/org-omg-sysmlv2
                        {:local/root "../org-omg-sysmlv2"}}}' \
  -M:test -e "(require 'dynamics.core-test 'dynamics.xmile-test 'dynamics.sysml-test)
              (let [r (clojure.test/run-tests 'dynamics.core-test
                                              'dynamics.xmile-test
                                              'dynamics.sysml-test)]
                (System/exit (+ (:fail r) (:error r))))"
```

## The scalar decision core in Kotoba

**`kotoba/dynamics_score_core.kotoba` is the authority for this library's score
arithmetic.** It carries `band-weight`, `leverage-score`'s `:base-score` and
`:expected-yield`, `loop-structural-strength`,
`upper-bound-rate-from-zero-events`, `cagr` and `real-growth` with no host math
library underneath it: `pow` is reconstructed from the compiler's bounded
exp/log intrinsics rather than `Math/pow`/`js/Math.pow`, which was the one
place this library rested on a runtime's numerics.

`src/dynamics/core.cljc` remains the **reference implementation and the load
path** -- it is what consumers require, because a `.cljc` is what a JVM and a
JS runtime can both `require`. When the two disagree about a number, **the
kernel is right and the `.cljc` is what gets fixed.**

The `.cljc` is deliberately not deleted. `kotoba-lang/dsl-core` replaced
`problem.cljc` with `problem.kotoba` and left 12 consumer repositories unable
to load for 23 days, because a `.kotoba` is not on anybody's classpath.
Authority moves to the kernel; the load path stays where callers can reach it.

### Running the gate

`dynamics_score_core.mjs` is the compiled `:js-kotoba-v1` artifact, committed
beside its source. The gate imports it under node and compares every case
against the `.cljc`:

```bash
nbb <root>/scripts/fleet-ci/gates/dynamics-score-core-check.cljs .
```

**No JVM is involved at any point in that command** -- it is nbb driving a
restricted ESM module. A JVM is needed only to *produce* the artifact, the way
a compiler is needed to produce a binary:

```bash
amu compile <abs>/kotoba/dynamics_score_core.kotoba --target js \
  --output <abs>/kotoba/dynamics_score_core.mjs
```

The gate binds the artifact to its source by sha256, so a forgotten recompile
fails loudly instead of silently gating an old kernel.

### What the gate checks

1. **artifact ↔ source** -- the `.mjs`'s `sourceDigest` equals the `.kotoba`'s
   sha256.
2. **purity** -- `requiredCapabilities` is empty, and an unreadable field is
   not read as empty.
3. **parity, exact** -- `band-weight`, `leverage-base`, `expected-yield`,
   `loop-structural-strength`, `real-growth`. No epsilon: both sides are IEEE
   operations or a selection, so one ulp is a defect. `loop-structural-strength`
   folds `(* a b c d)` to the left, and regrouping those multiplications is
   algebraically identical and not identical in IEEE.
4. **parity, within 1e-12 relative** -- `pow`, `cagr`,
   `upper-bound-rate-from-zero-events`. The kernel imports no host
   transcendental, so bit equality is not available and claiming it would be a
   lie. The worst error observed is printed every run (currently 7.1e-15).
5. **evidence floor** -- a global case floor plus a per-table ratchet, because
   a global total alone does not catch one table being gutted while the others
   hold the count up.

**What this does not claim.** It does not claim `dynamics.core` runs without a
JVM or a JS engine. `rank-interventions`, `meadows-bands`, `loop-archetypes`,
`compare-archetypes-2d`, `regime-changes` and `money-loop-measures` are all
still `.cljc` and all still need a host. What is asserted is narrower and is
exactly what it says: the scalar arithmetic that decides a score has moved to
Kotoba, and the `.cljc` is now checked against it.

## License

MIT.
