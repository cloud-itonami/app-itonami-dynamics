;; One-time measurement oracle for AWAI-2813 (2026-11-12).
;;
;; Run from the repo root:
;;   basilisp run oracle/europe_aging_oracle_run.cljc > examples/generated/europe-aging-results.edn
;; Also runnable on JVM Clojure or Babashka (top-level forms execute on load).
;; Pure clojure.core only: no host interop, no reader conditionals, no IO
;; inside (stdout carries the EDN; the shell redirects it).
;; Avoid same-name shadowing between loop vars and coefficient bindings
;; (Basilisp misbehaves: see AWAI-2811 notes).
;;
;; NOT part of CI/gate/build/workflow. dynamics CI only runs
;; test/run_tests.cljk via nbb; nothing invokes this file.
;;
;; Parameters mirror examples/europe-aging-scenarios.edn (single source of
;; truth). Before running, confirm the mirror holds:
;;   grep -e ':birth-rate 0.0079' -e ':death-rate 0.0107' \
;;        -e ':aging-coef 0.014' -e ':p0 449.0' -e ':e0 96.98' \
;;        -e ':net-migration 1.5' \
;;        examples/europe-aging-scenarios.edn

(ns europe-aging-oracle-run)

(def common
  {:p0 449.0 :e0 96.98 :dt 0.05 :years 10.0
   :checkpoints [0 1 5 10]
   :aging-coef 0.014 :elder-death-multiple 3.0 :net-migration 1.5})

(def scenario-order
  ["baseline-2024-rates" "no-migration" "faster-aging"])

(def scenarios
  {"baseline-2024-rates"
   {:birth-rate 0.0079 :death-rate 0.0107}
   "no-migration"
   {:birth-rate 0.0079 :death-rate 0.0107 :net-migration 0.0}
   "faster-aging"
   {:birth-rate 0.0079 :death-rate 0.0107 :aging-coef 0.018}})

(defn- abs [x] (if (neg? x) (- x) x))

(defn- round3 [x]
  (/ (double (long (+ (* x 1000.0) 0.5))) 1000.0))

(defn simulate [pcfg]
  (let [{:keys [p0 e0 dt years checkpoints aging-coef elder-death-multiple net-migration]} common
        br (:birth-rate pcfg)
        dr (:death-rate pcfg)
        ac (get pcfg :aging-coef aging-coef)
        mg (get pcfg :net-migration net-migration)
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
  (str "#:model{:id :europe-aging,\n"
       "        :as-of \"2026-11-12\",\n"
       "        :classification :scenario-not-forecast,\n"
       "        :interpretation\n"
       "        \"2-stock Euler integration (dt 0.05yr x 10yr), dependency-free one-time oracle. \"\n"
       "        \"Birth/death rates are Eurostat-2024 measured values; net migration, aging \"\n"
       "        \"coefficient and elder-mortality multiple are sensitivity assumptions. Year-1 \"\n"
       "        \"checked against Eurostat January-2025 65+ figure.\",\n"
       "        :oracle {:script \"oracle/europe_aging_oracle_run.cljc (one-time oracle, not CI/gate)\",\n"
       "                 :runtime \"basilisp, macOS arm64\",\n"
       "                 :source-edn \"examples/europe-aging-scenarios.edn (mirror asserted by grep before run)\"},\n"
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
  (let [e1 (second (get-in results ["baseline-2024-rates" 1]))]
    (when-not (< (abs (- e1 99.0)) 1.5)
      (throw (ex-info "year-1 validation against Eurostat Jan-2025 failed" {:e1 e1}))))
  (doseq [n scenario-order]
    (let [s0 (/ (second (get-in results [n 0])) (first (get-in results [n 0])))
          s10 (/ (second (get-in results [n 10])) (first (get-in results [n 10])))]
      (when-not (> s10 s0)
        (throw (ex-info "aging invariant broken" {:scenario n})))))
  (let [pb (first (get-in results ["baseline-2024-rates" 10]))
        pn (first (get-in results ["no-migration" 10]))]
    (when-not (> pb pn)
      (throw (ex-info "migration ordering broken" {:baseline pb :no-migration pn}))))
  (print (emit results)))
