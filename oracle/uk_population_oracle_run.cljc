;; One-time measurement oracle for AWAI-2849 (2026-11-12).
;;
;; Run from the repo root:
;;   basilisp run oracle/uk_population_oracle_run.cljc > examples/generated/uk-population-aging-results.edn
;; Also runnable on JVM Clojure or Babashka (top-level forms execute on load).
;; Pure clojure.core only: no host interop, no reader conditionals, no IO
;; inside (stdout carries the EDN; the shell redirects it).
;; Avoid same-name shadowing between loop vars and coefficient bindings
;; (Basilisp misbehaves: see AWAI-2811 notes).
;;
;; NOT part of CI/gate/build/workflow. dynamics CI only runs
;; test/run_tests.cljk via nbb; nothing invokes this file.
;;
;; Parameters mirror examples/uk-population-aging-scenarios.edn (single
;; source of truth). Before running, confirm the mirror holds:
;;   grep -e ':birth-rate 0.00923' -e ':death-rate 0.00988' \
;;        -e ':aging-coef 0.015' -e ':p0 69.3' -e ':e0 13.10' \
;;        -e ':net-migration 0.22' \
;;        examples/uk-population-aging-scenarios.edn

(ns uk-population-oracle-run)

(def common
  {:p0 69.3 :e0 13.10 :dt 0.05 :years 10.0
   :checkpoints [0 1 5 10]
   :birth-rate 0.00923 :death-rate 0.00988 :net-migration 0.22
   :aging-coef 0.015 :elder-death-multiple 3.0})

(def scenario-order
  ["baseline-ons-projection" "low-migration" "faster-aging"])

(def scenarios
  {"baseline-ons-projection" {}
   "low-migration" {:net-migration 0.11}
   "faster-aging" {:aging-coef 0.019}})

(defn- abs [x] (if (neg? x) (- x) x))

(defn- round3 [x]
  (/ (double (long (+ (* x 1000.0) 0.5))) 1000.0))

(defn simulate [pcfg]
  (let [{:keys [p0 e0 dt years checkpoints birth-rate death-rate net-migration aging-coef elder-death-multiple]} common
        br (get pcfg :birth-rate birth-rate)
        dr (get pcfg :death-rate death-rate)
        mg (get pcfg :net-migration net-migration)
        ac (get pcfg :aging-coef aging-coef)
        de (* elder-death-multiple dr)
        n (long (+ (/ years dt) 0.5))
        tol (+ (/ dt 2) 1e-12)]
    (loop [i 0 pop p0 eld e0 traj {0 [(round3 p0) (round3 e0)]}]
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
                 (if hit (assoc traj hit [(round3 pop2) (round3 eld2)]) traj)))))))

(defn- fmt-point [[a b]] (str "[" a " " b "]"))

(defn- emit [results]
  (str "#:model{:id :uk-population-aging,\n"
       "        :as-of \"2026-11-12\",\n"
       "        :interpretation\n"
       "        \"2-stock Euler integration (dt 0.05yr x 10yr), dependency-free one-time oracle. \"\n"
       "        \"Rates are converted from the ONS 2024-based 10-year projection totals; \"\n"
       "        \"the elderly level is approximate; aging coefficient and elder-mortality \"\n"
       "        \"multiple are sensitivity assumptions. Year-1 checked against the ONS path.\",\n"
       "        :oracle {:script \"oracle/uk_population_oracle_run.cljc (one-time oracle, not CI/gate)\",\n"
       "                 :runtime \"basilisp, macOS arm64\",\n"
       "                 :source-edn \"examples/uk-population-aging-scenarios.edn (mirror asserted by grep before run)\"},\n"
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
      (when-not (and (<= 68.0 pv 73.0) (<= 13.0 ev 20.0) (<= ev pv))
        (throw (ex-info "bounds broken" {:scenario n :cp cp})))))
  (let [p1 (first (get-in results ["baseline-ons-projection" 1]))
        e1 (second (get-in results ["baseline-ons-projection" 1]))]
    (when-not (and (< (abs (- p1 69.47)) 0.6)
                   (< (abs (- e1 13.31)) 0.4))
      (throw (ex-info "year-1 validation failed" {:p1 p1 :e1 e1}))))
  (let [pb (first (get-in results ["baseline-ons-projection" 10]))
        pl (first (get-in results ["low-migration" 10]))
        sb (/ (second (get-in results ["baseline-ons-projection" 10]))
              (first (get-in results ["baseline-ons-projection" 10])))]
    (when-not (and (> pb pl) (> sb 0.189))
      (throw (ex-info "ordering broken" {:baseline-p pb :low-migration-p pl}))))
  (print (emit results)))
