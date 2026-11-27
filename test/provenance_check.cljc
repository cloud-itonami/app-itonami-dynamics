;; AWAI-2838 / NEG-2838-04 Provenance & Coefficient-Free Scenario-Label Check
;; (babashka or JVM Clojure, stdlib only).
;;   bb test/provenance_check.cljc
;;
;; Enforces:
;;   Rule: AWAI-2838 / coefficient-free-scenario-label
;;         (coefficient-free-zone-never-presented-as-measured)
;;   Failure mode: scenario-assumption-reported-as-observed-parameter
;;   Fail-closed: output-relabeled-scenario-pending-data
;;   Verification: assert-measured-vs-scenario-tag-per-output
;;
;; 1. Loads src/dynamics/provenance.cljk, src/dynamics/core.cljk, src/dynamics/xmile.cljk
;; 2. Validates every committed model/results/archetype/tier-b EDN file in
;;    examples/, examples/generated/, and examples/tier-b/
;; 3. Executes positive & negative-control checks for per-output measured vs
;;    scenario provenance tagging and fail-closed relabeling.
;; Exit 0 = every check passed, 1 = one or more checks failed.

(require '[clojure.edn :as edn]
         '[clojure.java.io :as io])

(def root (-> *file* io/file .getCanonicalFile .getParentFile .getParentFile))
(load-file (str (io/file root "src/dynamics/provenance.cljk")))
(load-file (str (io/file root "src/dynamics/core.cljk")))
(load-file (str (io/file root "src/dynamics/xmile.cljk")))

(alias 'prov 'dynamics.provenance)
(alias 'd 'dynamics.core)
(alias 'dx 'dynamics.xmile)

(def results (atom []))
(defn check! [id ok detail]
  (swap! results conj {:id id :ok (boolean ok) :detail detail})
  (println (str (if ok "PASS" "FAIL") "\t" (name id) "\t" detail)))

(defn catch-ex-data [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo e
      (or (ex-data e) {:message (.getMessage e)}))))

