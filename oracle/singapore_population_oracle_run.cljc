;; One-time measurement oracle for AWAI-2826 (2026-11-12).
;;
;; Run from the repo root:
;;   basilisp run oracle/singapore_population_oracle_run.cljc > examples/generated/singapore-population-aging-results.edn
;; Also runnable on JVM Clojure or Babashka (top-level forms execute on load).
;; Pure clojure.core only: no host interop, no reader conditionals, no IO
;; inside (stdout carries the EDN; the shell redirects it).
;; Units are thousands of people (unlike the millions used elsewhere),
;; because Singapore's stocks are an order of magnitude smaller.
;; Avoid same-name shadowing between loop vars and coefficient bindings
;; (Basilisp misbehaves: see AWAI-2811 notes).
;;
;; NOT part of CI/gate/build/workflow. dynamics CI only runs
;; test/run_tests.cljk via nbb; nothing invokes this file.
;;
;; Parameters mirror examples/singapore-population-aging-scenarios.edn
;; (single source of truth). Before running, confirm the mirror holds:
;;   grep -e ':birth-rate 0.00737' -e ':death-rate 0.006' \
;;        -e ':aging-coef 0.010' -e ':p0 4180.9' -e ':e0 752.6' \
;;        -e ':net-migration 18.0' \
;;        examples/singapore-population-aging-scenarios.edn

(ns singapore-population-oracle-run)

(def common
  {:p0 4180.9 :e0 752.6 :dt 0.05 :years 10.0
   :checkpoints [0 1 5 10]
   :birth-rate 0.00737 :death-rate 0.006 :net-migration 18.0
   :aging-coef 0.010 :elder-death-multiple 3.0})

(def scenario-order
  ["baseline-2024-rates" "lower-fertility" "faster-aging"])

(def scenarios
  {"baseline-2024-rates" {}
   "lower-fertility" {:birth-rate 0.00663}
   "faster-aging" {:aging-coef 0.014}})

(defn- abs [x] (if (neg? x) (- x) x))

(defn- round1 [x]
  (/ (double (long (+ (* x 10.0) 0.5))) 10.0))

(defn simulate [pcfg]
  (let [{:keys [p0 e0 dt years checkpoints birth-rate death-rate net-migration aging-coef elder-death-multiple]} common
        br (get pcfg :birth-rate birth-rate)
        dr (get pcfg :death-rate death-rate)
        mg (get pcfg :net-migration net-migration)
        ac (get pcfg :aging-coef aging-coef)
        de (* elder-death-multiple dr)
        n (long (+ (/ years dt) 0.5))
        tol (+ (/ dt 2) 1e-12)]
    (loop [i 0 pop p0 eld e0 traj {0 [(round1 p0) (round1 e0)]}]
      (if (>= i n)
        traj
        (let [dp (+ (* (- br dr) pop) mg)
              de-flow (- (* ac (- pop eld)) (* de eld))
              pop2 (max 0.0 (+ pop (* dp dt)))
              eld2 (max 0.0 (min pop2 (+ eld (* de-flow dt))))
              t (* (inc i) dt)
              hit (first (filter #(and (not (contains? traj %))
                                       (< (abs (- t %)) tol))
                                 checkpoints))]
          (recur (inc i) pop2 eld2
                 (if hit (assoc traj hit [(round1 pop2) (round1 eld2)]) traj)))))))

(defn- fmt-point [[a b]] (str "[" a " " b "]"))

(defn- emit [results]
  (str "#:model{:id :singapore-population-aging,\n"
       "        :as-of \"2026-11-12\",\n"
       "        :interpretation\n"
       "        \"2-stock Euler integration (dt 0.05yr x 10yr), dependency-free one-time oracle, \"\n"
       "        \"units are thousands of people. Birth/death rates are SingStat/MOH-measured \"\n"
       "        \"2024 values; net migration is a residual; aging coefficient and elder-mortality \"\n"
       "        \"multiple are sensitivity assumptions. Year-1 checked against the 2025 estimate.\",\n"
       "        :oracle {:script \"oracle/singapore_population_oracle_run.cljc (one-time oracle, not CI/gate)\",\n"
       "                 :runtime \"basilisp, macOS arm64\",\n"
       "                 :source-edn \"examples/singapore-population-aging-scenarios.edn (mirror asserted by grep before run)\"},\n"
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
      (when-not (and (<= 4000.0 pv 5000.0) (<= 700.0 ev 1500.0) (<= ev pv))
        (throw (ex-info "bounds broken" {:scenario n :cp cp})))))
  (let [p1 (first (get-in results ["baseline-2024-rates" 1]))
        e1 (second (get-in results ["baseline-2024-rates" 1]))]
    (when-not (and (< (abs (- p1 4204.5)) 5.0)
                   (< (abs (- e1 775.0)) 35.0))
      (throw (ex-info "year-1 validation failed" {:p1 p1 :e1 e1}))))
  (let [pb (first (get-in results ["baseline-2024-rates" 10]))
        pl (first (get-in results ["lower-fertility" 10]))
        sb (/ (second (get-in results ["baseline-2024-rates" 10]))
              (first (get-in results ["baseline-2024-rates" 10])))]
    (when-not (and (> pb pl) (> sb 0.18))
      (throw (ex-info "ordering broken" {:baseline-p pb :lower-fert-p pl}))))
  (print (emit results)))
