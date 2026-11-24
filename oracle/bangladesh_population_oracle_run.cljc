;; One-time measurement oracle for AWAI-3361 (2026-11-24).
;;
;; Run from the repo root:
;;   basilisp run oracle/bangladesh_population_oracle_run.cljc > examples/generated/bangladesh-population-aging-results.edn
;; Also runnable on JVM Clojure or Babashka (top-level forms execute on load).
;; Pure clojure.core only: no host interop, no reader conditionals, no IO
;; inside (stdout carries the EDN; the shell redirects it).
;; Avoid same-name shadowing between loop vars and coefficient bindings
;; (Basilisp misbehaves: see AWAI-2811 notes).
;;
;; NOT part of CI/gate/build/workflow. dynamics CI only runs
;; test/run_tests.cljk via nbb; nothing invokes this file.
;;
;; Parameters mirror examples/bangladesh-population-aging-scenarios.edn (single
;; source of truth). Before running, confirm the mirror holds:
;;   grep -e ':birth-rate 0.0198' -e ':death-rate 0.0058' \
;;        -e ':aging-coef 0.00272885' -e ':p0 165.158616' -e ':e0 9.707301' \
;;        -e ':net-migration -0.600682' \
;;        examples/bangladesh-population-aging-scenarios.edn
;;
;; Base-year consistency (persons, exact integer asserts below):
;; Census 2022 (enumerated, census night 2022-06-15): P 165,158,616 = 47,242,685
;; (0-14) + 108,208,630 (15-64, NatVol1 Table 3.2.10) + 9,707,301 (65+).
;; Cross-checks: 65+ share 5.878% (~Prelim Table 2.12 5.89), dependency ratio
;; 52.63 (NatVol1 Table 3.2.10), aging index 20.55.

(ns bangladesh-population-oracle-run)

;; --- measured person counts (BBS Census 2022, see scenarios EDN pins) -----
(def census-2022-total 165158616)
(def census-2022-0-14 47242685)
(def census-2022-15-64 108208630)
(def census-2022-65plus 9707301)
(def census-2022-65-69 3927239)
(def census-2022-70-74 2831889)
(def census-2022-75plus 2948173)
(def census-2011-total 144043697)
(def census-2011-65plus-thousands 6834) ; Table-5.1: (1150+964+2493+2227)
(def svrs-cbr-per-1000 19.8)
(def svrs-cdr-per-1000 5.8)
(def svrs-intl-in-per-1000 2.972)
(def svrs-intl-out-per-1000 6.609)
(def elder-death-multiple 3.0)          ; Tier A/B 共有仮定 (実測ではない)
(def years-between-censuses 11.25)      ; 2011-03-15 -> 2022-06-15

(def common
  {:p0 165.158616 :e0 9.707301 :dt 0.05 :years 10.0
   :checkpoints [0 1 5 10]
   :birth-rate 0.0198 :death-rate 0.0058 :net-migration -0.600682
   :aging-coef 0.00272885 :elder-death-multiple 3.0})

(def scenario-order
  ["baseline-2022-rates" "no-migration" "lower-fertility" "faster-aging"])

(def scenarios
  {"baseline-2022-rates" {}
   "no-migration" {:net-migration 0.0}
   "lower-fertility" {:birth-rate 0.01782}
   "faster-aging" {:aging-coef 0.00409328}})

(defn- abs [x] (if (neg? x) (- x) x))

(defn- round3 [x]
  (/ (double (long (+ (* x 1000.0) 0.5))) 1000.0))

;; --- measured-base asserts: 人の合計と公表値との照合 (計算はここで行う) ---
(when-not (= census-2022-total
             (+ census-2022-0-14 census-2022-15-64 census-2022-65plus))
  (throw (ex-info "age classes do not sum to total"
                  {:sum (+ census-2022-0-14 census-2022-15-64 census-2022-65plus)})))

(when-not (= census-2022-65plus
             (+ census-2022-65-69 census-2022-70-74 census-2022-75plus))
  (throw (ex-info "65+ breakdown does not sum"
                  {:sum (+ census-2022-65-69 census-2022-70-74 census-2022-75plus)})))

(let [share (* 100.0 (/ census-2022-65plus census-2022-total))
      aging-idx (* 100.0 (/ census-2022-65plus census-2022-0-14))
      dep (* 100.0 (/ (+ census-2022-0-14 census-2022-65plus) census-2022-15-64))]
  (when-not (and (< (abs (- share 5.89)) 0.05)
                 (< (abs (- dep 52.63)) 0.02)
                 (< (abs (- aging-idx 20.548)) 0.001))
    (throw (ex-info "published-value mismatch"
                    {:share share :aging-idx aging-idx :dep dep}))))

