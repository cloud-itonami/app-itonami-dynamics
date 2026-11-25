;; AWAI-3384 Tier B check (babashka or JVM Clojure, stdlib only).
;;   bb test/tier_b_check.cljc [--raw <captures-dir>]
;; --raw (babashka only) also rebuilds the inputs from the retained UN WPP
;; captures in a separate bb process and compares byte for byte.
;; Exit 0 = every check and negative control behaved as expected, 1 = one did
;; not, 2 = could not run.
(require '[clojure.edn :as edn] '[clojure.java.io :as io] '[clojure.string :as str] '[clojure.java.shell])

(def root (-> *file* io/file .getCanonicalFile .getParentFile .getParentFile))
(load-file (str (io/file root "oracle/tier_b_population.cljc")))
(alias 'tb 'tier-b-population)

(def results (atom []))
(defn check! [id ok detail]
  (swap! results conj {:id id :ok (boolean ok)})
  (println (str (if ok "PASS" "FAIL") "\t" (name id) "\t" detail)))
(defn uncomputable! [what] (println (str "UNCOMPUTABLE\t" what)) (System/exit 2))
(defn read-edn [rel] (let [f (io/file root rel)] (if (.exists f) (edn/read-string (slurp f)) (uncomputable! (str "missing " rel)))))

(def inputs (read-edn "examples/tier-b/wpp2024-tier-b-inputs.edn"))
(def published (read-edn "examples/tier-b/wpp2024-tier-b-models.edn"))
(def models (tb/generate inputs))
(def world (get-in inputs [:world :p2023]))

