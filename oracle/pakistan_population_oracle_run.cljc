;; One-time measurement oracle for AWAI-3360 (2026-11-24).
;;
;; Run from the repo root:
;;   basilisp run oracle/pakistan_population_oracle_run.cljc > examples/generated/pakistan-population-aging-results.edn
;; Also runnable on JVM Clojure or Babashka (top-level forms execute on load).
;; Pure clojure.core only: no host interop, no reader conditionals, no IO
;; inside (stdout carries the EDN; the shell redirects it).
;; Avoid same-name shadowing between loop vars and coefficient bindings
;; (Basilisp misbehaves: see AWAI-2811 notes).
;;
;; NOT part of CI/gate/build/workflow. dynamics CI only runs
;; test/run_tests.cljk via nbb; nothing invokes this file.
;;
;; Parameters mirror examples/pakistan-population-aging-scenarios.edn (single
;; source of truth). Before running, confirm the mirror holds:
;;   grep -e ':birth-rate 0.027' -e ':death-rate 0.0067' \
;;        -e ':aging-coef 0.0013875793' -e ':p0 240.458089' -e ':e0 8.531184' \
;;        -e ':net-migration 0.0' \
;;        examples/pakistan-population-aging-scenarios.edn
;;
;; Base-year consistency (persons, exact integer asserts below):
;; Census 2023 detailed (Table 5): P 240,458,089 = 97,533,739 (0-14, UNDER 15)
;; + 134,393,166 (15-64) + 8,531,184 (65+). Check: 65+ share 3.5479%
;; (~published 3.55 in Key Findings Table 2.12). The PBS headline 241,499,431
;; includes 1,041,342 head-count-only persons with no age detail and is not
;; used as the model base.
;; Census 2017 (Table05n): P 207,684,626, 15-64 116,255,314, 65+ 7,712,816,
;; 0-14 83,716,496 = Total-15-64-65+ (= UNDER 16 88,150,064 - age-15 4,433,568).

(ns pakistan-population-oracle-run)

;; --- measured person counts (PBS Census, see scenarios EDN :model/source-pins) ---
(def census-2023-total 240458089)
(def census-2023-0-14 97533739)
(def census-2023-15-64 134393166)
(def census-2023-65plus 8531184)
(def census-2017-total 207684626)
(def census-2017-0-14 83716496)
(def census-2017-15-64 116255314)
(def census-2017-65plus 7712816)
;; PDS-2020 vital rates (per 1000, reference year 2020); as fractions /1000
(def pds-cbr-per1000 27)
(def pds-cdr-per1000 6.7)
(def net-migration-assumption 0.0) ; PBS publishes no national net migration
(def elder-death-multiple 3.0)      ; Tier A/B shared assumption (not measured)

(def common
  {:p0 240.458089 :e0 8.531184 :dt 0.05 :years 10.0
   :checkpoints [0 1 5 10]
   :birth-rate 0.027 :death-rate 0.0067 :net-migration 0.0
   :aging-coef 0.0013875793 :elder-death-multiple 3.0})

(def scenario-order
  ["baseline-2023-rates" "lower-fertility" "faster-aging"])

(def scenarios
  {"baseline-2023-rates" {}
   "lower-fertility" {:birth-rate 0.0243}
   "faster-aging" {:aging-coef 0.002081369}})

(defn- abs [x] (if (neg? x) (- x) x))

(defn- round3 [x]
  (/ (double (long (+ (* x 1000.0) 0.5))) 1000.0))

;; --- measured-base asserts: person sums and published-value cross-checks ---
(when-not (= census-2023-total
             (+ census-2023-0-14 census-2023-15-64 census-2023-65plus))
  (throw (ex-info "2023 age classes do not sum to total"
                  {:sum (+ census-2023-0-14 census-2023-15-64 census-2023-65plus)})))

(when-not (= census-2017-total
             (+ census-2017-0-14 census-2017-15-64 census-2017-65plus))
  (throw (ex-info "2017 age classes do not sum to total"
                  {:sum (+ census-2017-0-14 census-2017-15-64 census-2017-65plus)})))

(let [share23 (* 100.0 (/ census-2023-65plus census-2023-total))
      share17 (* 100.0 (/ census-2017-65plus census-2017-total))
      rdi23 (* 100.0 (/ census-2023-65plus census-2023-15-64))
      ageidx23 (* 100.0 (/ census-2023-65plus census-2023-0-14))]
  (when-not (and (< (abs (- share23 3.55)) 0.01)
                 (< (abs (- share17 3.71)) 0.01)
                 (< (abs (- rdi23 6.3479)) 0.001)
                 (< (abs (- ageidx23 8.7469)) 0.001))
    (throw (ex-info "published-value mismatch"
                    {:share23 share23 :share17 share17 :rdi23 rdi23 :ageidx23 ageidx23}))))

