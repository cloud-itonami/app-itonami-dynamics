;; One-time measurement oracle for JUN-1162 (2026-11-12).
;;
;; Run from the repo root:
;;   basilisp run oracle/corruption_oracle_run.cljc > examples/generated/corruption-discretion-accountability-results.edn
;; Also runnable on JVM Clojure or Babashka (top-level forms execute on load).
;; Pure clojure.core only: no host interop, no reader conditionals, no IO
;; inside (stdout carries the EDN; the shell redirects it).
;;
;; NOT part of CI/gate/build/workflow. dynamics CI only runs
;; test/run_tests.cljk via nbb; nothing invokes this file.
;;
;; Parameters mirror
;; examples/corruption-discretion-accountability-scenarios.edn (that EDN is
;; the single source of truth). Before running, confirm the mirror holds:
;;   grep -e ':b1-onset 0.35' -e ':monopoly 0.90' -e ':accountability 0.75' \
;;        -e ':c0 0.05' -e ':sim-years 10' \
;;        examples/corruption-discretion-accountability-scenarios.edn

(ns corruption-oracle-run)

(def common
  {:c0 0.05 :h0 0.10
   :b1 0.35 :b2 0.50 :a1 0.60 :a2 0.70
   :dt 0.05 :years 10.0 :checkpoints [0 1 3 5 10]})

(def scenario-order
  ["centralized-low-accountability" "reform-mid"
   "polycentric-high-accountability"])

(def scenarios
  {"centralized-low-accountability"
   {:monopoly 0.90 :discretion 0.85 :accountability 0.15
    :shield 0.80 :norm 0.90}
   "reform-mid"
   {:monopoly 0.60 :discretion 0.60 :accountability 0.45
    :shield 0.50 :norm 0.40}
   "polycentric-high-accountability"
   {:monopoly 0.35 :discretion 0.40 :accountability 0.75
    :shield 0.30 :norm 0.20}})

(defn- abs [x] (if (neg? x) (- x) x))

(defn- round6 [x]
  (/ (double (long (+ (* x 1000000.0) 0.5))) 1000000.0))

(defn simulate [p]
  (let [{:keys [c0 h0 b1 b2 a1 a2 dt years checkpoints]} common
        n (long (+ (/ years dt) 0.5))
        tol (+ (/ dt 2) 1e-12)]
    (loop [i 0 c c0 h h0 traj {0 [(round6 c0) (round6 h0)]}]
      (if (>= i n)
        traj
        (let [acc-eff (* (:accountability p) (- 1 (* (:shield p) h)))
              pressure (* (:monopoly p) (:discretion p)
                          (+ 1 (* (:norm p) c)))
              dc (- (* b1 pressure (- 1 c)) (* b2 acc-eff c))
              dh (- (* a1 c (- 1 h)) (* a2 (:accountability p) h))
              c2 (min 1.0 (max 0.0 (+ c (* dc dt))))
              h2 (min 1.0 (max 0.0 (+ h (* dh dt))))
              t (* (inc i) dt)
              hit (first (filter #(and (not (contains? traj %))
                                       (< (abs (- t %)) tol))
                                 checkpoints))]
          (recur (inc i) c2 h2
                 (if hit (assoc traj hit [(round6 c2) (round6 h2)]) traj)))))))

(defn- fmt-point [[c h]] (str "[" c " " h "]"))

(defn- emit [results]
  (str "#:model{:id :corruption-discretion-accountability,\n"
       "        :as-of \"2026-11-12\",\n"
       "        :classification :scenario-not-forecast,\n"
       "        :interpretation\n"
       "        \"2-stock Euler integration (dt 0.05yr x 10yr), dependency-free one-time oracle. \"\n"
       "        \"Coefficients are sensitivity assumptions, not measured rates. \"\n"
       "        \"Read ordering and intervention direction only.\",\n"
       "        :oracle {:script \"oracle/corruption_oracle_run.cljc (one-time oracle, not CI/gate)\",\n"
       "                 :runtime \"basilisp 0.5.1, macOS arm64\",\n"
       "                 :source-edn \"examples/corruption-discretion-accountability-scenarios.edn (mirror asserted by grep before run)\"},\n"
       "        :checkpoints-years [0 1 3 5 10],\n"
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
    (when-not (every? (fn [[c h]] (and (<= 0.0 c 1.0) (<= 0.0 h 1.0)))
                      (vals traj))
      (throw (ex-info "stock out of bounds" {:scenario n}))))
  (let [c10 (into {} (map (fn [n] [n (first (get-in results [n 10]))])
                          scenario-order))]
    (when-not (> (get c10 "centralized-low-accountability")
                 (get c10 "reform-mid")
                 (get c10 "polycentric-high-accountability"))
      (throw (ex-info "ordering invariant broken" {:c10 c10}))))
  (print (emit results)))
