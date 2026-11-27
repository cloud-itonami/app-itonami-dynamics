(ns dynamics.xmile-test
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [deftest is testing]]
            [dynamics.xmile :as dx]
            [xmile.model :as m]
            [xmile.validate :as validate]
            [xmile.execute :as execute]))

(def xmile-ns
  {:model m/model :sim-specs m/sim-specs :aux m/aux :flow m/flow
   :stock m/stock :add-variable m/add-variable})

(defn- exp [x]
  #?(:cljs (js/Math.exp x)
     :clj (Math/exp x)))

(deftest acquisition-model-builds-a-valid-real-xmile-model-test
  (testing "the generic acquisition-model, given real params, is a valid XMILE model per xmile.validate"
    (let [built (dx/acquisition-model xmile-ns {:name "test-acq" :inflow-rate 100 :conversion-rate 0.01
                                                 :initial-stock 5 :sim-days 10})]
      (is (validate/valid? (validate/validate built))))))

(deftest acquisition-model-projects-correct-linear-growth-test
  (testing "with a constant inflow*rate and no feedback, the stock grows exactly linearly -- verify against the closed-form answer, not just 'it runs'"
    (let [built (dx/acquisition-model xmile-ns {:name "linear-check" :inflow-rate 200 :conversion-rate 0.05
                                                 :initial-stock 10 :sim-days 20} {:dt 1.0 :method :rk4})
          projected (dx/project execute/run built [0 10 20])
          ;; closed form: stock(t) = initial + inflow*rate*t = 10 + 200*0.05*t = 10 + 10t
          expected {0 10.0 10 110.0 20 210.0}]
      (doseq [[day exp] expected]
        (let [diff (- exp (get-in projected [:checkpoints day]))]
          (is (< (if (neg? diff) (- diff) diff) 1e-6)
              (str "day " day " expected " exp " got " (get-in projected [:checkpoints day]))))))))

(deftest project-omits-checkpoints-past-sim-stop-test
  (testing "a checkpoint day beyond :xmile/stop is silently omitted, not extrapolated"
    (let [built (dx/acquisition-model xmile-ns {:name "short-horizon" :inflow-rate 10 :conversion-rate 0.1
                                                 :initial-stock 0 :sim-days 5})
          projected (dx/project execute/run built [0 5 100])]
      (is (contains? (:checkpoints projected) 0))
      (is (contains? (:checkpoints projected) 5))
      (is (not (contains? (:checkpoints projected) 100))))))

(deftest etzhayyim-f2-real-projection-test
  (testing "the actual etzhayyim F2-upper-bound projection this cycle computed, pinned as a regression check against the real observed numbers (5th observation, 2026-07-21): avg 1846.4 weekly uniques, F2 upper bound 0.00017822024693214808"
    (let [built (dx/acquisition-model xmile-ns
                                       {:name "etzhayyim-adherent-acquisition"
                                        :inflow-rate (/ 1846.4 7.0)
                                        :conversion-rate 0.00017822024693214808
                                        :initial-stock 1
                                        :sim-days 3650})
          projected (dx/project execute/run built [365 1825 3650])]
      ;; even under the MOST OPTIMISTIC rate consistent with zero observed conversions,
      ;; projected adherents after 10 years is still under 200
      (is (< 15 (get-in projected [:checkpoints 365]) 20))
      (is (< 80 (get-in projected [:checkpoints 1825]) 95))
      (is (< 165 (get-in projected [:checkpoints 3650]) 180)))))

(deftest percentage-rate-model-builds-a-valid-real-xmile-model-test
  (testing "the generic percentage-rate-model, given a real rate, is a valid XMILE model"
    (let [built (dx/percentage-rate-model xmile-ns {:name "test-rate" :initial-stock 100 :annual-rate 0.1 :sim-years 5})]
      (is (validate/valid? (validate/validate built))))))

