;; One-time measurement oracle for AWAI-2836 (2026-11-12).
;;
;; Run from the repo root:
;;   basilisp run oracle/spain_rent_oracle_run.cljc > examples/generated/spain-rent-burden-results.edn
;; Also runnable on JVM Clojure or Babashka (top-level forms execute on load).
;; Pure clojure.core only: no host interop, no reader conditionals, no IO
;; inside (stdout carries the EDN; the shell redirects it).
;; Avoid same-name shadowing between loop vars and coefficient bindings
;; (Basilisp misbehaves: see AWAI-2811 notes).
;;
;; NOT part of CI/gate/build/workflow. dynamics CI only runs
;; test/run_tests.cljk via nbb; nothing invokes this file.
;;
;; Parameters mirror examples/spain-rent-burden-scenarios.edn (single source
;; of truth). Before running, confirm the mirror holds:
;;   grep -e ':rent-growth 0.115' -e ':rent-growth 0.14' \
;;        -e ':wage-growth 0.035' -e ':coupling 0.5' \
;;        -e ':r0 100.0' -e ':b0 16.7' \
;;        examples/spain-rent-burden-scenarios.edn

(ns spain-rent-oracle-run)

(def common
  {:r0 100.0 :b0 16.7 :dt 0.05 :years 10.0
   :checkpoints [0 1 5 10]
   :wage-growth 0.035 :coupling 0.5})

(def scenario-order
  ["idealista-pace" "fotocasa-pace" "cooling"])

(def scenarios
  {"idealista-pace" {:rent-growth 0.115}
   "fotocasa-pace" {:rent-growth 0.14}
   "cooling" {:rent-growth 0.05}})

(defn- abs [x] (if (neg? x) (- x) x))

(defn- round3 [x]
  (/ (double (long (+ (* x 1000.0) 0.5))) 1000.0))

(defn simulate [pcfg]
  (let [{:keys [r0 b0 dt years checkpoints wage-growth coupling]} common
        gr (get pcfg :rent-growth)
        gw wage-growth
        kk coupling
        n (long (+ (/ years dt) 0.5))
        tol (+ (/ dt 2) 1e-12)]
    (loop [i 0 rent r0 burd b0 traj {0 [(round3 r0) (round3 b0)]}]
      (if (>= i n)
        traj
        (let [dr (* gr rent)
              db (* kk (- gr gw) burd)
              rent2 (+ rent (* dr dt))
              burd2 (max 0.0 (+ burd (* db dt)))
              t (* (inc i) dt)
              hit (first (filter #(and (not (contains? traj %))
                                       (< (abs (- t %)) tol))
                                 checkpoints))]
          (recur (inc i) rent2 burd2
                 (if hit (assoc traj hit [(round3 rent2) (round3 burd2)]) traj)))))))

(defn- fmt-point [[a b]] (str "[" a " " b "]"))

(defn- emit [results]
  (str "#:model{:id :spain-rent-burden,\n"
       "        :as-of \"2026-11-12\",\n"
       "        :interpretation\n"
       "        \"2-stock Euler integration (dt 0.05yr x 10yr), dependency-free one-time oracle. \"\n"
       "        \"Rent growth rates are Idealista/Fotocasa-2024 measured values (secondary press \"\n"
       "        \"citations); the 2024 burden level is Eurostat-measured; wage growth and the \"\n"
       "        \"coupling coefficient are sensitivity assumptions. The observed 2023-2024 burden \"\n"
       "        \"decline (employment/composition effects) is NOT modeled -- a named limitation.\",\n"
       "        :oracle {:script \"oracle/spain_rent_oracle_run.cljc (one-time oracle, not CI/gate)\",\n"
       "                 :runtime \"basilisp, macOS arm64\",\n"
       "                 :source-edn \"examples/spain-rent-burden-scenarios.edn (mirror asserted by grep before run)\"},\n"
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
    (doseq [[cp [rv bv]] traj]
      (when-not (and (<= 80.0 rv 500.0) (<= 5.0 bv 40.0))
        (throw (ex-info "bounds broken" {:scenario n :cp cp})))))
  (let [r1 (first (get-in results ["idealista-pace" 1]))
        b1 (second (get-in results ["idealista-pace" 1]))]
    (when-not (and (< (abs (- r1 112.2)) 4.0)
                   (< (abs (- b1 17.4)) 3.0))
      (throw (ex-info "year-1 validation failed" {:r1 r1 :b1 b1}))))
  (let [rf (first (get-in results ["fotocasa-pace" 10]))
        ri (first (get-in results ["idealista-pace" 10]))
        bb (second (get-in results ["idealista-pace" 10]))
        bc (second (get-in results ["cooling" 10]))]
    (when-not (and (> rf ri) (> bb bc))
      (throw (ex-info "ordering broken" {:fotocasa-r rf :idealista-r ri}))))
  (print (emit results)))
