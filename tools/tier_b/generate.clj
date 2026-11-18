;; AWAI-3384 model generator (babashka or JVM Clojure, stdlib only).
;;   bb tools/tier_b/generate.clj [inputs.edn] [out.edn]
;; Writes one Tier B model per country/area from the WPP 2024 inputs. The output
;; is deterministic: the check regenerates it and compares byte for byte.
(require '[clojure.edn :as edn] '[clojure.java.io :as io] '[clojure.pprint :as pp])
(def root (-> *file* io/file .getCanonicalFile .getParentFile .getParentFile .getParentFile))
(load-file (str (io/file root "oracle/tier_b_population.cljc")))
(defn render [inputs]
  (let [models (tier-b-population/generate inputs)]
    (with-out-str
      (pp/pprint {:dataset/id "awai-3384-wpp2024-tier-b-models"
                  :dataset/version "2026-11-18.1"
                  :issue "AWAI-3384"
                  :classification :scenario-not-forecast
                  :tier :b
                  :inputs {:file "examples/tier-b/wpp2024-tier-b-inputs.edn" :dataset (:dataset/id inputs) :base-year 2023}
                  :assumptions {:elder-death-multiple {:value 3.0 :status :assumed :note "same as the Tier A models"}
                                :aging-coef {:status :derived :note "from the WPP 2022->2023 change of the 65+ population and the assumed multiple"}
                                :rates {:status :held-constant :note "2023 rates held for 10 years; a scenario, not a projection"}}
                  :coverage {:share (tier-b-population/coverage inputs models) :denominator "WPP 2024 World, 2023, same panel"}
                  :models models}))))
(when (= *file* (System/getProperty "babashka.file" *file*))
  (let [[in out] *command-line-args*
        in (or in (str (io/file root "examples/tier-b/wpp2024-tier-b-inputs.edn")))
        out (or out (str (io/file root "examples/tier-b/wpp2024-tier-b-models.edn")))]
    (spit out (render (edn/read-string (slurp in))))
    (println "wrote" out)))