(deftest percentage-rate-model-matches-continuous-exponential-closed-form-test
  (testing "Stock' = Stock * rate integrates to the closed-form continuous exponential S0*e^(rt), not discrete-compound S0*(1+r)^t -- verify against the correct closed form"
    (let [built (dx/percentage-rate-model xmile-ns {:name "exp-check" :initial-stock 1000 :annual-rate 0.2 :sim-years 10} {:dt 0.01 :method :rk4})
          projected (dx/project execute/run built [5 10])
          expected-5 (* 1000 (exp (* 0.2 5)))
          expected-10 (* 1000 (exp (* 0.2 10)))]
      (is (< 0.99 (/ (get-in projected [:checkpoints 5]) expected-5) 1.01))
      (is (< 0.99 (/ (get-in projected [:checkpoints 10]) expected-10) 1.01)))))

(deftest percentage-rate-model-supports-negative-decline-rates-test
  (testing "a negative annual-rate produces real decay, not growth, and stays positive (never crosses zero for a proportional model)"
    (let [built (dx/percentage-rate-model xmile-ns {:name "decline-check" :initial-stock 100 :annual-rate -0.1 :sim-years 20})
          projected (dx/project execute/run built [1 10 20])]
      (is (> (get-in projected [:checkpoints 1]) (get-in projected [:checkpoints 10]) (get-in projected [:checkpoints 20])))
      (is (pos? (get-in projected [:checkpoints 20]))))))

(deftest aca-marketplace-real-decline-projection-test
  (testing "the actual ACA marketplace decline projection this cycle computed, pinned as a regression check: real 2025->2026 enrollment (24.3M -> 23.1M, -4.938% annual rate)"
    (let [rate (- (/ 23.1 24.3) 1)
          built (dx/percentage-rate-model xmile-ns {:name "aca-decline" :initial-stock 23.1 :annual-rate rate :sim-years 30})
          projected (dx/project execute/run built [10])
          crossing (dx/crossing-year execute/run built rate 12.15)]
      (is (< -0.0494 rate -0.0493))
      (is (< 13.9 (get-in projected [:checkpoints 10]) 14.3))
      (is (< 12.5 crossing 13.5)))))

(defn- bass-closed-form [p q M t]
  (let [e (exp (* (- (+ p q)) t))
        f (/ (- 1 e) (+ 1 (* (/ q p) e)))]
    (* M f)))

(deftest bass-diffusion-model-builds-a-valid-real-xmile-model-test
  (testing "the generic bass-diffusion-model, given real params, is a valid XMILE model"
    (let [built (dx/bass-diffusion-model xmile-ns {:name "test-bass" :market-size 1000 :p-coefficient 0.03
                                                     :q-coefficient 0.38 :initial-adopters 0 :sim-time 10})]
      (is (validate/valid? (validate/validate built))))))

(deftest bass-diffusion-model-matches-closed-form-test
  (testing "matches Bass's own closed-form solution A(t) = M * (1-e^-(p+q)t) / (1+(q/p)e^-(p+q)t) to high precision -- not just 'it runs', verified against the textbook analytic answer"
    (let [p 0.03 q 0.38 M 1000
          built (dx/bass-diffusion-model xmile-ns {:name "bass-closed-form-check" :market-size M :p-coefficient p
                                                     :q-coefficient q :initial-adopters 0 :sim-time 20} {:dt 0.02})
          projected (dx/project execute/run built [1 5 10 15 20])]
      (doseq [t [1 5 10 15 20]]
        (let [expected (bass-closed-form p q M t)
              actual (get-in projected [:checkpoints t])]
          (is (< 0.999 (/ actual expected) 1.001)
              (str "t=" t " expected " expected " got " actual)))))))

(deftest bass-diffusion-model-produces-an-s-curve-with-nonzero-q-test
  (testing "a nonzero q (imitation/internal influence) produces acceleration through the middle -- the growth rate in the middle window must exceed the growth rate in the first window, unlike a pure p-only (no-feedback) model which only decelerates"
    (let [built (dx/bass-diffusion-model xmile-ns {:name "s-curve-check" :market-size 1000 :p-coefficient 0.01
                                                     :q-coefficient 0.4 :initial-adopters 0 :sim-time 15} {:dt 0.02})
          projected (dx/project execute/run built [0 2 6 8])
          early-rate (/ (- (get-in projected [:checkpoints 2]) (get-in projected [:checkpoints 0])) 2)
          mid-rate (/ (- (get-in projected [:checkpoints 8]) (get-in projected [:checkpoints 6])) 2)]
      (is (> mid-rate early-rate)))))

