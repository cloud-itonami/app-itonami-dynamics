;; One-time measurement oracle for AWAI-3359 (2026-11-22).
;;
;; Run from the repo root:
;;   basilisp run oracle/brazil_population_oracle_run.cljc > examples/generated/brazil-population-aging-results.edn
;; Also runnable on JVM Clojure or Babashka (top-level forms execute on load).
;; Pure clojure.core only: no host interop, no reader conditionals, no IO
;; inside (stdout carries the EDN; the shell redirects it).
;; Avoid same-name shadowing between loop vars and coefficient bindings
;; (Basilisp misbehaves: see AWAI-2811 notes).
;;
;; NOT part of CI/gate/build/workflow. dynamics CI only runs
;; test/run_tests.cljk via nbb; nothing invokes this file.
;;
;; Parameters mirror examples/brazil-population-aging-scenarios.edn (single
;; source of truth). Before running, confirm the mirror holds:
;;   grep -e ':birth-rate 0.01251866' -e ':death-rate 0.00740968' \
;;        -e ':aging-coef 0.00602303' -e ':p0 203.080756' -e ':e0 22.169101' \
;;        -e ':net-migration -0.252799' \
;;        examples/brazil-population-aging-scenarios.edn
;;
;; Base-year consistency (persons, exact integer assert below):
;; Censo 2022 (Segunda Apuração, 2022-08-01): P 203,080,756 = 40,129,261 (0-14)
;; + 140,782,394 (15-64, residual) + 22,169,101 (65+). Cross-checks: 65+ share
;; 10.916% (~published 10.9), aging index 55.24 (~published 55.2), 60+ 32,113,490.

(ns brazil-population-oracle-run)

;; --- measured person counts (IBGE, see scenarios EDN :model/source-pins) ---
(def census-2022-total 203080756)
(def census-2022-0-14 40129261)
(def census-2022-15-64 140782394)
(def census-2022-65plus 22169101)
(def census-2010-total 190755799)
(def census-2010-65plus 14081477)
(def rc-2022-births 2542298)
(def rc-2022-deaths 1504763)
(def net-migration-2022 -252799)      ; STI saldo, pessoas/ano
(def elder-death-multiple 3.0)        ; Tier A/B 共有仮定 (実測ではない)

(def common
  {:p0 203.080756 :e0 22.169101 :dt 0.05 :years 10.0
   :checkpoints [0 1 5 10]
   :birth-rate 0.01251866 :death-rate 0.00740968 :net-migration -0.252799
   :aging-coef 0.00602303 :elder-death-multiple 3.0})

(def scenario-order
  ["baseline-2022-rates" "lower-fertility" "faster-aging"])

(def scenarios
  {"baseline-2022-rates" {}
   "lower-fertility" {:birth-rate 0.01126679}
   "faster-aging" {:aging-coef 0.00903455}})

(defn- abs [x] (if (neg? x) (- x) x))

(defn- round3 [x]
  (/ (double (long (+ (* x 1000.0) 0.5))) 1000.0))

;; --- measured-base asserts: 人の合計と公表値との照合 (計算はここで行う) ---
(when-not (= census-2022-total
             (+ census-2022-0-14 census-2022-15-64 census-2022-65plus))
  (throw (ex-info "age classes do not sum to total"
                  {:sum (+ census-2022-0-14 census-2022-15-64 census-2022-65plus)})))

(let [share (* 100.0 (/ census-2022-65plus census-2022-total))
      aging-idx (* 100.0 (/ census-2022-65plus census-2022-0-14))
      rdi65 (* 100.0 (/ census-2022-65plus census-2022-15-64))
      cbr (* 1000.0 (/ rc-2022-births census-2022-total))
      cdr (* 1000.0 (/ rc-2022-deaths census-2022-total))]
  (when-not (and (< (abs (- share 10.9)) 0.05)
                 (< (abs (- aging-idx 55.2)) 0.1)
                 (< (abs (- rdi65 15.747)) 0.001)
                 (< (abs (- cbr 12.52)) 0.01)
                 (< (abs (- cdr 7.41)) 0.01))
    (throw (ex-info "published-value mismatch"
                    {:share share :aging-idx aging-idx :rdi65 rdi65 :cbr cbr :cdr cdr}))))

;; aging-coef の導出 (2010->2022 中点法、Tier B の1年式の12年化):
;; a = (ΔE/年 + m·d·E中) / (P中 - E中)。ここで再計算し、EDNの係数と照合する。
(let [{:keys [birth-rate death-rate aging-coef elder-death-multiple]} common
      m elder-death-multiple
      p-mid (/ (+ census-2022-total census-2010-total) 2000000.0)
      e-mid (/ (+ census-2022-65plus census-2010-65plus) 2000000.0)
      a-derived (/ (+ (/ (- census-2022-65plus census-2010-65plus) 12000000.0)
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
  (str "#:model{:id :brazil-population-aging,\n"
       "        :as-of \"2026-11-22\",\n"
       "        :interpretation\n"
       "        \"2-stock Euler integration (dt 0.05yr x 10yr), dependency-free one-time oracle. "
       "Base year is the measured Censo Demográfico 2022 (Segunda Apuração, 2022-08-01): "
       "203,080,756 persons, 22,169,101 aged 65+ (10.9%). Birth/death rates are 2022 "
       "Estatísticas do Registro Civil occurred counts; net migration is the 2022 STI "
       "saldo (-252,799/yr) from IBGE Projeções Revisão 2024; the aging coefficient is "
       "derived from the 2010->2022 census change (midpoint method, elder multiple 3.0 "
       "assumed, same as Tier A/B). Year-1 checked for directional/magnitude consistency "
       "only (reconciled projection levels sit +3.8% above census and are not matched).\",\n"
       "        :oracle {:script \"oracle/brazil_population_oracle_run.cljc (one-time oracle, not CI/gate)\",\n"
       "                 :runtime \"basilisp, macOS arm64\",\n"
       "                 :source-edn \"examples/brazil-population-aging-scenarios.edn (mirror asserted by grep before run)\"},\n"
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
      (when-not (and (<= 190.0 pv 220.0) (<= 20.0 ev 35.0) (<= ev pv))
        (throw (ex-info "bounds broken" {:scenario n :cp cp})))))
  ;; year-1: 人口+0.79M (調整人口の年増+0.83Mと同程度)、65+ +0.59M/年 (10年平均+0.67M/年)
  (let [p1 (first (get-in results ["baseline-2022-rates" 1]))
        e1 (second (get-in results ["baseline-2022-rates" 1]))]
    (when-not (and (< (abs (- p1 203.867)) 0.05)
                   (< (abs (- e1 22.760)) 0.05))
      (throw (ex-info "year-1 validation failed" {:p1 p1 :e1 e1}))))
  (let [pb (first (get-in results ["baseline-2022-rates" 10]))
        pl (first (get-in results ["lower-fertility" 10]))
        sb (/ (second (get-in results ["baseline-2022-rates" 10]))
              (first (get-in results ["baseline-2022-rates" 10])))
        sf (/ (second (get-in results ["faster-aging" 10]))
              (first (get-in results ["faster-aging" 10])))]
    (when-not (and (> pb pl) (> sb 0.109) (> sf sb))
      (throw (ex-info "ordering broken" {:baseline-p pb :lower-fert-p pl}))))
  (print (emit results)))
