;; One-time measurement oracle for AWAI-2819 (2026-11-12).
;;
;; Run from the repo root:
;;   basilisp run oracle/us_population_oracle_run.cljc > examples/generated/us-population-aging-results.edn
;; Also runnable on JVM Clojure or Babashka (top-level forms execute on load).
;; Pure clojure.core only: no host interop, no reader conditionals, no IO
;; inside (stdout carries the EDN; the shell redirects it).
;; Avoid same-name shadowing between loop vars and coefficient bindings
;; (Basilisp misbehaves: see AWAI-2811 notes).
;;
;; NOT part of CI/gate/build/workflow. dynamics CI only runs
;; test/run_tests.cljk via nbb; nothing invokes this file.
;;
;; Parameters mirror examples/us-population-aging-scenarios.edn (single
;; source of truth). Before running, confirm the mirror holds:
;;   grep -e ':birth-rate 0.01067' -e ':death-rate 0.00912' \
;;        -e ':elder-growth 0.031' -e ':p0 340.1' -e ':e0 61.2' \
;;        -e ':net-migration 2.73' \
;;        examples/us-population-aging-scenarios.edn

(ns us-population-oracle-run)

(def common
  {:p0 340.1 :e0 61.2 :dt 0.05 :years 10.0
   :checkpoints [0 1 5 10]
   :birth-rate 0.01067 :death-rate 0.00912 :net-migration 2.73
   :elder-growth 0.031})

(def scenario-order
  ["baseline-2024-rates" "low-migration" "faster-aging"])

(def scenarios
  {"baseline-2024-rates" {}
   "low-migration" {:net-migration 1.26}
   "faster-aging" {:elder-growth 0.04}})

(defn- abs [x] (if (neg? x) (- x) x))

(defn- round3 [x]
  (/ (double (long (+ (* x 1000.0) 0.5))) 1000.0))

(defn simulate [pcfg]
  (let [{:keys [p0 e0 dt years checkpoints birth-rate death-rate net-migration elder-growth]} common
        br (get pcfg :birth-rate birth-rate)
        dr (get pcfg :death-rate death-rate)
        mg (get pcfg :net-migration net-migration)
        gr (get pcfg :elder-growth elder-growth)
        n (long (+ (/ years dt) 0.5))
        tol (+ (/ dt 2) 1e-12)]
    (loop [i 0 pop p0 eld e0 traj {0 [(round3 p0) (round3 e0)]}]
      (if (>= i n)
        traj
        (let [dp (+ (* (- br dr) pop) mg)
              pop2 (max 0.0 (+ pop (* dp dt)))
              eld2 (max 0.0 (min pop2 (+ eld (* gr eld dt))))
              t (* (inc i) dt)
              hit (first (filter #(and (not (contains? traj %))
                                       (< (abs (- t %)) tol))
                                 checkpoints))]
          (recur (inc i) pop2 eld2
                 (if hit (assoc traj hit [(round3 pop2) (round3 eld2)]) traj)))))))

(defn- fmt-point [[a b]] (str "[" a " " b "]"))

(defn- emit [results]
  (str "#:model{:id :us-population-aging,\n"
       "        :as-of \"2026-11-12\",\n"
       "        :interpretation\n"
       "        \"2-stock Euler integration (dt 0.05yr x 10yr), dependency-free one-time oracle. \"\n"
       "        \"Birth rate is NCHS-final 2024; death total is secondary-corroborated; net \"\n"
       "        \"migration and elderly growth are Census Vintage-2024 measured. Year-1 checked \"\n"
       "        \"against the 2025 estimate (tolerance reflects the 2025 migration drop).\",\n"
       "        :oracle {:script \"oracle/us_population_oracle_run.cljc (one-time oracle, not CI/gate)\",\n"
       "                 :runtime \"basilisp, macOS arm64\",\n"
       "                 :source-edn \"examples/us-population-aging-scenarios.edn (mirror asserted by grep before run)\"},\n"
       "        :checkpoints-years [0 1 5 10],\n"
       "        :scenarios\n"
       "        {\n"
       (apply str
              (map (fn [n]
                     (str "         :" n " {:checkpoints "
                          (apply str
                                 (map (fn [cp]
                                        (str "{" cp " "
                                             (fmt-point (get-in results [n cp]))
                                             "}"))
                                      (:checkpoints common)))
                          "}\n"))
                   scenario-order))
       "         }}\n"))

(let [results (into {} (map (fn [n] [n (simulate (get scenarios n))])
                            scenario-order))
      cps (:checkpoints common)]
  (doseq [[n traj] results]
    (when-not (= (set (keys traj)) (set cps))
      (throw (ex-info "checkpoint mismatch" {:scenario n :keys (keys traj)})))
    (doseq [[cp [pv ev]] traj]
      (when-not (and (<= 300.0 pv 400.0) (<= 50.0 ev 110.0) (<= ev pv))
        (throw (ex-info "bounds broken" {:scenario n :cp cp})))))
  (let [p1 (first (get-in results ["baseline-2024-rates" 1]))
        e1 (second (get-in results ["baseline-2024-rates" 1]))]
    (when-not (and (< (abs (- p1 341.78)) 2.0)
                   (< (abs (- e1 63.1)) 2.5))
      (throw (ex-info "year-1 validation failed" {:p1 p1 :e1 e1}))))
  (let [pb (first (get-in results ["baseline-2024-rates" 10]))
        pn (first (get-in results ["low-migration" 10]))
        sb (/ (second (get-in results ["baseline-2024-rates" 10]))
              (first (get-in results ["baseline-2024-rates" 10])))]
    (when-not (and (> pb pn) (> sb 0.18))
      (throw (ex-info "ordering broken" {:baseline-p pb :low-migration-p pn}))))
  (print (emit results)))