(deftest bass-diffusion-model-p-only-decelerates-immediately-like-acquisition-model-test
  (testing "with q=0 (no imitation channel), the model degenerates to pure decelerating adoption from t=0 -- no S-curve acceleration, matching the structural claim in the docstring"
    (let [built (dx/bass-diffusion-model xmile-ns {:name "p-only-check" :market-size 1000 :p-coefficient 0.05
                                                     :q-coefficient 0.0 :initial-adopters 0 :sim-time 10} {:dt 0.02})
          projected (dx/project execute/run built [0 1 2 3])
          rate-1 (- (get-in projected [:checkpoints 1]) (get-in projected [:checkpoints 0]))
          rate-2 (- (get-in projected [:checkpoints 2]) (get-in projected [:checkpoints 1]))
          rate-3 (- (get-in projected [:checkpoints 3]) (get-in projected [:checkpoints 2]))]
      (is (> rate-1 rate-2 rate-3)))))

(def kotoba-network-base-params
  {:name "kotoba-network-effect-base"
   ;; Observed 2026-08-11 starting stocks/assets. Independent means outside
   ;; the owner/operator; owned repos never count as external participants.
   :initial-independent-developers 0
   :owned-capability-providers 57
   :initial-independent-providers 0
   :owned-reusable-components 465
   :initial-independent-components 0
   :initial-active-organizations 5
   :initial-independent-nodes 0
   :operator-nodes 10
   :initial-verified-receipts 0
   ;; Named base-scenario assumptions, not measured rates.
   :developer-market 10000
   :organization-market 1000
   :node-market 1000
   :external-developer-rate 5
   :developer-network-coefficient 0.05
   :provider-productivity 0.1
   :component-productivity 0.25
   :external-organization-rate 1
   :stack-network-coefficient 0.03
   :external-node-rate 0.5
   :node-demand-coefficient 0.05
   :receipts-per-organization 52
   :receipts-per-node 500
   :provider-scale 100
   :component-scale 1000
   :receipt-scale 10000
   :node-scale 100
   :complement-value-weight 0.5
   :technical-base-units 50
   :provider-barrier-weight 1
   :component-barrier-weight 0.1
   :receipt-barrier-weight 0.001
   :organization-barrier-weight 5
   :node-barrier-weight 3
   :entrant-replication-throughput 25
   :entrant-cost-per-unit 100000
   :fixed-technical-cost 3000000
   :sim-years 10})

(deftest network-effect-barrier-model-requires-explicit-honest-inputs-test
  (testing "missing scenario coefficients fail closed instead of silently gaining defaults"
    (is (thrown-with-msg? js/Error
                          #"missing required parameters"
                          (dx/network-effect-barrier-model xmile-ns {:name "incomplete"}))))
  (testing "zero replication throughput is invalid because catch-up time would be undefined"
    (is (thrown-with-msg? js/Error
                          #"must be positive"
                          (dx/network-effect-barrier-model
                           xmile-ns (assoc kotoba-network-base-params
                                           :entrant-replication-throughput 0))))))

(deftest network-effect-barrier-model-is-valid-xmile-and-separates-owned-assets-test
  (let [built (dx/network-effect-barrier-model xmile-ns kotoba-network-base-params)]
    (is (validate/valid? (validate/validate built)))
    (let [result (execute/run built)]
      ;; No independent developer/provider/node is invented at t=0.
      (is (zero? (first (get-in result [:xmile/series "Independent_Developers"]))))
      (is (zero? (first (get-in result [:xmile/series "Independent_Providers"]))))
      (is (zero? (first (get-in result [:xmile/series "Independent_Compute_Nodes"]))))
      ;; Observed owned complements still count as replication work:
      ;; 50 + 57 + .1*465 + 5*5 + 3*10 = 208.5 equivalent units.
      (is (= 208.5 (first (get-in result [:xmile/series "Barrier_Asset_Units"]))))
      (is (= 4.17 (first (get-in result [:xmile/series "Entry_Barrier_Index"]))))
      (is (= 8.34 (first (get-in result [:xmile/series "Entrant_Catchup_Years"]))))
      (is (= 23850000.0 (first (get-in result [:xmile/series "Entrant_Catchup_Cost"]))))
      ;; Owned complements are ecosystem assets, not an independent network.
      (is (> (first (get-in result [:xmile/series "Kotoba_Ecosystem_Value_Multiplier"])) 1))
      (is (= 1.0 (first (get-in result [:xmile/series "Kotoba_Independent_Network_Value_Multiplier"])))))))