;; ---- 1. inputs ---------------------------------------------------------------
(println (str "SCANNED\tcountries\t" (count (:countries inputs)) "\tmodels ok\t" (count (filter #(= :ok (:status (val %))) models))))
(check! :inputs-present (and (tb/finite? world) (pos? (count (:countries inputs)))) (str "world 2023 " world " million"))
(let [s (reduce + (map :p2023 (vals (:countries inputs))))]
  (check! :countries-sum-to-world (< (Math/abs (/ (- s world) world)) 1e-9) (str "countries " s " vs world " world)))
(check! :published-matches-regeneration (= (:models published) models) "the committed models equal a fresh generation from the inputs")

;; ---- 2. coverage against the same panel and year --------------------------------
(let [c (tb/coverage inputs models)]
  (println (str "COVERAGE\t" (format "%.4f%%" (* 100 c)) " of WPP 2024 world population (2023)"))
  (check! :coverage-at-least-99 (>= c 0.99) (format "%.4f%%" (* 100 c))))
(doseq [[iso m] models :when (not= :ok (:status m))]
  (println (str "NO-MODEL\t" iso "\t" (:name m) "\t" (name (:status m)) "\t" (pr-str (:reason m)))))

;; ---- 3. the engine reproduces every published Tier A aging scenario ---------------
(def tier-a
  (for [f (sort (.list (io/file root "examples")))
        :when (str/ends-with? f "-scenarios.edn")
        :let [m (edn/read-string (slurp (io/file root "examples" f)))
              c (:common m)]
        :when (and (:p0 c) (:e0 c) (:aging-coef c))
        :let [id (str/replace f #"-scenarios\.edn$" "")
              rf (io/file root "examples/generated" (str id "-results.edn"))]
        :when (.exists rf)]
    {:id id :scenarios m :results (edn/read-string (slurp rf))}))
(defn decimals-of [results]
  (let [vs (mapcat (fn [s] (mapcat identity (vals (:checkpoints s)))) (vals (:model/scenarios results)))]
    (if (every? #(< (Math/abs (- (* % 10.0) (Math/rint (* % 10.0)))) 1e-9) vs) 1 3)))
(defn tier-a-mismatches [engine-overrides]
  (for [{:keys [id scenarios results]} tier-a
        [sid sc] (:scenarios scenarios)
        :let [c (:common scenarios)
              p (merge {:dt (:dt c) :years (double (:sim-years c)) :checkpoints (:checkpoints-years c) :net-migration 0.0}
                       (select-keys c [:p0 :e0 :birth-rate :death-rate :net-migration :aging-coef :elder-death-multiple])
                       (select-keys sc [:p0 :e0 :birth-rate :death-rate :net-migration :aging-coef :elder-death-multiple])
                       engine-overrides)
              got (tb/simulate p (decimals-of results))
              rs (:model/scenarios results)
              want (:checkpoints (or (get rs sid) (get rs (name sid))))]
        :when (not= got want)]
    {:model id :scenario sid :got got :want want}))
(println (str "SCANNED\ttier-a-models\t" (count tier-a) "\tscenarios\t" (reduce + (map #(count (:scenarios (:scenarios %))) tier-a))))
(check! :engine-reproduces-tier-a (and (pos? (count tier-a)) (empty? (tier-a-mismatches {})))
        (str (count tier-a) " Tier A models, every scenario and checkpoint equal"))

;; ---- 4. Tier A vs Tier B, reported not reconciled ---------------------------------
;; Every Tier A scenario file with a total and a 65+ stock, including the US
;; (a different elder equation, so outside check 3). Europe is compared with the
;; sum of the 27 EU members; Singapore Tier A counts residents in thousands.
;; Tier A base years are 2024 or 2025, Tier B is 2023: the differences mix
;; definitions, sources and one to two years of change.
(def iso-of {"australia" "AUS" "canada" "CAN" "china" "CHN" "france" "FRA" "germany" "DEU" "indonesia" "IDN"
             "italy" "ITA" "japan" "JPN" "korea" "KOR" "netherlands" "NLD" "singapore" "SGP" "sweden" "SWE"
             "taiwan" "TWN" "thailand" "THA" "uk" "GBR" "us" "USA" "vietnam" "VNM" "nigeria" "NGA" "brazil" "BRA" "pakistan" "PAK"})
(def eu27 ["AUT" "BEL" "BGR" "HRV" "CYP" "CZE" "DNK" "EST" "FIN" "FRA" "DEU" "GRC" "HUN" "IRL" "ITA" "LVA" "LTU"
           "LUX" "MLT" "NLD" "POL" "PRT" "ROU" "SVK" "SVN" "ESP" "SWE"])
(defn tier-b-of [id]
  (let [k (first (str/split id #"-"))]
    (cond (= k "europe") (let [cs (map #(get-in inputs [:countries %]) eu27)]
                           (when (every? some? cs) {:label "EU27" :p (reduce + (map :p2023 cs)) :e (reduce + (map :e2023 cs))}))
          (iso-of k) (when-let [b (get-in inputs [:countries (iso-of k)])] {:label (iso-of k) :p (:p2023 b) :e (:e2023 b)}))))
(def comparisons
  (for [f (sort (.list (io/file root "examples")))
        :when (str/ends-with? f "-scenarios.edn")
        :let [m (edn/read-string (slurp (io/file root "examples" f))) c (:common m)
              id (str/replace f #"-scenarios\.edn$" "")
              scale (if (str/starts-with? id "singapore") 0.001 1.0)
              b (tier-b-of id)]
        :when (and (:p0 c) (:e0 c))]
    {:id id :tier-b b :p (* scale (:p0 c)) :e (* scale (:e0 c))}))
(doseq [{:keys [id tier-b p e]} comparisons]
  (if tier-b
    (println (str "TIER-A-VS-B\t" (:label tier-b) "\tP " (format "%.3f" p) " vs " (format "%.3f" (:p tier-b))
                  (format " (%+.1f%%)" (* 100 (/ (- p (:p tier-b)) (:p tier-b))))
                  "\tE " (format "%.3f" e) " vs " (format "%.3f" (:e tier-b))
                  (format " (%+.1f%%)" (* 100 (/ (- e (:e tier-b)) (:e tier-b))))))
    (println (str "TIER-A-VS-B\t" id "\tno Tier B counterpart (subnational)"))))
(check! :tier-a-comparison-listed (= 21 (count (filter :tier-b comparisons)))
        (str (count (filter :tier-b comparisons)) " Tier A population models compared with Tier B"))

;; ---- 5. boundaries -------------------------------------------------------------------
(def forbidden #{"rank" "ranking" "score"})
(defn bad-keys [x] (cond (map? x) (concat (keep #(when (forbidden (if (keyword? %) (name %) (str %))) %) (keys x)) (mapcat bad-keys (vals x)))
                         (sequential? x) (mapcat bad-keys x) :else nil))
(check! :no-ranking-or-score (empty? (bad-keys published)) "no rank/score keys in the published models")

;; ---- 6. raw provenance (only with --raw) ------------------------------------------------
(if-let [raw (second (drop-while #(not= "--raw" %) *command-line-args*))]
  (let [sha (fn [f] (let [md (java.security.MessageDigest/getInstance "SHA-256")]
                      (apply str (map #(format "%02x" %) (.digest md (java.nio.file.Files/readAllBytes (.toPath f)))))))
        caps (get-in inputs [:source :captures])
        rebuilt (io/file root ".agent-output/awai-3384/rebuilt-inputs.edn")
        _ (.mkdirs (.getParentFile rebuilt))
        r (clojure.java.shell/sh "bb" (str (io/file root "tools/tier_b/build_inputs.clj")) raw (str rebuilt))]
    (check! :raw-digests-match (every? #(let [f (io/file raw (:file %))] (and (.exists f) (= (:sha256 %) (sha f)))) caps) (str (count caps) " UN WPP captures"))
    (check! :raw-rebuild-identical (and (zero? (:exit r)) (= (slurp rebuilt) (slurp (io/file root "examples/tier-b/wpp2024-tier-b-inputs.edn"))))
            (str "rebuild exit " (:exit r))))
  (println "RAW-CHECK\tnot run (pass --raw <captures-dir>; babashka only)"))

;; ---- 7. negative controls ------------------------------------------------------------------
(let [without (update inputs :countries dissoc "CHN" "IND")
      c (tb/coverage without (tb/generate without))]
  (check! :neg-coverage-below-target (< c 0.99) (format "without CHN and IND, coverage is %.2f%%" (* 100 c))))
(check! :neg-perturbed-engine (seq (tier-a-mismatches {:elder-death-multiple 3.1})) "a 3.1 elder multiple breaks the Tier A reproduction")
(check! :neg-missing-input (= :incomplete (:status (tb/derive-params (assoc (get-in inputs [:countries "JPN"]) :cbr2023 nil)))) "a missing birth rate gives :incomplete, not a model")
(check! :neg-falling-elders (= :aging-unidentified (:status (tb/derive-params (assoc (get-in inputs [:countries "JPN"]) :e2023 30.0)))) "a fall of the 65+ stock gives :aging-unidentified, not a clamped value")
(check! :neg-tampered-published (not= (assoc-in (:models published) ["JPN" :baseline 10] [0.0 0.0]) models) "a tampered published model is detected")

(let [rs @results fails (remove :ok rs)]
  (println (str "SUMMARY\t" (count (filter :ok rs)) " PASS\t" (count fails) " FAIL\t" (count rs) " total"))
  (System/exit (if (seq fails) 1 0)))
