;; One-time measurement oracle for AWAI-2814 (2026-11-12).
;;
;; Run from the repo root:
;;   basilisp run oracle/okinawa_population_oracle_run.cljc > examples/generated/okinawa-population-aging-results.edn
;; Also runnable on JVM Clojure or Babashka (top-level forms execute on load).
;; Pure clojure.core only: no host interop, no reader conditionals, no IO
;; inside (stdout carries the EDN; the shell redirects it).
;; Avoid same-name shadowing between loop vars and coefficient bindings
;; (Basilisp misbehaves: see AWAI-2811 notes).
;;
;; NOT part of CI/gate/build/workflow. dynamics CI only runs
;; test/run_tests.cljk via nbb; nothing invokes this file.
;;
;; Parameters mirror examples/okinawa-population-aging-scenarios.edn (single
;; source of truth). Before running, confirm the mirror holds:
;;   grep -e ':natural-rate -0.0023' -e ':social-rate 0.0011' \
;;        -e ':elder-growth 0.016' -e ':p0 1469.6' -e ':e0 349.8' \
;;        examples/okinawa-population-aging-scenarios.edn

(ns okinawa-population-oracle-run)

(def common
  {:p0 1469.6 :e0 349.8 :dt 0.05 :years 10.0
   :checkpoints [0 1 5 10]
   :natural-rate -0.0023 :social-rate 0.0011 :elder-growth 0.016})

(def scenario-order
  ["baseline-2024-rates" "no-inmigration" "slower-aging"])

(def scenarios
  {"baseline-2024-rates" {}
   "no-inmigration" {:social-rate 0.0}
   "slower-aging" {:elder-growth 0.008}})

(defn- abs [x] (if (neg? x) (- x) x))

(defn- round3 [x]
  (/ (double (long (+ (* x 1000.0) 0.5))) 1000.0))

(defn simulate [pcfg]
  (let [{:keys [p0 e0 dt years checkpoints natural-rate social-rate elder-growth]} common
        nr natural-rate
        sr (get pcfg :social-rate social-rate)
        gr (get pcfg :elder-growth elder-growth)
        n (long (+ (/ years dt) 0.5))
        tol (+ (/ dt 2) 1e-12)]
    (loop [i 0 pop p0 eld e0 traj {0 [(round3 p0) (round3 e0)]}]
      (if (>= i n)
        traj
        (let [pop2 (+ pop (* (+ nr sr) pop dt))
              eld2 (max 0.0 (min pop2 (+ eld (* gr eld dt))))
              t (* (inc i) dt)
              hit (first (filter #(and (not (contains? traj %))
                                       (< (abs (- t %)) tol))
                                 checkpoints))]
          (recur (inc i) pop2 eld2
                 (if hit (assoc traj hit [(round3 pop2) (round3 eld2)]) traj)))))))

(defn- fmt-point [[a b]] (str "[" a " " b "]"))

(defn- emit [results]
  (str "#:model{:id :okinawa-population-aging,\n"
       "        :as-of \"2026-11-12\",\n"
       "        :interpretation\n"
       "        \"2-stock Euler integration (dt 0.05yr x 10yr), dependency-free one-time oracle. \"\n"
       "        \"Natural/social/elder rates are 2024 measured values; the 23.8% elderly-share \"\n"
       "        \"level is secondary-sourced. Year-1 checked against the 2025 census.\",\n"
       "        :oracle {:script \"oracle/okinawa_population_oracle_run.cljc (one-time oracle, not CI/gate)\",\n"
       "                 :runtime \"basilisp, macOS arm64\",\n"
       "                 :source-edn \"examples/okinawa-population-aging-scenarios.edn (mirror asserted by grep before run)\"},\n"
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
      (when-not (and (<= 1300.0 pv 1600.0) (<= 300.0 ev 500.0) (<= ev pv))
        (throw (ex-info "bounds broken" {:scenario n :cp cp})))))
  (let [p1 (first (get-in results ["baseline-2024-rates" 1]))
        e1 (second (get-in results ["baseline-2024-rates" 1]))]
    (when-not (and (< (abs (- p1 1466.548)) 3.0)
                   (< (abs (- e1 355.4)) 5.0))
      (throw (ex-info "year-1 validation against 2025 census failed" {:p1 p1 :e1 e1}))))
  (let [pb (first (get-in results ["baseline-2024-rates" 10]))
        pn (first (get-in results ["no-inmigration" 10]))
        eb (second (get-in results ["baseline-2024-rates" 10]))
        es (second (get-in results ["slower-aging" 10]))]
    (when-not (and (> pb pn) (> eb es))
      (throw (ex-info "ordering broken" {:baseline-p pb :no-migration-p pn}))))
  (print (emit results)))