(deftest network-effect-summary-computes-uplift-against-zero-feedback-test
  (let [network (dx/network-effect-barrier-model xmile-ns kotoba-network-base-params)
        no-network (dx/network-effect-barrier-model
                    xmile-ns
                    (assoc kotoba-network-base-params
                           :name "kotoba-no-network-counterfactual"
                           :developer-network-coefficient 0
                           :stack-network-coefficient 0
                           :node-demand-coefficient 0))
        summary (dx/network-effect-summary execute/run network no-network [0 5 10])
        y10 (get-in summary [:checkpoints 10])]
    (is (zero? (get-in summary [:checkpoints 0 :developer-uplift])))
    (is (zero? (get-in summary [:checkpoints 0 :organization-uplift])))
    (is (> (:developer-uplift y10) 0))
    (is (> (:developer-uplift-ratio y10) 1))
    (is (> (:organization-uplift y10) 0))
    (is (> (:organization-uplift-ratio y10) 1))
    (is (> (:kotoba-ecosystem-value-multiplier y10) 1))
    (is (> (:kotoba-independent-network-value-multiplier y10) 1))
    (is (> (:new-entrant-catchup-years y10)
           (get-in summary [:checkpoints 0 :new-entrant-catchup-years])))
    (is (> (:entry-barrier-index y10) 1))))

(deftest reinforcing-flow-calibration-inverts-the-xmile-equation-test
  (let [evidence {:start-stock 10
                  :end-stock 16
                  :duration-years 1
                  :market 100
                  :external-rate 2
                  :average-feedback-stock 13
                  :average-complement-index 0.5}
        result (dx/calibrate-reinforcing-flow evidence)
        expected (/ (- (/ 6 0.87) 2) (* 13 0.5))]
    (is (= :calibrated (:status result)))
    (is (< (abs (- expected (:estimate result))) 1e-12))
    (is (= 6 (:observed-rate result)))
    (is (= 6.5 (:feedback-exposure result)))))

(deftest reinforcing-flow-calibration-preserves-evidence-boundaries-test
  (testing "missing observations are unknown rather than a fabricated zero"
    (is (= {:status :unobserved} (dx/calibrate-reinforcing-flow nil)))
    (is (= {:status :unobserved}
           (dx/calibrate-reinforcing-flow {:status :unobserved}))))
  (testing "zero feedback exposure cannot identify a coefficient"
    (let [result (dx/calibrate-reinforcing-flow
                  {:start-stock 0 :end-stock 2 :duration-years 1
                   :market 100 :external-rate 2
                   :average-feedback-stock 0
                   :average-complement-index 0.5})]
      (is (= :unidentifiable (:status result)))
      (is (= :zero-feedback-exposure (:reason result)))
      (is (not (contains? result :estimate)))))
  (testing "a saturated market cannot identify a coefficient from zero flow"
    (let [result (dx/calibrate-reinforcing-flow
                  {:start-stock 100 :end-stock 100 :duration-years 1
                   :market 100 :external-rate 2
                   :average-feedback-stock 100
                   :average-complement-index 1})]
      (is (= :unidentifiable (:status result)))
      (is (= :no-remaining-market (:reason result)))))
  (testing "an observed effect below baseline is visible and bounded at zero"
    (let [result (dx/calibrate-reinforcing-flow
                  {:start-stock 10 :end-stock 11 :duration-years 1
                   :market 100 :external-rate 5
                   :average-feedback-stock 10
                   :average-complement-index 0.5})]
      (is (= :bounded-at-zero (:status result)))
      (is (neg? (:raw-estimate result)))
      (is (zero? (:estimate result)))))
  (testing "a shrinking stock is outside this no-churn adoption model"
    (is (thrown-with-msg? js/Error
                          #"outside the model domain"
                          (dx/calibrate-reinforcing-flow
                           {:start-stock 10 :end-stock 9 :duration-years 1
                            :market 100 :external-rate 2
                            :average-feedback-stock 10
                            :average-complement-index 0.5})))))

