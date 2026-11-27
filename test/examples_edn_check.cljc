;; Every examples/*.edn and examples/generated/*.edn must be readable EDN,
;; every oracle results file (:model/oracle present) must carry one checkpoint
;; map per scenario with a [a b] pair for each checkpoint year (AWAI-3249), and
;; every model/results/archetype EDN must satisfy the AWAI-2838 / NEG-2838-04
;; coefficient-free scenario-label provenance gate (never presenting scenario
;; outputs or unmeasured coefficients as :measured / :observed).
;;
;;   bb test/examples_edn_check.cljc
;;
;; Local check; CI for this repository is not currently running.
(require '[clojure.edn :as edn] '[clojure.java.io :as io])

(def root (-> *file* io/file .getCanonicalFile .getParentFile .getParentFile))
(load-file (str (io/file root "src/dynamics/provenance.cljc")))
(alias 'prov 'dynamics.provenance)

(def files (->> ["examples" "examples/generated"]
                (mapcat #(.listFiles (io/file root %)))
                (filter #(.endsWith (.getName %) ".edn"))
                (sort-by str)))

(def failures
  (vec (for [f files
             :let [problem (try
                             (let [m (edn/read-string (slurp f))
                                   prov-report (prov/validate-model-edn m)
                                   shape-err
                                   (when (and (.endsWith (.getName f) "-results.edn") (:model/oracle m))
                                     (let [years (:model/checkpoints-years m)
                                           scs (:model/scenarios m)]
                                       (when-not (and (seq years) (map? scs) (seq scs)
                                                      (every? (fn [s]
                                                                (let [cp (:checkpoints s)]
                                                                  (and (map? cp)
                                                                       (= (set years) (set (keys cp)))
                                                                       (every? #(and (vector? %) (= 2 (count %)) (every? number? %)) (vals cp)))))
                                                              (vals scs)))
                                         "checkpoint shape")))]
                               (or shape-err
                                   (when-not (:ok? prov-report)
                                     (str "provenance violation: " (pr-str (:violations prov-report))))))
                             (catch Exception e (.getMessage e)))]
             :when problem]
         [(str f) problem])))

(doseq [[f p] failures] (println "FAIL" f p))
(println (count files) "EDN files," (count failures) "failures")
(when (seq failures) (System/exit 1))
