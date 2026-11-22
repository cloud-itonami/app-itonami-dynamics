;; AWAI-3359 Brazil check (babashka or JVM Clojure, stdlib only).
;;   bb test/brazil_check.cljc
;; Re-derives every number in examples/brazil-population-aging-scenarios.edn
;; from the transcribed raw census persons (:model/raw-census-persons),
;; asserts total vs age-group sum, and re-runs the published oracle results
;; through the Tier B engine (oracle/tier_b_population.cljc) to prove the
;; same Euler. :age-15-64 is stated as a computed residual in the EDN, so
;; this check re-pulls it independently from the official 0-14 and 65+
;; absolutes (never from the residual itself) and requires equality.
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

(def scenarios (read-edn "examples/brazil-population-aging-scenarios.edn"))
(def published (read-edn "examples/generated/brazil-population-aging-results.edn"))
(def c (:common scenarios))
(def raw22 (get-in scenarios [:model/raw-census-persons :2022]))
(def raw10 (get-in scenarios [:model/raw-census-persons :2010]))

;; ---- 1. raw table: total = sum of age classes (tolerance + exact) ------------
;; :age-15-64 is recomputed from the official parts, not trusted from the EDN.
(def age0-14 (:age-0-14 raw22))
(def age65 (:age-65plus raw22))
(def age15-64 (- (:total raw22) age0-14 age65))
(def sum22 (+ age0-14 age15-64 age65))
(def rel-tol (/ (Math/abs (double (- sum22 (:total raw22)))) (double (:total raw22))))
(check! :total-vs-age-sum-tolerance (< rel-tol 1e-9)
        (str "sum " sum22 " vs total " (:total raw22) " rel.dev " rel-tol))
(check! :total-vs-age-sum-exact (= sum22 (:total raw22))
        (str "2022: 0-14 " age0-14 " + 15-64 " age15-64 " + 65+ " age65 " = " sum22))
(check! :age-15-64-residual-matches
        (= age15-64 (:age-15-64 raw22))
        (str "recomputed " age15-64 " vs EDN residual " (:age-15-64 raw22)))
(check! :age-15-64-evidence-matches
        (< (Math/abs (- (* (:value (get-in scenarios [:model/observed-evidence :age-15-64])) 1e6) age15-64)) 0.001)
        "observed-evidence 140.782394M = recomputed residual")
(check! :e2022-from-raw (= age65 22169101) (str "65+ 2022 = " age65 " persons"))
(check! :p2010-total (:total raw10) (str "2010 total " (:total raw10) " persons"))

;; ---- 2. persons -> millions conversion (same variable names/units as WPP) ----
(check! :p0-matches-conversion (== (:p0 c) (/ (:total raw22) 1e6))
        (str "p0 " (:p0 c) "M = " (:total raw22) " / 1e6"))
(check! :e0-matches-conversion (== (:e0 c) (/ age65 1e6))
        (str "e0 " (:e0 c) "M = " age65 " / 1e6"))
(println (str "SHARE\t65+ share 2022 "
              (format "%.4f%%" (* 100.0 (/ (double age65) (double (:total raw22)))))
              " (published 10.9%)"))
(println (str "OLD-DEP\tRDI65 " (format "%.4f" (/ (double age65) (double age15-64)))
              "  IE65 " (format "%.4f" (/ (double age65) (double age0-14)))
              "  total-dep " (format "%.4f" (/ (double (+ age0-14 age65)) (double age15-64)))))
(check! :published-shares-match
        (and (< (Math/abs (- (* 100.0 (/ (double age65) (double (:total raw22)))) 10.9)) 0.05)
             (< (Math/abs (- (* 100.0 (/ (double age65) (double age0-14))) 55.2)) 0.1))
        "65+ share 10.916% and aging index 55.244 match published 10.9 / 55.2")

;; ---- 3. rates and derived aging coefficient (all in code) -------------------
(def b (:birth-rate c)) (def d (:death-rate c)) (def m (:elder-death-multiple c))
(def cbr (* 1000.0 (/ (double (:births raw22)) (double (:total raw22)))))
(def cdr (* 1000.0 (/ (double (:deaths raw22)) (double (:total raw22)))))
(println (str "RATES\tCBR " (format "%.4f" cbr) "/1000  CDR " (format "%.4f" cdr) "/1000"))
(check! :rates-match-registro-civil
        (and (< (Math/abs (- b (/ (double (:births raw22)) (double (:total raw22))))) 1e-8)
             (< (Math/abs (- d (/ (double (:deaths raw22)) (double (:total raw22))))) 1e-8))
        (str "b " b " d " d " = births/deaths over census total"))
(def e10 (:age-65plus raw10))
(def p-mid (/ (+ (double (:total raw22)) (double (:total raw10))) 2.0))
(def e-mid (/ (+ (double age65) (double e10)) 2.0))
(def derived-a (/ (+ (/ (- (double age65) (double e10)) 12.0) (* m d e-mid))
                  (- p-mid e-mid)))
(check! :aging-coef-derived (< (Math/abs (- (:aging-coef c) derived-a)) 1e-6)
        (str "stored " (:aging-coef c) " derived " derived-a))
(check! :aging-coef-positive-small (and (pos? derived-a) (< derived-a 0.01))
        (str "a " derived-a " 1/yr (decade midpoint)"))
(check! :net-migration-matches
        (== (:net-migration c) (/ (double (:net-migration raw22)) 1e6))
        (str "net-mig " (:net-migration c) "M/yr = " (:net-migration raw22) " persons/yr"))

;; ---- 4. oracle results reproduced by the Tier B engine ---------------------
(def engine-scenarios
  (into {} (for [[sid sc] (:scenarios scenarios)]
             [(name sid)
              (tb/simulate (merge {:dt (:dt c) :years (double (:sim-years c))
                                   :checkpoints (:checkpoints-years c)}
                                  (select-keys c [:p0 :e0 :birth-rate :death-rate
                                                  :net-migration :aging-coef
                                                  :elder-death-multiple])
                                  (select-keys sc [:birth-rate :aging-coef :net-migration
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
        (if (empty? mismatches) "all 3 scenarios x 4 checkpoints equal"
            (str "mismatches " (pr-str mismatches))))
(doseq [[sid traj] engine-scenarios]
  (println (str sid "\t" (pr-str traj))))
(check! :bounds-and-ordering
        (let [base (get-in engine-scenarios ["baseline-2022-rates" 10])
              low (get-in engine-scenarios ["lower-fertility" 10])
              fast (get-in engine-scenarios ["faster-aging" 10])
              share (/ (second base) (first base))]
          (and (> (first base) (first low))
               (> share (/ 22.169101 203.080756))
               (> (second fast) (second base))
               (<= (second base) (first base))))
        "baseline P10 > lower-fertility P10, faster-aging E10 > baseline, 65+ share rises")

;; ---- 5. negative controls ----------------------------------------------------
(check! :negative-tampered-aging-detected
        (not= (tb/simulate (merge {:dt (:dt c) :years (double (:sim-years c))
                                   :checkpoints (:checkpoints-years c)}
                                  (select-keys c [:p0 :e0 :birth-rate :death-rate
                                                  :net-migration :elder-death-multiple])
                                  {:aging-coef 0.004}) 3)
              (get engine-scenarios "baseline-2022-rates"))
        "changing a changes the trajectory")
(check! :negative-forged-e0-detected
        (not (== (/ age65 1e6) 7.0))
        "forged e0 7.0M would not match raw sum 22169101")
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
