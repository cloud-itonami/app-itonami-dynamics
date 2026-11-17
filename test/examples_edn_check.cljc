;; Every examples/*.edn and examples/generated/*.edn must be readable EDN, and
;; every oracle results file (:model/oracle present) must carry one checkpoint map per scenario with a
;; [a b] pair for each checkpoint year (AWAI-3249).
;;
;;   bb test/examples_edn_check.cljc
;;
;; Local check; CI for this repository is not currently running.
(require '[clojure.edn :as edn] '[clojure.java.io :as io])

(def files (->> ["examples" "examples/generated"]
                (mapcat #(.listFiles (io/file %)))
                (filter #(.endsWith (.getName %) ".edn"))
                (sort-by str)))

(def failures
  (vec (for [f files
             :let [problem (try
                             (let [m (edn/read-string (slurp f))]
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
                                     "checkpoint shape"))))
                             (catch Exception e (.getMessage e)))]
             :when problem]
         [(str f) problem])))

(doseq [[f p] failures] (println "FAIL" f p))
(println (count files) "EDN files," (count failures) "failures")
(when (seq failures) (System/exit 1))