;; aging-coef derivation (2017->2023 midpoint, Brazil-style 6-year generalisation
;; of the Tier B 1-year formula): a = (ΔE/year + m·d·E_mid) / (P_mid - E_mid).
;; Recomputed here and matched against the EDN coefficient.
(let [{:keys [death-rate aging-coef elder-death-multiple]} common
      m elder-death-multiple
      p-mid (/ (+ (double census-2023-total) (double census-2017-total)) 2.0)
      e-mid (/ (+ (double census-2023-65plus) (double census-2017-65plus)) 2.0)
      a-derived (/ (+ (/ (- (double census-2023-65plus) (double census-2017-65plus)) 6.0)
                      (* m death-rate e-mid))
                   (- p-mid e-mid))]
  (when-not (< (abs (- a-derived aging-coef)) 0.0000005)
    (throw (ex-info "aging-coef mismatch" {:derived a-derived :stated aging-coef}))))

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
  (str "#:model{:id :pakistan-population-aging,\n"
       "        :as-of \"2026-11-24\",\n"
       "        :interpretation\n"
       "        \"2-stock Euler integration (dt 0.05yr x 10yr), dependency-free one-time oracle. "
       "Base year is the PBS 7th Census 2023 detailed population (Table 5): 240,458,089 persons, "
       "8,531,184 aged 65+ (3.55%). The headline 241,499,431 includes 1,041,342 head-count-only "
       "persons with no age detail. Birth/death rates are PBS PDS-2020 (reference 2020: CBR 27/1000, "
       "CDR 6.7/1000) because Census 2023 publishes no vital counts; net migration is a zero "
       "assumption (PBS publishes no national net-migration rate; intercensal residual +0.91M/yr "
       "is contaminated by census coverage change and is not used). The aging coefficient is derived "
       "from the 2017->2023 census change (6-year midpoint, elder multiple 3.0 assumed, same as "
       "Tier A/B). Baseline net-migration 0.0 doubles as the required zero-migration scenario. "
       "Year-1 checked for directional/magnitude consistency only.\",\n"
       "        :oracle {:script \"oracle/pakistan_population_oracle_run.cljc (one-time oracle, not CI/gate)\",\n"
       "                 :runtime \"basilisp, macOS arm64\",\n"
       "                 :source-edn \"examples/pakistan-population-aging-scenarios.edn (mirror asserted by grep before run)\"},\n"
       "        :checkpoints-years [0 1 5 10],\n"
       "        :scenarios\n"
       "        {\n"
       (apply str
              (map (fn [n]
                     (str "         :" n " {:checkpoints {"
                          (apply str
                                 (map (fn [cp]
                                        (str cp " "
                                             (fmt-point (get-in results [n cp]))
                                             " "))
                                      (:checkpoints common)))
                          "}}\n"))
                   scenario-order))
       "         }}\n"))

(let [results (into {} (map (fn [n] [n (simulate (get scenarios n))])
                            scenario-order))
      cps (:checkpoints common)]
  (doseq [[n traj] results]
    (when-not (= (set (keys traj)) (set cps))
      (throw (ex-info "checkpoint mismatch" {:scenario n :keys (keys traj)})))
    (doseq [[cp [pv ev]] traj]
      (when-not (and (<= 235.0 pv 310.0) (<= 8.0 ev 13.0) (<= ev pv))
        (throw (ex-info "bounds broken" {:scenario n :cp cp}))))
  ;; year-1: natural increase (0.027-0.0067)*240.458M ~= 4.89M/yr at m=0
  (let [p1 (first (get-in results ["baseline-2023-rates" 1]))
        e1 (second (get-in results ["baseline-2023-rates" 1]))]
    (when-not (and (< (abs (- p1 245.387)) 0.05)
                   (< (abs (- e1 8.683)) 0.05))
      (throw (ex-info "year-1 validation failed" {:p1 p1 :e1 e1}))))
  (let [pb (first (get-in results ["baseline-2023-rates" 10]))
        pl (first (get-in results ["lower-fertility" 10]))
        eb (second (get-in results ["baseline-2023-rates" 10]))
        ef (second (get-in results ["faster-aging" 10]))]
    (when-not (and (> pb pl) (> ef eb))
      (throw (ex-info "ordering broken" {:baseline-p pb :lower-fert-p pl}))))
  (print (emit results))))
