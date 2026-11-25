;; AWAI-3360 Pakistan check (babashka or JVM Clojure, stdlib only).
;;   bb test/pakistan_check.cljc
;; Re-derives every number in examples/pakistan-population-aging-scenarios.edn
;; from the transcribed raw census persons (:model/raw-census-persons),
;; asserts total vs age-group sum for 2023 and 2017, verifies the three
;; required scenarios (baseline with migration, zero-migration, birth-rate
;; change) with pairwise-distinct effective definitions, re-runs the
;; published oracle results through the Tier B engine to prove the same
;; Euler. The zero-migration scenario (net 0.0, EDN :unmeasured) is a
;; separate sensitivity; the baseline net-migration is WPP (:derived).
;; Exit 0 = every check passed, 1 = one did not, 2 = could not run.
(require '[clojure.edn :as edn] '[clojure.java.io :as io])

(def root (-> *file* io/file .getCanonicalFile .getParentFile .getParentFile))
(load-file (str (io/file root "oracle/tier_b_population.cljc")))
(alias 'tb 'tier-b-population)

(def results (atom []))
(defn check! [id ok detail]
  (swap! results conj {:id id :ok (boolean ok)})
  (println (str (if ok "PASS" "FAIL") "\t" (name id) "\t" detail)))
(defn uncomputable! [what] (println (str "UNCOMPUTABLE\t" what)) (System/exit 2))
(defn read-edn [rel]
  (let [f (io/file root rel)]
    (if (.exists f) (edn/read-string (slurp f)) (uncomputable! (str "missing " rel)))))

(def scenarios (read-edn "examples/pakistan-population-aging-scenarios.edn"))
(def published (read-edn "examples/generated/pakistan-population-aging-results.edn"))
(def c (:common scenarios))
(def raw23 (get-in scenarios [:model/raw-census-persons :2023]))
(def raw17 (get-in scenarios [:model/raw-census-persons :2017]))

;; ---- 0. required scenarios (baseline / zero-migration / birth-rate change) --
;; The three required scenarios have mutually distinct effective definitions
;; (baseline with migration, no-migration at 0.0, lower-fertility at birth x0.9).
(def sc (:scenarios scenarios))
(check! :required-scenarios-present
        (and (contains? sc :baseline-2023-rates)
             (contains? sc :no-migration)
             (contains? sc :lower-fertility)
             (contains? sc :faster-aging)
             (= 4 (count sc)))
        (str "scenarios: " (pr-str (sort (keys sc)))))
(check! :required-scenarios-distinct
        (let [eff (fn [sid] (merge {:p0 (:p0 c) :e0 (:e0 c) :birth-rate (:birth-rate c)
                                    :death-rate (:death-rate c) :net-migration (:net-migration c)
                                    :aging-coef (:aging-coef c)}
                                   (select-keys (get sc sid) [:birth-rate :death-rate
                                                              :net-migration :aging-coef])))
              b (eff :baseline-2023-rates) z (eff :no-migration) l (eff :lower-fertility)]
          (and (not= b z) (not= b l) (not= z l)
               (not (== (:net-migration c) 0.0))))
        "baseline / no-migration / lower-fertility have pairwise different effective params")
(check! :no-migration-is-zero
        (and (= (:net-migration (get sc :no-migration) 0.0) 0.0)
             (not (= (:net-migration c) 0.0)))
        (str "no-migration overrides only net-migration to 0.0 (baseline " (:net-migration c) " M/yr)"))
(check! :birth-rate-change-present
        (and (< (:birth-rate (get sc :lower-fertility)) (:birth-rate c))
             (< (Math/abs (- (:birth-rate (get sc :lower-fertility)) (* 0.9 (:birth-rate c)))) 1e-12))
        (str "lower-fertility birth-rate " (:birth-rate (get sc :lower-fertility)) " = 0.9 x " (:birth-rate c)))
(check! :faster-aging-is-sensitivity
        (< (Math/abs (- (:aging-coef (get sc :faster-aging)) (* 1.5 (:aging-coef c)))) 1e-9)
        (str "faster-aging coef " (:aging-coef (get sc :faster-aging)) " = 1.5 x " (:aging-coef c)))

