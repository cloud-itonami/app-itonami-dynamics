;; AWAI-3358 Nigeria check (babashka or JVM Clojure, stdlib only).
;;   bb test/nigeria_check.cljc
;; Re-derives every number in examples/nigeria-population-aging-scenarios.edn
;; from the transcribed NPC-2022 Annex 3 persons table, asserts total vs
;; age-group sum, and re-runs the published oracle results through the
;; Tier B engine (oracle/tier_b_population.cljc) to prove the same Euler.
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

(def scenarios (read-edn "examples/nigeria-population-aging-scenarios.edn"))
(def published (read-edn "examples/generated/nigeria-population-aging-results.edn"))
(def c (:common scenarios))
(def raw22 (get-in scenarios [:model/raw-annex3-persons :2022]))
(def raw21 (get-in scenarios [:model/raw-annex3-persons :2021]))

;; ---- 1. raw table consistency (persons, exact integer sum) -------------------
(def age-keys [:0-4 :5-9 :10-14 :15-19 :20-24 :25-29 :30-34 :35-39 :40-44
               :45-49 :50-54 :55-59 :60-64 :65-69 :70-74 :75-79 :80+])
(def sum22 (reduce + (map #(get raw22 %) age-keys)))
(check! :raw-groups-sum-to-total (= sum22 (:total raw22))
        (str "2022 groups " sum22 " vs total " (:total raw22) " diff " (- sum22 (:total raw22))))

(def e22 (+ (:65-69 raw22) (:70-74 raw22) (:75-79 raw22) (:80+ raw22)))
(def e21 (+ (:65-69 raw21) (:70-74 raw21) (:75-79 raw21) (:80+ raw21)))
(check! :e2022-from-raw (= e22 6152284) (str "65+ 2022 = " e22 " persons"))
(check! :p2021-total (:total raw21) (str "2021 total " (:total raw21) " persons"))

;; ---- 2. persons -> millions conversion (same variable names/units as WPP) ----
(check! :p0-matches-conversion (== (:p0 c) (/ (:total raw22) 1e6))
        (str "p0 " (:p0 c) "M = " (:total raw22) " / 1e6"))
(check! :e0-matches-conversion (== (:e0 c) (/ e22 1e6))
        (str "e0 " (:e0 c) "M = " e22 " / 1e6"))
(println (str "SHARE\t65+ share 2022 "
              (format "%.4f%%" (* 100.0 (/ (double e22) (double (:total raw22)))))
              " (2021 "
              (format "%.4f%%" (* 100.0 (/ (double e21) (double (:total raw21)))))
              ")"))
(def age0-14 (+ (:0-4 raw22) (:5-9 raw22) (:10-14 raw22)))
(def age15-64 (- (:total raw22) age0-14 e22))
(println (str "OLD-DEP\tE/(15-64) " (format "%.4f" (/ (double e22) (double age15-64)))
              "  E/(P-E) " (format "%.4f" (/ (double e22) (double (- (:total raw22) e22))))
              "  total-dep " (format "%.4f" (/ (double (+ age0-14 e22)) (double age15-64)))))

;; ---- 3. rates and derived aging coefficient (all in code) --------------------
(def b (:birth-rate c)) (def d (:death-rate c)) (def m (:elder-death-multiple c))
(def derived-a (/ (+ (- (double e22) (double e21)) (* m d (double e21)))
                  (double (- (:total raw21) e21))))
(check! :rates-match-npc (and (== b 0.03608) (== d 0.011115))
        (str "b " b " d " d " (CBR 36.08/CDR 11.115 per 1000)"))
(check! :aging-coef-derived (< (Math/abs (- (:aging-coef c) derived-a)) 1e-8)
        (str "stored " (:aging-coef c) " derived " derived-a))
(check! :aging-coef-positive-small (and (pos? derived-a) (< derived-a 0.01))
        (str "a " derived-a " 1/yr"))
(let [delta (- (:total raw22) (:total raw21))
      Implied (- delta (* (- b d) (:total raw21)))]
  (println (str "IMPLIED-NETMIGRATION\t" Implied " persons/yr (M=" (format "%.4f" (/ (double Implied) 1e6)) " M/yr)"))
  (check! :net-migration-zero-justified (< (Math/abs Implied) 50000)
          (str "residual " Implied " persons/yr vs P " (:total raw22))))

;; ---- 4. oracle results reproduced by the Tier B engine -----------------------
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
              share (/ (second base) (first base))]
          (and (> (first base) (first low))
               (> share (/ 6.152284 216.783381))
               (<= (second base) (first base))))
        "baseline P10 > lower-fertility P10 and 65+ share rises")

;; ---- 5. negative controls -----------------------------------------------------
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
        "forged e0 7.0M would not match raw sum 6152284")
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
