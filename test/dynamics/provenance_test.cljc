(ns dynamics.provenance-test
  "Unit and negative-control tests for AWAI-2838 / NEG-2838-04:
   - Rule: `coefficient-free-scenario-label`
           (`coefficient-free-zone-never-presented-as-measured`)
   - Failure mode: `scenario-assumption-reported-as-observed-parameter`
   - Fail-closed action: `output-relabeled-scenario-pending-data`
   - Minimal verification: `assert-measured-vs-scenario-tag-per-output`

   Note: All political/policy fixtures below are abstract, synthetic, aggregate
   records containing zero personal data (no PII)."
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [deftest is testing]]
            [dynamics.core :as d]
            [dynamics.xmile :as dx]
            [dynamics.provenance :as prov]))

(defn- catch-ex-data [f]
  (try
    (f)
    nil
    (catch #?(:clj clojure.lang.ExceptionInfo :cljs js/Error) e
      (or (ex-data e) {:message (ex-message e)}))))

(defn- has-any-measured-assertion?
  "True if ANY accepted tag key, `:status`, or boolean flag still asserts
   measured/observed provenance on `m`."
  [m]
  (boolean
   (or (some #(contains? prov/measured-tags (get m %)) prov/tag-keys)
       (contains? prov/measured-tags (:status m))
       (true? (:measured? m))
       (true? (:observed? m)))))

(deftest measured-output-with-dated-empirical-provenance-is-verified-test
  (testing "an empirically sourced, dated output preserves its :measured / :observed tag"
    (let [measured-out {:id :jurisdiction-alpha-turnout-2024
                        :value 0.642
                        :provenance/tag :measured
                        :as-of "2024-11-05"
                        :source "National Electoral Commission official turnout gazette 2024"}
          observed-out {:id :jurisdiction-alpha-enacted-bills-2024
                        :value 118
                        :provenance/tag :observed
                        :as-of 2024
                        :source "Parliamentary Official Bulletin 2024 session summary"}
          enforced-m (prov/enforce-output-provenance measured-out)
          enforced-o (prov/enforce-output-provenance observed-out)]
      (is (= :measured (:provenance/tag enforced-m)))
      (is (= :verified (:provenance/status enforced-m)))
      (is (false? (:provenance/relabeled? enforced-m)))
      (is (= :observed (:provenance/tag enforced-o)))
      (is (= :verified (:provenance/status enforced-o)))
      (is (false? (:provenance/relabeled? enforced-o)))
      (is (= enforced-m (prov/validate-output-provenance! measured-out))))))

(deftest explicit-scenario-output-preserves-scenario-tag-test
  (testing "an explicit coefficient-free scenario assumption keeps its scenario tag without relabeling"
    (let [scenario-out {:id :policy-bill-elasticity-sensitivity
                        :value 0.15
                        :provenance/tag :scenario-assumption
                        :scenario? true
                        :note "No empirical elasticity measured; sensitivity assumption only"}
          enforced (prov/enforce-output-provenance scenario-out)]
      (is (= :scenario-assumption (:provenance/tag enforced)))
      (is (= :verified (:provenance/status enforced)))
      (is (false? (:provenance/relabeled? enforced)))
      (is (= enforced (prov/validate-output-provenance! scenario-out))))))

(deftest negative-control-scenario-assumption-reported-as-observed-fails-closed-test
  (testing "a scenario assumption reported as :observed or :measured is relabeled fail-closed to :scenario-pending-data"
    (let [bad-policy-param {:id :bill-passage-lobby-elasticity
                            :value 0.28
                            :provenance/tag :observed
                            :scenario? true
                            :source "Policy simulation sensitivity draft"
                            :as-of "2026-11-28"}
          relabeled (prov/enforce-output-provenance bad-policy-param)
          err (catch-ex-data #(prov/validate-output-provenance! bad-policy-param))]
      (is (= :scenario-pending-data (:provenance/tag relabeled)))
      (is (= :relabeled-fail-closed (:provenance/status relabeled)))
      (is (true? (:provenance/relabeled? relabeled)))
      (is (not (has-any-measured-assertion? relabeled)))
      (is (= :scenario-assumption-reported-as-observed-parameter
             (:provenance/failure-mode relabeled)))
      (is (= :output-relabeled-scenario-pending-data
             (:provenance/fail-closed-action relabeled)))
      (is (= :observed (:provenance/prior-claim relabeled)))
      (is (some #{:flagged-scenario} (:provenance/reasons relabeled)))
      (is (some? err))
      (is (= :scenario-assumption-reported-as-observed-parameter (:error/code err)))
      (is (= :scenario-pending-data (:fail-closed-tag err)))))

  (testing "boolean :measured? true or :observed? true on a coefficient-free zone also fails closed"
    (let [bad-flag-entry {:id :coalition-stability-coef
                          :value 0.42
                          :measured? true
                          :coefficient-free? true}
          relabeled (prov/enforce-output-provenance bad-flag-entry)]
      (is (= :scenario-pending-data (:provenance/tag relabeled)))
      (is (false? (:measured? relabeled)))
      (is (not (has-any-measured-assertion? relabeled)))
      (is (true? (:provenance/relabeled? relabeled)))
      (is (= :scenario-assumption-reported-as-observed-parameter
             (:provenance/failure-mode relabeled))))))

(deftest negative-control-contradictory-and-bogus-tags-fail-closed-test
  (testing "Repro A (Kougi): :provenance/tag :scenario-not-forecast paired with :classification :measured fails closed and strips all measured fields"
    (let [repro-a {:provenance/tag :scenario-not-forecast
                   :classification :measured
                   :coefficient-free? true}
          relabeled (prov/enforce-output-provenance repro-a)
          report (prov/assert-measured-vs-scenario-tag-per-output {:repro-a repro-a})
          strict-err (catch-ex-data #(prov/validate-output-provenance! repro-a))
          bundle-err (catch-ex-data #(prov/assert-measured-vs-scenario-tag-per-output! {:repro-a repro-a}))]
      (is (= :scenario-pending-data (:provenance/tag relabeled)))
      (is (= :scenario-pending-data (:classification relabeled)))
      (is (not (has-any-measured-assertion? relabeled)))
      (is (true? (:provenance/relabeled? relabeled)))
      (is (= :scenario-assumption-reported-as-observed-parameter
             (:provenance/failure-mode relabeled)))
      (is (some #{:contradictory-measured-claim} (:provenance/reasons relabeled)))
      (is (false? (:ok? report)))
      (is (= [:repro-a] (:relabeled-ids report)))
      (is (not (has-any-measured-assertion? (get-in report [:safe-outputs :repro-a]))))
      (is (some? strict-err))
      (is (some? bundle-err))))

  (testing "Repro A reverse order: :provenance/tag :measured paired with :classification :scenario-not-forecast fails closed even with source/as-of"
    (let [repro-a-rev {:provenance/tag :measured
                       :classification :scenario-not-forecast
                       :source "National Statistical Registry 2024"
                       :as-of 2024}
          repro-a-model-cls {:provenance/tag :scenario-assumption
                             :model/classification :observed
                             :measured? true
                             :source "National Statistical Registry 2024"
                             :as-of 2024}]
      (doseq [case-map [repro-a-rev repro-a-model-cls]]
        (let [relabeled (prov/enforce-output-provenance case-map)
              report (prov/assert-measured-vs-scenario-tag-per-output {:x case-map})
              strict-err (catch-ex-data #(prov/validate-output-provenance! case-map))]
          (is (= :scenario-pending-data (:provenance/tag relabeled)))
          (is (not (has-any-measured-assertion? relabeled)))
          (is (true? (:provenance/relabeled? relabeled)))
          (is (false? (:ok? report)))
          (is (some? strict-err))))))

  (testing "Repro B (Kougi): bogus :provenance/tag :bogus paired with :measured? true fails closed and strips :measured?"
    (let [repro-b {:provenance/tag :bogus
                   :measured? true
                   :source "Synthetic census fixture"
                   :as-of 2024}
          relabeled (prov/enforce-output-provenance repro-b)
          report (prov/assert-measured-vs-scenario-tag-per-output {:repro-b repro-b})
          strict-err (catch-ex-data #(prov/validate-output-provenance! repro-b))
          bundle-err (catch-ex-data #(prov/assert-measured-vs-scenario-tag-per-output! {:repro-b repro-b}))]
      (is (= :scenario-pending-data (:provenance/tag relabeled)))
      (is (false? (:measured? relabeled)))
      (is (not (has-any-measured-assertion? relabeled)))
      (is (true? (:provenance/relabeled? relabeled)))
      (is (= :invalid-provenance-tag (:provenance/failure-mode relabeled)))
      (is (= :bogus (:provenance/prior-claim relabeled)))
      (is (false? (:ok? report)))
      (is (= [:repro-b] (:relabeled-ids report)))
      (is (not (has-any-measured-assertion? (get-in report [:safe-outputs :repro-b]))))
      (is (= :invalid-provenance-tag (:error/code strict-err)))
      (is (some? bundle-err))))

  (testing "secondary bogus tag alongside a valid primary scenario tag also fails closed"
    (let [bad-secondary {:provenance/tag :scenario-not-forecast
                         :classification :bogus}
          relabeled (prov/enforce-output-provenance bad-secondary)
          report (prov/assert-measured-vs-scenario-tag-per-output {:bad bad-secondary})]
      (is (= :scenario-pending-data (:provenance/tag relabeled)))
      (is (= :scenario-pending-data (:classification relabeled)))
      (is (true? (:provenance/relabeled? relabeled)))
      (is (= :invalid-provenance-tag (:provenance/failure-mode relabeled)))
      (is (false? (:ok? report))))))

(deftest negative-control-empty-parameters-cannot-mask-inputs-test
  (testing "Repro C (Kougi): empty :parameters [] alongside scenario :inputs fails closed and is rejected in strict mode"
    (let [repro-c {:provenance/tag :measured
                   :source "Synthetic census fixture"
                   :as-of 2024
                   :parameters []
                   :inputs [{:provenance/tag :scenario-assumption
                             :coefficient-free? true}]}
          relabeled (prov/enforce-output-provenance repro-c)
          report (prov/assert-measured-vs-scenario-tag-per-output {:repro-c repro-c})
          strict-err (catch-ex-data #(prov/validate-output-provenance! repro-c))
          bundle-err (catch-ex-data #(prov/assert-measured-vs-scenario-tag-per-output! {:repro-c repro-c}))]
      (is (= :scenario-pending-data (:provenance/tag relabeled)))
      (is (not (has-any-measured-assertion? relabeled)))
      (is (true? (:provenance/relabeled? relabeled)))
      (is (= :scenario-assumption-reported-as-observed-parameter
             (:provenance/failure-mode relabeled)))
      (is (some #{:contains-unmeasured-or-scenario-parameter} (:provenance/reasons relabeled)))
      (is (false? (:ok? report)))
      (is (= [:repro-c] (:relabeled-ids report)))
      (is (not (has-any-measured-assertion? (get-in report [:safe-outputs :repro-c]))))
      (is (= :scenario-assumption-reported-as-observed-parameter (:error/code strict-err)))
      (is (some? bundle-err))))

  (testing "empty map :parameters {} or measured :parameters alongside scenario :inputs also fails closed"
    (doseq [variant [{:provenance/tag :measured
                      :source "Official statistical release 2024"
                      :as-of 2024
                      :parameters {}
                      :inputs [{:provenance/tag :scenario-assumption :scenario? true}]}
                     {:provenance/tag :measured
                      :source "Official statistical release 2024"
                      :as-of 2024
                      :parameters {:p1 {:provenance/tag :measured
                                        :source "Official statistical release 2024"
                                        :as-of 2024}}
                      :inputs [{:provenance/tag :scenario-pending-data :coefficient-free? true}]}
                     {:provenance/tag :measured
                      :source "Official statistical release 2024"
                      :as-of 2024
                      :inputs [{:provenance/tag :measured
                                :source "Official statistical release 2024"
                                :as-of 2024
                                :parameters []
                                :inputs [{:provenance/tag :scenario-assumption :scenario? true}]}]}]]
      (let [relabeled (prov/enforce-output-provenance variant)
            err (catch-ex-data #(prov/validate-output-provenance! variant))]
        (is (= :scenario-pending-data (:provenance/tag relabeled)))
        (is (not (has-any-measured-assertion? relabeled)))
        (is (true? (:provenance/relabeled? relabeled)))
        (is (some #{:contains-unmeasured-or-scenario-parameter} (:provenance/reasons relabeled)))
        (is (some? err))))))

(deftest negative-control-missing-or-placeholder-provenance-fails-closed-test
  (testing "claiming :measured without an empirical :source or :as-of fails closed"
    (doseq [bad-case [{:id :no-source :value 1.2 :provenance/tag :measured :as-of "2026-01-01"}
                      {:id :blank-source :value 1.2 :provenance/tag :measured :source "   " :as-of "2026-01-01"}
                      {:id :placeholder-source :value 1.2 :provenance/tag :observed :source "assumption (TBD)" :as-of "2026-01-01"}
                      {:id :keyword-assumption-source :value 1.2 :provenance/tag :observed :source :unmeasured-assumption :as-of "2026-01-01"}
                      {:id :no-as-of :value 1.2 :provenance/tag :measured :source "Official Electoral Registry 2024"}]]
      (let [relabeled (prov/enforce-output-provenance bad-case)]
        (is (= :scenario-pending-data (:provenance/tag relabeled))
            (str (:id bad-case) " must be relabeled to :scenario-pending-data"))
        (is (not (has-any-measured-assertion? relabeled)))
        (is (true? (:provenance/relabeled? relabeled)))
        (is (= :scenario-assumption-reported-as-observed-parameter
               (:provenance/failure-mode relabeled))))))

  (testing "untagged outputs fail closed to :scenario-pending-data"
    (let [untagged {:id :unlabeled-output :value 42.0}
          relabeled (prov/enforce-output-provenance untagged)]
      (is (= :scenario-pending-data (:provenance/tag relabeled)))
      (is (true? (:provenance/relabeled? relabeled)))
      (is (= :missing-provenance-tag (:provenance/failure-mode relabeled))))))

(deftest negative-control-mixed-measured-and-assumed-parameters-cannot-claim-measured-test
  (testing "AWAI-2800 / AWAI-2838 propagation: an output combining one measured parameter with one coefficient-free assumption CANNOT be labeled :measured"
    (let [mixed-output {:id :policy-valueflow-projection
                        :value 125.4
                        :provenance/tag :measured
                        :as-of "2026-11-28"
                        :source "Parliamentary Budget Office 2025 baseline"
                        :parameters
                        {:enacted-budget-baseline
                         {:value 100.0
                          :provenance/tag :measured
                          :as-of "2025-04-01"
                          :source "Ministry of Finance FY2025 enacted budget"}
                         :policy-compliance-elasticity
                         {:value 0.25
                          :provenance/tag :scenario-assumption
                          :scenario? true}}}
          relabeled (prov/enforce-output-provenance mixed-output)
          err (catch-ex-data #(prov/validate-output-provenance! mixed-output))]
      (is (= :scenario-pending-data (:provenance/tag relabeled)))
      (is (not (has-any-measured-assertion? relabeled)))
      (is (true? (:provenance/relabeled? relabeled)))
      (is (some #{:contains-unmeasured-or-scenario-parameter} (:provenance/reasons relabeled)))
      (is (= :scenario-assumption-reported-as-observed-parameter (:error/code err))))))

(deftest assert-measured-vs-scenario-tag-per-output-test
  (testing "valid mixed output bundle preserves distinct per-output measured vs scenario tags"
    (let [bundle {:turnout-2024
                  {:value 0.642
                   :provenance/tag :measured
                   :as-of "2024-11-05"
                   :source "Electoral Commission 2024 certified aggregate report"}
                  :budget-baseline-2025
                  {:value 100.0
                   :provenance/tag :observed
                   :as-of "2025-04-01"
                   :source "Ministry of Finance FY2025 enacted budget"}
                  :policy-reform-scenario
                  {:value 112.5
                   :provenance/tag :scenario-not-forecast
                   :scenario? true}
                  :unmeasured-feedback-zone
                  {:value 0.08
                   :provenance/tag :scenario-pending-data
                   :coefficient-free? true}}
          report (prov/assert-measured-vs-scenario-tag-per-output! bundle)]
      (is (true? (:ok? report)))
      (is (= 4 (:checked-count report)))
      (is (= #{:turnout-2024 :budget-baseline-2025} (set (:measured-ids report))))
      (is (= #{:policy-reform-scenario :unmeasured-feedback-zone} (set (:scenario-ids report))))
      (is (empty? (:violations report)))
      (is (= :measured (get-in report [:safe-outputs :turnout-2024 :provenance/tag])))
      (is (= :scenario-not-forecast (get-in report [:safe-outputs :policy-reform-scenario :provenance/tag])))))

  (testing "negative control: one mislabeled scenario output in a mixed bundle fails closed and is relabeled without corrupting valid outputs"
    (let [tampered-bundle {:turnout-2024
                           {:value 0.642
                            :provenance/tag :measured
                            :as-of "2024-11-05"
                            :source "Electoral Commission 2024 certified aggregate report"}
                           :policy-reform-scenario
                           {:value 112.5
                            :provenance/tag :scenario-not-forecast
                            :scenario? true}
                           :forged-policy-coef
                           {:value 0.33
                            :provenance/tag :observed
                            :assumption? true
                            :source "Sensitivity guess"
                            :as-of "2026-11-28"}}
          report (prov/assert-measured-vs-scenario-tag-per-output tampered-bundle)
          err (catch-ex-data #(prov/assert-measured-vs-scenario-tag-per-output! tampered-bundle))]
      (is (false? (:ok? report)))
      (is (= [:forged-policy-coef] (:relabeled-ids report)))
      (is (= 1 (count (:violations report))))
      (is (= :scenario-assumption-reported-as-observed-parameter
             (get-in report [:violations 0 :failure-mode])))
      (is (= :scenario-pending-data
             (get-in report [:safe-outputs :forged-policy-coef :provenance/tag])))
      (is (not (has-any-measured-assertion? (get-in report [:safe-outputs :forged-policy-coef]))))
      (is (= :measured
             (get-in report [:safe-outputs :turnout-2024 :provenance/tag])))
      (is (= :scenario-not-forecast
             (get-in report [:safe-outputs :policy-reform-scenario :provenance/tag])))
      (is (some? err))
      (is (false? (:ok? err))))))

(deftest core-and-xmile-fail-closed-guards-test
  (testing "dynamics.core/stock and dynamics.core/flow reject scenario, contradictory, and bogus provenance tags"
    (let [stock-err (catch-ex-data
                     #(d/stock {:id :synthetic-policy-stock
                                :value 10.0
                                :as-of "2026-11-28"
                                :source "Official report"
                                :scenario? true
                                :provenance/tag :measured}))
          stock-contra-err (catch-ex-data
                            #(d/stock {:id :contra-stock
                                       :value 10.0
                                       :as-of "2026-11-28"
                                       :source "Official report"
                                       :provenance/tag :scenario-not-forecast
                                       :classification :measured}))
          stock-bogus-err (catch-ex-data
                           #(d/stock {:id :bogus-stock
                                      :value 10.0
                                      :as-of "2026-11-28"
                                      :source "Official report"
                                      :provenance/tag :bogus
                                      :measured? true}))
          flow-err (catch-ex-data
                    #(d/flow {:id :synthetic-policy-flow
                              :from :a :to :b
                              :rate-estimate 0.2
                              :assumption? true
                              :provenance/tag :observed}))]
      (is (= :scenario-assumption-reported-as-observed-parameter (:error/code stock-err)))
      (is (= :scenario-pending-data (:fail-closed-tag stock-err)))
      (is (= :scenario-assumption-reported-as-observed-parameter (:error/code stock-contra-err)))
      (is (= :invalid-provenance-tag (:error/code stock-bogus-err)))
      (is (= :scenario-assumption-reported-as-observed-parameter (:error/code flow-err)))))

  (testing "dynamics.xmile/calibrate-network-effect-params tags retained assumptions as :scenario-pending-data"
    (let [params {:developer-network-coefficient 0.05
                  :stack-network-coefficient 0.03
                  :node-demand-coefficient 0.05
                  :entrant-replication-throughput 25
                  :entrant-cost-per-unit 100000}
          res (dx/calibrate-network-effect-params
               params
               {:developer-flow {:start-stock 10 :end-stock 16 :duration-years 1
                                 :market 100 :external-rate 2
                                 :average-feedback-stock 13
                                 :average-complement-index 0.5}
                :organization-flow {:status :unobserved}
                :node-flow {:status :unobserved}
                :entrant-replication []})]
      (is (= :partially-calibrated (:status res)))
      (is (= :measured (get-in res [:parameter-provenance :developer-network-coefficient])))
      (is (= :scenario-pending-data (get-in res [:parameter-provenance :stack-network-coefficient])))
      (is (= :scenario-pending-data (get-in res [:parameter-provenance :node-demand-coefficient])))
      (is (= :scenario-pending-data (:output-provenance res)))))

  (testing "dynamics.xmile/tag-projection-output relabels coefficient-free projection claiming :measured to :scenario-pending-data"
    (let [tagged (dx/tag-projection-output
                  {:output {:initial 10.0 :checkpoints {1 12.0 5 20.0}}
                   :claimed-tag :measured
                   :scenario? true
                   :assumptions-retained [:stack-network-coefficient]})]
      (is (= :scenario-pending-data (:provenance/tag tagged)))
      (is (true? (:provenance/relabeled? tagged)))
      (is (= :scenario-assumption-reported-as-observed-parameter
             (:provenance/failure-mode tagged))))))

(deftest validate-model-edn-negative-controls-test
  (testing "a scenario EDN forged to :model/classification :measured fails validation"
    (let [forged {:model/id :synthetic-election-model
                  :model/as-of "2026-11-28"
                  :model/classification :measured
                  :scenarios {:baseline {:label "assumed rates"}}}
          report (prov/validate-model-edn forged)]
      (is (false? (:ok? report)))
      (is (some #(= :scenario-model-classified-as-measured %)
                (mapcat :reasons (:violations report))))))

  (testing "an :unmeasured evidence entry claiming :measured? true fails validation"
    (let [forged {:model/id :synthetic-bill-model
                  :model/as-of "2026-11-28"
                  :model/classification :scenario-not-forecast
                  :model/observed-evidence
                  {:unmeasured {:policy-coef {:value 0.5 :measured? true}}}
                  :scenarios {:baseline {:label "sensitivity"}}}
          report (prov/validate-model-edn forged)]
      (is (false? (:ok? report)))
      (is (some #(= :unmeasured-entry-claims-measured %)
                (mapcat :reasons (:violations report)))))))
