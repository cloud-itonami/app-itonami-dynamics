;; One-time measurement oracle for AWAI-2810 (2026-11-12).
;;
;; Run from the repo root:
;;   basilisp run oracle/china_population_oracle_run.cljc > examples/generated/china-population-aging-results.edn
;; Also runnable on JVM Clojure or Babashka (top-level forms execute on load).
;; Pure clojure.core only: no host interop, no reader conditionals, no IO
;; inside (stdout carries the EDN; the shell redirects it).
;;
;; NOT part of CI/gate/build/workflow. dynamics CI only runs
;; test/run_tests.cljk via nbb; nothing invokes this file.
;;
;; Parameters mirror examples/china-population-aging-scenarios.edn (single
;; source of truth). Before running, confirm the mirror holds:
;;   grep -e ':birth-rate 0.00677' -e ':birth-rate 0.00562' \
;;        -e ':aging-coef 0.007' -e ':p0 1408.28' -e ':e0 220.23' \
;;        examples/china-population-aging-scenarios.edn

(ns china-population-oracle-run)

(def common
  {:p0 1408.28 :e0 220.23 :dt 0.05 :years 10.0
   :checkpoints [0 1 5 10]
   :aging-coef 0.007 :elder-death-multiple 3.0})

(def scenario-order
  ["baseline-2024-rates" "low-fertility-2025-rates" "slower-aging"])

(def scenarios
  {"baseline-2024-rates"
   {:birth-rate 0.00677 :death-rate 0.00776}
   "low-fertility-2025-rates"
   {:birth-rate 0.00562 :death-rate 0.00803}
   "slower-aging"
   {:birth-rate 0.00562 :death-rate 0.00803 :aging-coef 0.0035}})

(defn- abs [x] (if (neg? x) (- x) x))

(defn- round3 [x]
  (/ (double (long (+ (* x 1000.0) 0.5))) 1000.0))

(defn simulate [p]
  (let [{:keys [p0 e0 dt years checkpoints aging-coef elder-death-multiple]} common
        b (:birth-rate p)
        d (:death-rate p)
        a (get p :aging-coef aging-coef)
        de (* elder-death-multiple d)
        n (long (+ (/ years dt) 0.5))
        tol (+ (/ dt 2) 1e-12)]
    (loop [i 0 p p0 e e0 traj {0 [(round3 p0) (round3 e0)]}]
      (if (>= i n)
        traj
        (let [dp (* (- b d) p)
              de-flow (- (* a (- p e)) (* de e))
              p2 (max 0.0 (+ p (* dp dt)))
              e2 (max 0.0 (min p2 (+ e (* de-flow dt))))
              t (* (inc i) dt)
              hit (first (filter #(and (not (contains? traj %))
                                       (< (abs (- t %)) tol))
                                 checkpoints))]
          (recur (inc i) p2 e2
                 (if hit (assoc traj hit [(round3 p2) (round3 e2)]) traj)))))))

(defn- fmt-point [[p e]] (str "[" p " " e "]"))

(defn- emit [results]
  (str "#:model{:id :china-population-aging,\n"
       "        :as-of \"2026-11-12\",\n"
       "        :classification :scenario-not-forecast,\n"
       "        :interpretation\n"
       "        \"2-stock Euler integration (dt 0.05yr x 10yr), dependency-free one-time oracle. \"\n"
       "        \"Birth/death rates are NBS-measured 2024/2025 values; aging coefficient and \"\n"
       "        \"elder-mortality multiple are sensitivity assumptions. Year-1 checked against \"\n"
       "        \"NBS 2025 preliminary values.\",\n"
       "        :oracle {:script \"oracle/china_population_oracle_run.cljc (one-time oracle, not CI/gate)\",\n"
       "                 :runtime \"basilisp, macOS arm64\",\n"
       "                 :source-edn \"examples/china-population-aging-scenarios.edn (mirror asserted by grep before run)\"},\n"
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
      (throw (ex-info "checkpoint mismatch" {:scenario n :keys (keys traj)}))))
  (let [low1 (get-in results ["low-fertility-2025-rates" 1])]
    (when-not (and (< (abs (- (first low1) 1405.0)) 1.0)
                   (< (abs (- (second low1) 223.65)) 1.0))
      (throw (ex-info "year-1 validation against NBS 2025 failed" {:low1 low1}))))
  (doseq [n scenario-order]
    (let [p0 (first (get-in results [n 0]))
          p10 (first (get-in results [n 10]))
          s0 (/ (second (get-in results [n 0])) p0)
          s10 (/ (second (get-in results [n 10])) p10)]
      (when-not (< p10 p0)
        (throw (ex-info "decline invariant broken" {:scenario n})))
      (when (and (not= n "slower-aging") (not (> s10 s0)))
        (throw (ex-info "aging invariant broken" {:scenario n :s0 s0 :s10 s10})))))
  (print (emit results)))
