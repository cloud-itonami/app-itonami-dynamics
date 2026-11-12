;; One-time measurement oracle for AWAI-2811 (2026-11-12).
;;
;; Run from the repo root:
;;   basilisp run oracle/india_population_oracle_run.cljc > examples/generated/india-population-urbanization-results.edn
;; Also runnable on JVM Clojure or Babashka (top-level forms execute on load).
;; Pure clojure.core only: no host interop, no reader conditionals, no IO
;; inside (stdout carries the EDN; the shell redirects it).
;;
;; NOT part of CI/gate/build/workflow. dynamics CI only runs
;; test/run_tests.cljk via nbb; nothing invokes this file.
;;
;; Parameters mirror examples/india-population-urbanization-scenarios.edn
;; (single source of truth). Before running, confirm the mirror holds:
;;   grep -e ':birth-rate 0.0183' -e ':birth-rate 0.0160' \
;;        -e ':urbanization-coef 0.008' -e ':p0 1450.936' \
;;        -e ':u0 530.387' \
;;        examples/india-population-urbanization-scenarios.edn

(ns india-population-oracle-run)

(def common
  {:p0 1450.936 :u0 530.387 :dt 0.05 :years 10.0
   :checkpoints [0 1 5 10]
   :urbanization-coef 0.008})

(def scenario-order
  ["baseline-srs-2024" "lower-fertility" "faster-urbanization"])

(def scenarios
  {"baseline-srs-2024"
   {:birth-rate 0.0183 :death-rate 0.0064}
   "lower-fertility"
   {:birth-rate 0.0160 :death-rate 0.0064}
   "faster-urbanization"
   {:birth-rate 0.0183 :death-rate 0.0064 :urbanization-coef 0.012}})

(defn- abs [x] (if (neg? x) (- x) x))

(defn- round3 [x]
  (/ (double (long (+ (* x 1000.0) 0.5))) 1000.0))

(defn simulate [p]
  (let [{:keys [p0 u0 dt years checkpoints urbanization-coef]} common
        b (:birth-rate p)
        d (:death-rate p)
        uc (get p :urbanization-coef urbanization-coef)
        n (long (+ (/ years dt) 0.5))
        tol (+ (/ dt 2) 1e-12)]
    (loop [i 0 p p0 u u0 traj {0 [(round3 p0) (round3 u0)]}]
      (if (>= i n)
        traj
        (let [dp (* (- b d) p)
              du (+ (* uc (- p u)) (* (- b d) u))
              p2 (+ p (* dp dt))
              u2 (max 0.0 (min p2 (+ u (* du dt))))
              t (* (inc i) dt)
              hit (first (filter #(and (not (contains? traj %))
                                       (< (abs (- t %)) tol))
                                 checkpoints))]
          (recur (inc i) p2 u2
                 (if hit (assoc traj hit [(round3 p2) (round3 u2)]) traj)))))))

(defn- fmt-point [[p u]] (str "[" p " " u "]"))

(defn- emit [results]
  (str "#:model{:id :india-population-urbanization,\n"
       "        :as-of \"2026-11-12\",\n"
       "        :classification :scenario-not-forecast,\n"
       "        :interpretation\n"
       "        \"2-stock Euler integration (dt 0.05yr x 10yr), dependency-free one-time oracle. \"\n"
       "        \"Birth/death rates are SRS-2024 measured values; urbanization coefficient is \"\n"
       "        \"a sensitivity assumption. Year-1 checked against the cited 2025 range.\",\n"
       "        :oracle {:script \"oracle/india_population_oracle_run.cljc (one-time oracle, not CI/gate)\",\n"
       "                 :runtime \"basilisp, macOS arm64\",\n"
       "                 :source-edn \"examples/india-population-urbanization-scenarios.edn (mirror asserted by grep before run)\"},\n"
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
  (let [p1 (first (get-in results ["baseline-srs-2024" 1]))]
    (when-not (and (< 1455.0 p1) (< p1 1480.0))
      (throw (ex-info "year-1 range check failed" {:p1 p1}))))
  (doseq [n scenario-order]
    (let [p0 (first (get-in results [n 0]))
          p10 (first (get-in results [n 10]))]
      (when-not (< p0 p10)
        (throw (ex-info "growth invariant broken" {:scenario n})))))
  (let [s (into {} (map (fn [n]
                          (let [tr (get results n)]
                            [n (/ (second (get tr 10)) (first (get tr 10)))]))
                        scenario-order))]
    (when-not (> (get s "faster-urbanization")
                 (get s "baseline-srs-2024"))
      (throw (ex-info "urbanization ordering broken" {:shares s}))))
  (print (emit results)))