;; aging-coef の導出 (2011->2022 中点法、Brazil 2010->2022 と同形の11.25年化):
;; a = (ΔE/年 + m·d·E中) / (P中 - E中)。E2011 は千人丸め値 (報告済み)。
(let [{:keys [death-rate aging-coef elder-death-multiple]} common
      m elder-death-multiple
      p-mid (/ (+ census-2022-total census-2011-total) 2000000.0)
      e-mid (/ (+ census-2022-65plus (* census-2011-65plus-thousands 1000)) 2000000.0)
      a-derived (/ (+ (/ (- census-2022-65plus (* census-2011-65plus-thousands 1000))
                          (* years-between-censuses 1000000.0))
                      (* m death-rate e-mid))
                   (- p-mid e-mid))]
  (when-not (< (abs (- a-derived aging-coef)) 0.000001)
    (throw (ex-info "aging-coef mismatch" {:derived a-derived :stated aging-coef}))))

;; net-migration の導出: SVRS の千人率差 × P0 (換算はここで行う)。
(let [{:keys [p0 net-migration]} common
      m-derived (* (/ (- svrs-intl-in-per-1000 svrs-intl-out-per-1000) 1000.0) p0)]
  (when-not (< (abs (- m-derived net-migration)) 0.000001)
    (throw (ex-info "net-migration mismatch" {:derived m-derived :stated net-migration}))))

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
  (str "#:model{:id :bangladesh-population-aging,\n"
       "        :as-of \"2026-11-24\",\n"
       "        :interpretation\n"
       "        \"2-stock Euler integration (dt 0.05yr x 10yr), dependency-free one-time oracle. "
       "Base year is the enumerated Population and Housing Census 2022 (census night 2022-06-15): "
       "165,158,616 persons, 9,707,301 aged 65+ (5.88%). Birth/death rates are SVRS 2022 "
       "national CBR 19.8 / CDR 5.8 per 1000 (BBS, weighted final); net migration is the SVRS 2022 "
       "international net rate (2.972 - 6.609 per 1000, times P0 = -0.600682M/yr); the aging coefficient "
       "is derived from the 2011->2022 census change (midpoint method over 11.25yr, elder multiple 3.0 "
       "assumed, same as Tier A/B; 2011 65+ is thousands-rounded). The PEC-adjusted population "
       "(169.83M) and the SVRS 1-July estimate are not used as the base (enumerated census count). "
       "Year-1 checked for directional/magnitude consistency only.\",\n"
       "        :oracle {:script \"oracle/bangladesh_population_oracle_run.cljc (one-time oracle, not CI/gate)\",\n"
       "                 :runtime \"basilisp, macOS arm64\",\n"
       "                 :source-edn \"examples/bangladesh-population-aging-scenarios.edn (mirror asserted by grep before run)\"},\n"
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
      (when-not (and (<= 150.0 pv 210.0) (<= 8.0 ev 16.0) (<= ev pv))
        (throw (ex-info "bounds broken" {:scenario n :cp cp})))))
  ;; year-1: 人口+1.72M (SVRS推計の年増と同程度の規模。水準は列挙/調整差で一致させない)、65+ +0.26M/年
  (let [p1 (first (get-in results ["baseline-2022-rates" 1]))
        e1 (second (get-in results ["baseline-2022-rates" 1]))]
    (when-not (and (< (abs (- p1 166.882)) 0.05)
                   (< (abs (- e1 9.962)) 0.05))
      (throw (ex-info "year-1 validation failed" {:p1 p1 :e1 e1}))))
  (let [pb (first (get-in results ["baseline-2022-rates" 10]))
        pn (first (get-in results ["no-migration" 10]))
        pl (first (get-in results ["lower-fertility" 10]))
        sb (/ (second (get-in results ["baseline-2022-rates" 10]))
              (first (get-in results ["baseline-2022-rates" 10])))
        sf (/ (second (get-in results ["faster-aging" 10]))
              (first (get-in results ["faster-aging" 10])))]
    (when-not (and (> pb pl) (> pn pb) (> sb 0.0588) (> sf sb))
      (throw (ex-info "ordering broken" {:baseline-p pb :nomig-p pn :lower-fert-p pl}))))
  (print (emit results)))
