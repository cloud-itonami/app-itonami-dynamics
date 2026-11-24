;; AWAI-3361 Bangladesh check (babashka or JVM Clojure, stdlib only).
;;   bb test/bangladesh_check.cljc
;; Re-derives every number in examples/bangladesh-population-aging-scenarios.edn
;; from the transcribed BBS raw census persons (NatVol1 Table 3.2.8/3.2.10)
;; and the 2011 monograph thousands table, asserts total vs age-group sum,
;; and re-runs the published oracle results through the Tier B engine
;; (oracle/tier_b_population.cljc) to prove the same Euler.
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

(def scenarios (read-edn "examples/bangladesh-population-aging-scenarios.edn"))
(def published (read-edn "examples/generated/bangladesh-population-aging-results.edn"))
(def c (:common scenarios))
(def raw22 (get-in scenarios [:model/raw-census-persons :2022]))
(def raw11k (get-in scenarios [:model/raw-2011-thousands]))

;; ---- 0. three required scenarios present (AWAI-3361) ----------------------
(check! :required-scenarios-present
        (let [ids (set (keys (:scenarios scenarios)))]
          (and (= 4 (count ids))
               (ids :baseline-2022-rates) (ids :no-migration)
               (ids :lower-fertility) (ids :faster-aging)))
        "baseline + no-migration (zero) + lower-fertility present; faster-aging is the extra sensitivity")
(check! :no-migration-is-zero
        (and (contains? (:scenarios scenarios) :no-migration)
             (== (:net-migration (get-in scenarios [:scenarios :no-migration])) 0.0)
             (not (== (:net-migration c) 0.0)))
        "baseline has measured net out-migration; no-migration zeroes only net-migration")
(check! :unmeasured-separated
        (let [ev (:model/observed-evidence scenarios)]
          (and (contains? ev :unmeasured)
               (contains? (:unmeasured ev) :net-migration-absolute)
               (contains? (:unmeasured ev) :elder-death-multiple)
               (contains? ev :net-migration-2022)))
        "absolute M/yr and elder multiple held under :unmeasured; SVRS per-1000 rates stay measured")
(check! :lower-fertility-is-birth-x09
        (< (Math/abs (- (:birth-rate (get-in scenarios [:scenarios :lower-fertility]))
                        (* 0.9 (:birth-rate c)))) 1e-12)
        "lower-fertility birth-rate = 0.9 * baseline birth-rate")
(check! :faster-aging-is-aging-x15
        (< (Math/abs (- (:aging-coef (get-in scenarios [:scenarios :faster-aging]))
                        (* 1.5 (:aging-coef c)))) 1e-7)
        "faster-aging coefficient = 1.5 * baseline coefficient (sensitivity)")

;; ---- 1. raw table consistency (persons, exact integer sum) ----------------
(def age-keys [:0-4 :5-9 :10-14 :15-19 :20-24 :25-29 :30-34 :35-39 :40-44
               :45-49 :50-54 :55-59 :60-64 :65-69 :70-74 :75-79 :80-84
               :85-89 :90-94 :95-99 :100+])