;; ---- 1. Audit all committed EDN artifacts across examples/ ------------------
(def edn-files
  (->> ["examples" "examples/generated" "examples/tier-b"]
       (mapcat #(.listFiles (io/file root %)))
       (filter #(.endsWith (.getName %) ".edn"))
       (sort-by #(.getPath %))))

(def edn-reports
  (for [f edn-files
        :let [m (edn/read-string (slurp f))
              r (prov/validate-model-edn m)]]
    (assoc r :file (.getPath f))))

(def model-edn-reports (filter #(not= :non-model (:kind %)) edn-reports))
(def edn-failures (filter #(not (:ok? %)) edn-reports))

(check! :all-committed-edns-pass-provenance-gate
        (and (pos? (count model-edn-reports)) (empty? edn-failures))
        (if (empty? edn-failures)
          (str (count model-edn-reports) " model/results/archetype/tier-b EDN files (" (count edn-files) " total EDNs), 0 violations")
          (str (count edn-failures) " violations: " (pr-str (map #(select-keys % [:file :violations]) edn-failures)))))

(let [scenario-and-results
      (filter #(= :model (:kind %)) edn-reports)
      all-scenario-labeled?
      (every? #(= :scenario-not-forecast (:classification %)) scenario-and-results)]
  (check! :all-scenario-and-results-edns-explicitly-classified
          (and (= 56 (count scenario-and-results)) all-scenario-labeled?)
          (str (count scenario-and-results) " scenario/results EDN files carry :model/classification :scenario-not-forecast")))

;; ---- 2. Positive controls: distinct per-output measured vs scenario tags ----
(let [bundle {:turnout-2024
              {:value 0.642
               :provenance/tag :measured
               :as-of "2024-11-05"
               :source "Official Electoral Commission 2024 turnout summary"}
              :enacted-bills-2024
              {:value 118
               :provenance/tag :observed
               :as-of 2024
               :source "Parliamentary Official Bulletin 2024"}
              :policy-elasticity-scenario
              {:value 0.15
               :provenance/tag :scenario-assumption
               :scenario? true}
              :pending-zone-output
              {:value 0.08
               :provenance/tag :scenario-pending-data
               :coefficient-free? true}}
      rep (prov/assert-measured-vs-scenario-tag-per-output! bundle)]
  (check! :valid-mixed-bundle-preserves-per-output-tags
          (and (:ok? rep)
               (= #{:turnout-2024 :enacted-bills-2024} (set (:measured-ids rep)))
               (= #{:policy-elasticity-scenario :pending-zone-output} (set (:scenario-ids rep)))
               (empty? (:relabeled-ids rep)))
          "measured and scenario outputs keep distinct verified per-output tags"))

;; ---- 3. Negative controls: scenario assumption reported as observed ---------
(let [bad-param {:id :policy-compliance-elasticity
                 :value 0.30
                 :provenance/tag :observed
                 :scenario? true
                 :source "Sensitivity draft"
                 :as-of "2026-11-28"}
      relabeled (prov/enforce-output-provenance bad-param)
      err (catch-ex-data #(prov/validate-output-provenance! bad-param))]
  (check! :neg-scenario-as-observed-relabels-fail-closed
          (and (= :scenario-pending-data (:provenance/tag relabeled))
               (true? (:provenance/relabeled? relabeled))
               (= :scenario-assumption-reported-as-observed-parameter (:provenance/failure-mode relabeled))
               (= :output-relabeled-scenario-pending-data (:provenance/fail-closed-action relabeled))
               (= :scenario-assumption-reported-as-observed-parameter (:error/code err)))
          "scenario assumption tagged :observed relabels to :scenario-pending-data and throws in strict mode"))

(let [bad-bundle {:turnout-2024
                  {:value 0.642
                   :provenance/tag :measured
                   :as-of "2024-11-05"
                   :source "Official Electoral Commission 2024 turnout summary"}
                  :forged-policy-parameter
                  {:value 0.41
                   :provenance/tag :measured
                   :assumption? true
                   :source "Analyst assumption"
                   :as-of "2026-11-28"}
                  :untagged-policy-output
                  {:value 9.9}}
      rep (prov/assert-measured-vs-scenario-tag-per-output bad-bundle)
      err (catch-ex-data #(prov/assert-measured-vs-scenario-tag-per-output! bad-bundle))]
  (check! :neg-per-output-assert-catches-mislabeled-and-untagged
          (and (false? (:ok? rep))
               (= #{:forged-policy-parameter :untagged-policy-output} (set (:relabeled-ids rep)))
               (= :measured (get-in rep [:safe-outputs :turnout-2024 :provenance/tag]))
               (= :scenario-pending-data (get-in rep [:safe-outputs :forged-policy-parameter :provenance/tag]))
               (= :scenario-pending-data (get-in rep [:safe-outputs :untagged-policy-output :provenance/tag]))
               (some? err))
          "mislabeled & untagged outputs fail closed to :scenario-pending-data while valid measured output stays :measured"))

(let [mixed-input-output {:id :mixed-policy-valueflow
                          :value 115.0
                          :provenance/tag :measured
                          :as-of "2026-11-28"
                          :source "Ministry of Finance FY2025 budget"
                          :parameters
                          {:budget {:value 100.0 :provenance/tag :measured :as-of "2025-04-01"
                                    :source "Ministry of Finance FY2025 budget"}
                           :multiplier {:value 1.15 :provenance/tag :scenario-assumption :scenario? true}}}
      relabeled (prov/enforce-output-provenance mixed-input-output)]
  (check! :neg-mixed-measured-and-assumed-params-relabels-fail-closed
          (and (= :scenario-pending-data (:provenance/tag relabeled))
               (true? (:provenance/relabeled? relabeled))
               (some #{:contains-unmeasured-or-scenario-parameter} (:provenance/reasons relabeled)))
          "output mixing measured baseline + assumed coefficient cannot be tagged :measured"))

(let [core-stock-err (catch-ex-data #(d/stock {:id :bad-stock :value 1.0 :as-of "2026-11-28"
                                               :source "Official dataset" :estimate? true
                                               :provenance/tag :measured}))
      core-flow-err (catch-ex-data #(d/flow {:id :bad-flow :from :x :to :y
                                             :coefficient-free? true :provenance/tag :observed}))
      xmile-tagged (dx/tag-projection-output {:output {:initial 0 :checkpoints {1 5.0}}
                                              :claimed-tag :observed
                                              :coefficient-free? true})]
  (check! :neg-core-and-xmile-guards-fail-closed
          (and (= :scenario-assumption-reported-as-observed-parameter (:error/code core-stock-err))
               (= :scenario-assumption-reported-as-observed-parameter (:error/code core-flow-err))
               (= :scenario-pending-data (:provenance/tag xmile-tagged))
               (true? (:provenance/relabeled? xmile-tagged)))
          "dynamics.core stock/flow and dynamics.xmile tag-projection-output enforce fail-closed guard"))

(let [forged-edn {:model/id :tampered-political-model
                  :model/as-of "2026-11-28"
                  :model/classification :observed
                  :scenarios {:baseline {:checkpoints {0 [1.0 0.5]}}}}
      rep (prov/validate-model-edn forged-edn)]
  (check! :neg-tampered-edn-classification-rejected
          (and (false? (:ok? rep))
               (some #(= :scenario-model-classified-as-measured %)
                     (mapcat :reasons (:violations rep))))
          "tampered model EDN claiming :model/classification :observed is rejected"))

;; ---- summary ----------------------------------------------------------------
(let [fails (remove :ok @results)]
  (println (str "TOTAL\t" (count @results) " checks, " (count fails) " failures"))
  (when (seq fails) (System/exit 1)))