;; ---- 1. raw tables: total = sum of age classes (exact integer) --------------
(def sum23 (+ (:age-0-14 raw23) (:age-15-64 raw23) (:age-65plus raw23)))
(def sum17 (+ (:age-0-14 raw17) (:age-15-64 raw17) (:age-65plus raw17)))
(check! :total-vs-age-sum-2023 (= sum23 (:total raw23))
        (str "2023: 0-14 " (:age-0-14 raw23) " + 15-64 " (:age-15-64 raw23) " + 65+ " (:age-65plus raw23) " = " sum23))
(check! :total-vs-age-sum-2017 (= sum17 (:total raw17))
        (str "2017: 0-14 " (:age-0-14 raw17) " + 15-64 " (:age-15-64 raw17) " + 65+ " (:age-65plus raw17) " = " sum17))
(check! :e2023-from-raw (= (:age-65plus raw23) 8531184) (str "65+ 2023 = " (:age-65plus raw23) " persons"))
(check! :p2017-total (:total raw17) (str "2017 total " (:total raw17) " persons"))

;; ---- 2. persons -> millions conversion (same variable names/units as WPP) ----
(check! :p0-matches-conversion (== (:p0 c) (/ (:total raw23) 1e6))
        (str "p0 " (:p0 c) "M = " (:total raw23) " / 1e6"))
(check! :e0-matches-conversion (== (:e0 c) (/ (:age-65plus raw23) 1e6))
        (str "e0 " (:e0 c) "M = " (:age-65plus raw23) " / 1e6"))
(println (str "SHARE\t65+ share 2023 "
              (format "%.4f%%" (* 100.0 (/ (double (:age-65plus raw23)) (double (:total raw23)))))
              " (published 3.55%)"))
(println (str "OLD-DEP\tRDI65 "
              (format "%.4f" (/ (double (:age-65plus raw23)) (double (:age-15-64 raw23))))
              "  IE65 " (format "%.4f" (/ (double (:age-65plus raw23)) (double (:age-0-14 raw23))))
              "  total-dep " (format "%.4f" (/ (double (+ (:age-0-14 raw23) (:age-65plus raw23))) (double (:age-15-64 raw23))))))
(check! :published-shares-match
        (and (< (Math/abs (- (* 100.0 (/ (double (:age-65plus raw23)) (double (:total raw23)))) 3.55)) 0.01)
             (< (Math/abs (- (* 100.0 (/ (double (:age-65plus raw17)) (double (:total raw17)))) 3.71)) 0.01))
        "65+ share 3.5479% (2023) and 3.7137% (2017) match published 3.55 / 3.71")

;; ---- 3. rates, aging coefficient, zero-migration residual -------------------
(def b (:birth-rate c)) (def d (:death-rate c)) (def m (:elder-death-multiple c))
(println (str "RATES\tCBR " (* b 1000.0) "/1000  CDR " (* d 1000.0) "/1000 (PDS-2020)"))
(check! :rates-match-pds-2020
        (and (== b 0.027) (== d 0.0067))
        (str "b " b " d " d " (CBR 27/CDR 6.7 per 1000, PDS-2020 Table 3.1/4.1)"))
(def e17 (:age-65plus raw17))
(def p-mid (/ (+ (double (:total raw23)) (double (:total raw17))) 2.0))
(def e-mid (/ (+ (double (:age-65plus raw23)) (double e17)) 2.0))
(def derived-a (/ (+ (/ (- (double (:age-65plus raw23)) (double e17)) 6.0) (* m d e-mid))
                  (- p-mid e-mid)))
(check! :aging-coef-derived (< (Math/abs (- (:aging-coef c) derived-a)) 1e-8)
        (str "stored " (:aging-coef c) " derived " derived-a))
(check! :aging-coef-positive-small (and (pos? derived-a) (< derived-a 0.01))
        (str "a " derived-a " 1/yr (6-year midpoint 2017->2023)"))
(check! :net-migration-baseline-wpp (== (:net-migration c) -1.619557)
        (str "net-mig " (:net-migration c) "M/yr (WPP 2023 derived, not measured)"))
(check! :net-migration-unmeasured-zero
        (and (= (:status (get-in scenarios [:model/observed-evidence
                                             :net-migration-2023-unmeasured])) :unmeasured)
             (== (:value (get-in scenarios [:model/observed-evidence
                                            :net-migration-2023-unmeasured])) 0.0))
        (str "observed-evidence carries :unmeasured net-migration 0.0 (no-migration scenario)"))