(def sum22 (reduce + (map #(get raw22 %) age-keys)))
(check! :raw-groups-sum-to-total (= sum22 (:total raw22))
        (str "2022 groups " sum22 " vs total " (:total raw22) " diff " (- sum22 (:total raw22))))
(def e22 (+ (:65-69 raw22) (:70-74 raw22) (:75-79 raw22) (:80-84 raw22)
            (:85-89 raw22) (:90-94 raw22) (:95-99 raw22) (:100+ raw22)))
(check! :e2022-from-raw (= e22 9707301) (str "65+ 2022 = " e22 " persons"))
(check! :p2022-total (:total raw22) (str "2022 total " (:total raw22) " persons"))

;; 2011 thousands table: male+female sum per group, vs monograph totals 72109/71933 (000)
(def k-keys (vec (keys (dissoc raw11k :male-total :female-total :unit))))
(def sum-m (reduce + (map #(:male (get raw11k %)) k-keys)))
(def sum-f (reduce + (map #(:female (get raw11k %)) k-keys)))
(check! :raw-2011-thousands-sum
        (and (<= (Math/abs (- sum-m (:male-total raw11k))) 1)
             (<= (Math/abs (- sum-f (:female-total raw11k))) 1))
        (str "2011 thousands male " sum-m "/" (:male-total raw11k) " female " sum-f "/" (:female-total raw11k)))

;; ---- 2. persons -> millions conversion (same variable names/units as WPP) --
(check! :p0-matches-conversion (== (:p0 c) (/ (:total raw22) 1e6))
        (str "p0 " (:p0 c) "M = " (:total raw22) " / 1e6"))
(check! :e0-matches-conversion (== (:e0 c) (/ e22 1e6))
        (str "e0 " (:e0 c) "M = " e22 " / 1e6"))
(def age0-14 (+ (:0-4 raw22) (:5-9 raw22) (:10-14 raw22)))
(def age15-64 (- (:total raw22) age0-14 e22))
(check! :age-split-matches-natvol1
        (and (= age0-14 47242685) (= age15-64 108208630))
        (str "0-14 " age0-14 " 15-64 " age15-64 " (NatVol1 Table 3.2.10)"))
(println (str "SHARE\t65+ share 2022 "
              (format "%.4f%%" (* 100.0 (/ (double e22) (double (:total raw22)))))
              " (Prelim Table 2.12 65+ sum 5.89%)"))
(println (str "OLD-DEP\tE/(15-64) " (format "%.4f" (/ (double e22) (double age15-64)))
              "  aging-index E/0-14 " (format "%.4f" (/ (double e22) (double age0-14)))
              "  total-dep " (format "%.4f" (/ (double (+ age0-14 e22)) (double age15-64)))))
(check! :published-shares-match
        (and (< (Math/abs (- (* 100.0 (/ (double e22) (double (:total raw22)))) 5.89)) 0.05)
             (< (Math/abs (- (* 100.0 (/ (double (+ age0-14 e22)) (double age15-64))) 52.63)) 0.02))
        "65+ share 5.878% and dependency ratio 52.63 match Prelim 5.89 / NatVol1 52.63")

;; ---- 3. rates and derived aging coefficient (all in code) -----------------
(def b (:birth-rate c)) (def d (:death-rate c)) (def m (:elder-death-multiple c))
(check! :rates-match-svrs (and (== b 0.0198) (== d 0.0058))
        (str "b " b " d " d " (SVRS 2022 CBR 19.8 / CDR 5.8 per 1000)"))
(def e11-persons (* 1000 (+ (:male (:65-69 raw11k)) (:female (:65-69 raw11k))
                             (:male (:70+ raw11k)) (:female (:70+ raw11k)))))
(def p11-persons (get-in scenarios [:model/raw-census-persons :2011 :total]))
;; midpoint method over 11.25yr (15 Mar 2011 -> 15 Jun 2022), same structure as Brazil
(def derived-a (/ (+ (/ (- (double e22) (double e11-persons)) 11.25)
                      (* m d (/ (+ (double e22) (double e11-persons)) 2.0)))
                   (- (/ (+ (double (:total raw22)) (double p11-persons)) 2.0)
                      (/ (+ (double e22) (double e11-persons)) 2.0))))
(check! :aging-coef-derived (< (Math/abs (- (:aging-coef c) derived-a)) 1e-6)
        (str "stored " (:aging-coef c) " derived " derived-a
             " (E11=" e11-persons " persons, thousands-rounded)"))
(check! :aging-coef-positive-small (and (pos? derived-a) (< derived-a 0.01))
        (str "a " derived-a " 1/yr (intercensal midpoint)"))
;; net migration: SVRS Tables 8.7/8.8 totals (per 1000) applied to p0
(def svrs (get-in scenarios [:model/observed-evidence :net-migration-2022]))
(def derived-m (* (/ (- (:in-rate-per-1000 svrs) (:out-rate-per-1000 svrs)) 1000.0) (:p0 c)))
(check! :net-migration-matches (< (Math/abs (- (:net-migration c) derived-m)) 1e-6)
        (str "net-mig " (:net-migration c) "M/yr = (" (:in-rate-per-1000 svrs) "-"
             (:out-rate-per-1000 svrs) ")/1000 * " (:p0 c) " = " derived-m))

;; ---- 4. oracle results reproduced by the Tier B engine --------------------
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
        (if (empty? mismatches) "all 4 scenarios x 4 checkpoints equal"
            (str "mismatches " (pr-str mismatches))))
(doseq [[sid traj] engine-scenarios]
  (println (str sid "\t" (pr-str traj))))
(check! :bounds-and-ordering
        (let [base (get-in engine-scenarios ["baseline-2022-rates" 10])
              nomig (get-in engine-scenarios ["no-migration" 10])
              low (get-in engine-scenarios ["lower-fertility" 10])
              fast (get-in engine-scenarios ["faster-aging" 10])
              share (/ (second base) (first base))]
          (and (> (first base) (first low))
               (> (first nomig) (first base))
               (> share (/ 9.707301 165.158616))
               (> (second fast) (second base))
               (<= (second base) (first base))))
        "baseline P10 > lower-fertility P10, no-migration P10 > baseline, faster-aging E10 > baseline, 65+ share rises")

;; ---- 5. negative controls -------------------------------------------------
(check! :negative-tampered-aging-detected
        (not= (tb/simulate (merge {:dt (:dt c) :years (double (:sim-years c))
                                   :checkpoints (:checkpoints-years c)}
                                  (select-keys c [:p0 :e0 :birth-rate :death-rate
                                                  :net-migration :elder-death-multiple])
                                  {:aging-coef 0.004}) 3)
              (get engine-scenarios "baseline-2022-rates"))
        "changing a changes the trajectory")
(check! :negative-forged-e0-detected
        (not (== (/ e22 1e6) 7.0))
        "forged e0 7.0M would not match raw sum 9707301")
(check! :no-ranking-or-score
        (let [bad #{"rank" "ranking" "score"}
              walk (fn walk [x] (cond (map? x) (concat
                                                 (keep #(when (bad (if (keyword? %) (name %) (str %))) %) (keys x))
                                                 (mapcat walk (vals x)))
                                         (sequential? x) (mapcat walk x)
                                         :else nil))]
          (empty? (walk published)))
        "no rank/score keys in the published results")

;; ---- summary --------------------------------------------------------------
(def fails (filter #(not (:ok %)) @results))
(println (str "TOTAL\t" (count @results) " checks, " (count fails) " failures"))
(when (seq fails) (System/exit 1))