(deftest entry-replication-calibration-uses-completed-work-only-test
  (is (= {:status :unobserved} (dx/calibrate-entry-replication [])))
  (let [result (dx/calibrate-entry-replication
                [{:equivalent-units 10 :elapsed-years 0.5 :variable-cost 1000000}
                 {:equivalent-units 20 :elapsed-years 1.0 :variable-cost 3000000}])]
    (is (= :calibrated (:status result)))
    (is (= 2 (:observation-count result)))
    (is (= 20.0 (:entrant-replication-throughput result)))
    (is (< (abs (- (/ 4000000 30) (:entrant-cost-per-unit result))) 1e-9)))
  (is (thrown-with-msg? js/Error
                        #"invalid completed-work observation"
                        (dx/calibrate-entry-replication
                         [{:equivalent-units 0 :elapsed-years 1 :variable-cost 1}]))))

(deftest partial-calibration-never-relabels-retained-assumptions-test
  (let [result (dx/calibrate-network-effect-params
                kotoba-network-base-params
                {:developer-flow
                 {:start-stock 10 :end-stock 16 :duration-years 1
                  :market 100 :external-rate 2
                  :average-feedback-stock 13
                  :average-complement-index 0.5}
                 :organization-flow {:status :unobserved}
                 :node-flow {:status :unobserved}
                 :entrant-replication
                 [{:equivalent-units 10 :elapsed-years 0.5 :variable-cost 1000000}
                  {:equivalent-units 20 :elapsed-years 1 :variable-cost 3000000}]})
        calibrated (:calibrated-params result)]
    (is (= :partially-calibrated (:status result)))
    (is (= #{:developer-network-coefficient
             :entrant-replication-throughput
             :entrant-cost-per-unit}
           (set (keys (:applied result)))))
    (is (= [:stack-network-coefficient :node-demand-coefficient]
           (:assumptions-retained result)))
    (is (= (:stack-network-coefficient kotoba-network-base-params)
           (:stack-network-coefficient calibrated)))
    (is (validate/valid?
         (validate/validate
          (dx/network-effect-barrier-model xmile-ns calibrated))))))

(deftest empty-calibration-retains-all-scenario-assumptions-test
  (let [result (dx/calibrate-network-effect-params kotoba-network-base-params {})]
    (is (= :unobserved (:status result)))
    (is (empty? (:applied result)))
    (is (= kotoba-network-base-params (:calibrated-params result)))
    (is (= 5 (count (:assumptions-retained result))))
    (is (= :scenario-pending-data (:output-provenance result)))
    (is (every? #(= :scenario-pending-data %) (vals (:parameter-provenance result))))))

(deftest xmile-coefficient-free-scenario-label-negative-control-test
  (testing "AWAI-2838 / NEG-2838-04: claiming :measured on a coefficient-free Bass or network model fails closed"
    (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                          #"cannot be labeled as measured/observed"
                          (dx/bass-diffusion-model
                           xmile-ns
                           {:name "bad-bass" :market-size 1000 :p-coefficient 0.03
                            :q-coefficient 0.38 :initial-adopters 0 :sim-time 10
                            :scenario? true :provenance/tag :measured}))))
  (testing "tag-projection-output relabels coefficient-free projection claiming :observed to :scenario-pending-data"
    (let [built (dx/bass-diffusion-model xmile-ns {:name "scenario-bass" :market-size 1000
                                                   :p-coefficient 0.03 :q-coefficient 0.38
                                                   :initial-adopters 0 :sim-time 10})
          projected (dx/project execute/run built [5 10])
          tagged (dx/tag-projection-output {:output projected
                                            :claimed-tag :observed
                                            :scenario? true})]
      (is (= :scenario-pending-data (:provenance/tag tagged)))
      (is (true? (:provenance/relabeled? tagged)))
      (is (= :scenario-assumption-reported-as-observed-parameter
             (:provenance/failure-mode tagged))))))