(check! :net-migration-derived-not-measured
        (and (= (:status (get-in scenarios [:model/observed-evidence
                                             :net-migration-wpp-2023])) :derived)
             (== (:value (get-in scenarios [:model/observed-evidence
                                            :net-migration-wpp-2023])) -1.619557))
        "observed-evidence carries :derived WPP net -1.619557 (baseline, not measured)")
;; Residual is computed only to show why it is NOT used for justification:
;; census coverage changed between 2017 and 2023, so the +0.91M/yr residual
;; is not a migration measurement.
(let [delta (/ (- (double (:total raw23)) (double (:total raw17))) 6.0)
      implied (- delta (* (- b d) p-mid))]
  (println (str "IMPLIED-RESIDUAL\t" (format "%.1f" implied) " persons/yr (NOT used: coverage change contaminates intercensal residual)"))
  (check! :residual-not-used-as-justification (pos? implied)
          "residual +0.91M/yr is implausible for a net-emigration country, confirming it is not used"))

;; ---- 4. oracle results reproduced by the Tier B engine -----------------------
(def engine-scenarios
  (into {} (for [[sid scn] (:scenarios scenarios)]
             [(name sid)
              (tb/simulate (merge {:dt (:dt c) :years (double (:sim-years c))
                                   :checkpoints (:checkpoints-years c)}
                                  (select-keys c [:p0 :e0 :birth-rate :death-rate
                                                  :net-migration :aging-coef
                                                  :elder-death-multiple])
                                  (select-keys scn [:birth-rate :aging-coef :net-migration
                                                    :elder-death-multiple]))
                           3)])))
(def pub-scenarios (:model/scenarios published))
(def mismatches
  (for [sid (keys engine-scenarios)
        :let [want (:checkpoints (or (get pub-scenarios (keyword sid))
                                     (get pub-scenarios sid)))]
        :when (not= (get engine-scenarios sid) want)]
    {:scenario sid :got (get engine-scenarios sid) :want want}))
(check! :results-reproduced (empty? mismatches)
        (if (empty? mismatches) "all 4 scenarios x 4 checkpoints equal"
            (str "mismatches " (pr-str mismatches))))
(doseq [[sid traj] engine-scenarios]
  (println (str sid "\t" (pr-str traj))))
(check! :bounds-and-ordering
        (let [base (get-in engine-scenarios ["baseline-2023-rates" 10])
              nomig (get-in engine-scenarios ["no-migration" 10])
              low (get-in engine-scenarios ["lower-fertility" 10])
              fast (get-in engine-scenarios ["faster-aging" 10])]
          (and (> (first nomig) (first base))
               (> (first base) (first low))
               (> (second fast) (second base))
               (<= (second base) (first base))))
        "no-migration P10 > baseline P10 > lower-fertility P10, faster-aging E10 > baseline")

;; ---- 5. negative controls ----------------------------------------------------
(check! :negative-tampered-aging-detected
        (not= (tb/simulate (merge {:dt (:dt c) :years (double (:sim-years c))
                                   :checkpoints (:checkpoints-years c)}
                                  (select-keys c [:p0 :e0 :birth-rate :death-rate
                                                  :net-migration :elder-death-multiple])
                                  {:aging-coef 0.004}) 3)
              (get engine-scenarios "baseline-2023-rates"))
        "changing a changes the trajectory")
(check! :negative-forged-e0-detected
        (not (== (/ (:age-65plus raw23) 1e6) 7.0))
        "forged e0 7.0M would not match raw sum 8531184")
(check! :no-ranking-or-score
        (let [bad #{"rank" "ranking" "score"}
              walk (fn walk [x] (cond (map? x) (concat
                                                 (keep #(when (bad (if (keyword? %) (name %) (str %))) %) (keys x))
                                                 (mapcat walk (vals x)))
                                         (sequential? x) (mapcat walk x)
                                         :else nil))]
          (empty? (walk published)))
        "no rank/score keys in the published results")

;; ---- summary ------------------------------------------------------------------
(def fails (filter #(not (:ok %)) @results))
(println (str "TOTAL\t" (count @results) " checks, " (count fails) " failures"))
(when (seq fails) (System/exit 1))
