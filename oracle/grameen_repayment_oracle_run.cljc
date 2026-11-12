;; One-time measurement oracle for AWAI-2817 (2026-11-12).
;;
;; Run from the repo root:
;;   basilisp run oracle/grameen_repayment_oracle_run.cljc > examples/generated/grameen-repayment-results.edn
;; Also runnable on JVM Clojure or Babashka (top-level forms execute on load).
;; Pure clojure.core only: no host interop, no reader conditionals, no IO
;; inside (stdout carries the EDN; the shell redirects it).
;; Avoid same-name shadowing between loop vars and coefficient bindings
;; (Basilisp misbehaves: see AWAI-2811 notes).
;;
;; NOT part of CI/gate/build/workflow. dynamics CI only runs
;; test/run_tests.cljk via nbb; nothing invokes this file.
;;
;; Parameters mirror examples/grameen-repayment-scenarios.edn (single source
;; of truth). Before running, confirm the mirror holds:
;;   grep -e ':disbursement 2.4' -e ':recovery-rate 0.9553' \
;;        -e ':turnover 1.725' -e ':o0 1.42' -e ':r0 39.09848' \
;;        examples/grameen-repayment-scenarios.edn

(ns grameen-repayment-oracle-run)

(def common
  {:o0 1.42 :r0 39.09848 :dt 0.05 :years 10.0
   :checkpoints [0 1 5 10]
   :recovery-rate 0.9553 :turnover 1.725 :disbursement 2.4})

(def scenario-order
  ["baseline" "high-disburse" "stress-recovery"])

(def scenarios
  {"baseline" {}
   "high-disburse" {:disbursement 3.2}
   "stress-recovery" {:recovery-rate 0.90}})

(defn- abs [x] (if (neg? x) (- x) x))

(defn- round3 [x]
  (/ (double (long (+ (* x 1000.0) 0.5))) 1000.0))

(defn simulate [pcfg]
  (let [{:keys [o0 r0 dt years checkpoints recovery-rate turnover disbursement]} common
        er (get pcfg :recovery-rate recovery-rate)
        dd (get pcfg :disbursement disbursement)
        n (long (+ (/ years dt) 0.5))
        tol (+ (/ dt 2) 1e-12)]
    (loop [i 0 bal o0 rec r0 traj {0 [(round3 o0) (round3 r0)]}]
      (if (>= i n)
        traj
        (let [coll (* er turnover bal)
              bal2 (max 0.0 (+ bal (* (- dd coll) dt)))
              rec2 (+ rec (* coll dt))
              t (* (inc i) dt)
              hit (first (filter #(and (not (contains? traj %))
                                       (< (abs (- t %)) tol))
                                 checkpoints))]
          (recur (inc i) bal2 rec2
                 (if hit (assoc traj hit [(round3 bal2) (round3 rec2)]) traj)))))))

(defn- fmt-point [[a b]] (str "[" a " " b "]"))

(defn- emit [results]
  (str "#:model{:id :grameen-repayment-loop,\n"
       "        :as-of \"2026-11-12\",\n"
       "        :classification :scenario-not-forecast,\n"
       "        :interpretation\n"
       "        \"2-stock Euler integration (dt 0.05yr x 10yr), dependency-free one-time oracle. \"\n"
       "        \"Outstanding and cumulative repaid are Grameen-measured Dec-2025 values; \"\n"
       "        \"recovery rate is measured; turnover is calibrated to the Dec-2025 monthly \"\n"
       "        \"flow; annual disbursement is a sensitivity assumption. Year-1 checked \"\n"
       "        \"against the Aug-2026 outstanding figure.\",\n"
       "        :oracle {:script \"oracle/grameen_repayment_oracle_run.cljc (one-time oracle, not CI/gate)\",\n"
       "                 :runtime \"basilisp, macOS arm64\",\n"
       "                 :source-edn \"examples/grameen-repayment-scenarios.edn (mirror asserted by grep before run)\"},\n"
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
    (doseq [[cp [bv rv]] traj]
      (when-not (and (<= 0.0 bv 10.0) (<= 30.0 rv 120.0))
        (throw (ex-info "bounds broken" {:scenario n :cp cp})))))
  (let [bo1 (first (get-in results ["baseline" 1]))
        br1 (second (get-in results ["baseline" 1]))]
    (when-not (and (< (abs (- bo1 1.48)) 0.15)
                   (< (abs (- br1 41.44)) 0.5))
      (throw (ex-info "year-1 validation failed" {:o1 bo1 :r1 br1}))))
  (let [ob (first (get-in results ["baseline" 10]))
        oh (first (get-in results ["high-disburse" 10]))
        rb (second (get-in results ["baseline" 10]))
        rs (second (get-in results ["stress-recovery" 10]))]
    (when-not (and (> oh ob) (> rb rs))
      (throw (ex-info "ordering broken" {:baseline-o ob :high-o oh}))))
  (print (emit results)))
