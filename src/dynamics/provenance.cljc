(ns dynamics.provenance
  "Fail-closed provenance labeling for measured/observed parameters vs
   coefficient-free scenario assumptions (AWAI-2800 / AWAI-2838, NEG-2838-04).

   Rule: `AWAI-2838 / coefficient-free-scenario-label`
         (`coefficient-free-zone-never-presented-as-measured`)
   Failure mode: `scenario-assumption-reported-as-observed-parameter`
   Fail-closed action: `output-relabeled-scenario-pending-data`
   Minimal verification: `assert-measured-vs-scenario-tag-per-output`

   Design principle (portable Clojure / ClojureScript / Babashka / nbb):
   - Zero network I/O, zero host interop, zero personal data (no PII).
   - Inspects ALL accepted tag and boolean assertion fields together (never
     short-circuiting on the first present key) and BOTH `:parameters` and
     `:inputs` together (never short-circuiting on an empty collection).
   - Rejects unknown/bogus tag values (`:bogus`, etc.) and any contradictory
     measured + scenario combination in any field order.
   - A parameter or output may carry `:measured` or `:observed` ONLY when it
     has dated, non-placeholder empirical provenance (`:source` + `:as-of`),
     carries no scenario/assumption/estimate/unmeasured/bogus flags or tags,
     and every contributing entry across `:parameters` and `:inputs` is itself
     empirically measured.
   - Any violation fails closed: `enforce-output-provenance` relabels ALL
     present tag fields to `:scenario-pending-data` and clears `:measured?` /
     `:observed?` to `false` (leaving zero measured assertions on the output),
     while `validate-output-provenance!` and
     `assert-measured-vs-scenario-tag-per-output` / `!` reject it."
  (:require [clojure.string :as str]))

(def rule-id
  :AWAI-2838/coefficient-free-scenario-label)

(def pending-data-tag
  :scenario-pending-data)

(def measured-tags
  #{:measured :observed})

(def scenario-tags
  #{:scenario
    :scenario-assumption
    :scenario-not-forecast
    :scenario-pending-data
    :unmeasured})

(def valid-output-tags
  #{:measured
    :observed
    :scenario
    :scenario-assumption
    :scenario-not-forecast
    :scenario-pending-data})

(def tag-keys
  [:provenance/tag
   :provenance
   :tag
   :classification
   :model/classification
   :archetype/classification])

(def measured-boolean-keys
  [:measured? :observed?])

(def scenario-boolean-keys
  [:scenario? :assumption? :estimate? :coefficient-free? :unmeasured?])

(def ^:private non-measured-statuses
  #{:unmeasured
    :unobserved
    :unidentifiable
    :assumed
    :derived
    :held-constant
    :scenario
    :scenario-assumption
    :scenario-not-forecast
    :scenario-pending-data})

(def ^:private unmeasured-values
  #{:unmeasured
    :unobserved
    :unidentifiable
    :uncomputable-until-measured})

(def ^:private non-empirical-source-tokens
  #{"assumption" "assumed" "scenario" "scenario-assumption"
    "scenario-not-forecast" "scenario-pending-data"
    "unmeasured" "unmeasured-assumption" "unobserved" "unidentifiable"
    "estimate" "estimated" "placeholder" "fabricated" "synthetic"
    "tbd" "todo" "unknown" "none" "n/a" "pending" "pending-data"})

(def ^:private non-empirical-source-prefixes
  ["assumption" "assumed" "unmeasured" "unobserved" "scenario"
   "placeholder" "pending" "tbd" "todo"])

(defn- finite-year-or-number? [x]
  (and (number? x) (== x x) (< -1e9 x 1e9)))

(defn empirical-source?
  "True iff `source` is a non-blank string naming an empirical source rather
   than an assumption/scenario/unmeasured placeholder."
  [source]
  (and (string? source)
       (let [trimmed (str/trim source)
             lower (str/lower-case trimmed)]
         (and (not (str/blank? trimmed))
              (not (contains? non-empirical-source-tokens lower))
              (not (some #(str/starts-with? lower %) non-empirical-source-prefixes))))))

(defn valid-as-of?
  "True iff `as-of` is a non-blank date/period string (not a placeholder) or a
   finite numeric year."
  [as-of]
  (or (finite-year-or-number? as-of)
      (and (string? as-of)
           (let [trimmed (str/trim as-of)
                 lower (str/lower-case trimmed)]
             (and (not (str/blank? trimmed))
                  (not (contains? non-empirical-source-tokens lower)))))))

(defn collect-tag-claims
  "Inspect ALL accepted tag keys and boolean assertion keys on `entry` without
   short-circuiting. Surfaces every present tag, any invalid/bogus tag, any
   measured claim, any scenario/coefficient-free claim, and any contradiction."
  [entry]
  (if-not (map? entry)
    {:present-tags []
     :measured-tag-entries []
     :scenario-tag-entries []
     :invalid-tag-entries []
     :measured-bools []
     :scenario-bools []
     :has-measured-claim? false
     :has-scenario-claim? false
     :contradictory? false
     :has-any-claim? false}
    (let [present-tags (vec (for [k tag-keys
                                  :when (contains? entry k)
                                  :let [v (get entry k)]
                                  :when (some? v)]
                              [k v]))
          measured-tag-entries (filterv #(contains? measured-tags (second %)) present-tags)
          scenario-tag-entries (filterv #(contains? scenario-tags (second %)) present-tags)
          invalid-tag-entries (filterv #(not (contains? valid-output-tags (second %))) present-tags)
          measured-bools (filterv #(true? (get entry %)) measured-boolean-keys)
          scenario-bools (filterv #(boolean (get entry %)) scenario-boolean-keys)
          status-measured? (contains? measured-tags (:status entry))
          status-non-measured? (contains? non-measured-statuses (:status entry))
          val-unmeasured? (contains? unmeasured-values (:value entry))
          retained-assumptions? (boolean (seq (:assumptions-retained entry)))
          has-measured-claim? (boolean (or (seq measured-tag-entries)
                                           (seq measured-bools)
                                           status-measured?))
          has-scenario-claim? (boolean (or (seq scenario-tag-entries)
                                           (seq scenario-bools)
                                           status-non-measured?
                                           val-unmeasured?
                                           retained-assumptions?))]
      {:present-tags present-tags
       :measured-tag-entries measured-tag-entries
       :scenario-tag-entries scenario-tag-entries
       :invalid-tag-entries invalid-tag-entries
       :measured-bools measured-bools
       :scenario-bools scenario-bools
       :status-measured? status-measured?
       :has-measured-claim? has-measured-claim?
       :has-scenario-claim? has-scenario-claim?
       :contradictory? (and has-measured-claim? has-scenario-claim?)
       :has-any-claim? (boolean (or (seq present-tags)
                                    (seq measured-bools)
                                    status-measured?))})))

(defn extract-claimed-tag
  "Extract the primary provenance tag claimed by `entry`, preferring any
   measured claim when contradictory fields coexist so callers inspecting this
   value never overlook a measured assertion."
  [entry]
  (let [{:keys [present-tags measured-tag-entries invalid-tag-entries
                measured-bools status-measured?]}
        (collect-tag-claims entry)]
    (or (some-> invalid-tag-entries first second)
        (some-> measured-tag-entries first second)
        (some-> present-tags first second)
        (when (some #{:observed?} measured-bools) :observed)
        (when (some #{:measured?} measured-bools) :measured)
        (when status-measured? (:status entry)))))

(defn- assumption-flags [entry]
  (when (map? entry)
    (cond-> []
      (:scenario? entry) (conj :flagged-scenario)
      (:assumption? entry) (conj :flagged-assumption)
      (:estimate? entry) (conj :flagged-estimate)
      (:coefficient-free? entry) (conj :flagged-coefficient-free)
      (:unmeasured? entry) (conj :flagged-unmeasured)
      (contains? non-measured-statuses (:status entry)) (conj :non-measured-status)
      (contains? unmeasured-values (:value entry)) (conj :unmeasured-value)
      (seq (:assumptions-retained entry)) (conj :retained-assumptions)
      (contains? #{:unmeasured-assumption :assumption :scenario :unmeasured :unobserved}
                 (:source entry))
      (conj :non-empirical-source))))

(declare measured-provenance-failures)

(defn- extract-collection-entries [v]
  (cond
    (nil? v) []
    (map? v) (vec (vals v))
    (sequential? v) (vec v)
    (set? v) (vec v)
    :else [v]))

(defn- parameter-entries
  "Collect ALL entries from BOTH `:parameters` and `:inputs` without ever
   short-circuiting on an empty collection (e.g. `:parameters []` with
   non-empty `:inputs`)."
  [entry]
  (into (extract-collection-entries (:parameters entry))
        (extract-collection-entries (:inputs entry))))

(defn- param-measured-ok? [p]
  (cond
    (contains? unmeasured-values p) false
    (contains? scenario-tags p) false
    (map? p)
    (let [{:keys [invalid-tag-entries has-scenario-claim? contradictory?]}
          (collect-tag-claims p)]
      (and (empty? invalid-tag-entries)
           (not has-scenario-claim?)
           (not contradictory?)
           (empty? (measured-provenance-failures p))))
    :else false))

(defn measured-provenance-failures
  "Return a vector of concrete reasons why `entry` cannot be presented as
   `:measured` / `:observed`. Empty vector means empirical provenance is valid."
  [entry]
  (if-not (map? entry)
    [:not-a-map]
    (let [src (or (:source entry) (get-in entry [:measured-provenance :source]))
          as-of (or (:as-of entry)
                    (:model/as-of entry)
                    (get-in entry [:measured-provenance :as-of]))
          {:keys [invalid-tag-entries scenario-tag-entries contradictory?]}
          (collect-tag-claims entry)
          flags (assumption-flags entry)
          params (parameter-entries entry)
          bad-params? (and (seq params) (not (every? param-measured-ok? params)))]
      (vec
       (distinct
        (cond-> (vec flags)
          (seq invalid-tag-entries) (conj :invalid-provenance-tag)
          (seq scenario-tag-entries) (conj :flagged-scenario)
          contradictory? (conj :contradictory-measured-claim)
          (not (empirical-source? src)) (conj :missing-empirical-source)
          (not (valid-as-of? as-of)) (conj :missing-as-of-date)
          bad-params? (conj :contains-unmeasured-or-scenario-parameter)))))))

(defn has-measured-provenance?
  "True iff `entry` carries dated empirical provenance, has no scenario,
   coefficient-free, bogus, or contradictory tags/flags, and every contributing
   entry across `:parameters` and `:inputs` is itself empirically measured."
  [entry]
  (and (map? entry)
       (empty? (measured-provenance-failures entry))))

(defn coefficient-free-zone?
  "True iff `entry` falls in a coefficient-free or assumption-bearing zone and
   therefore must NEVER be presented as `:measured` or `:observed`."
  [entry]
  (not (has-measured-provenance? entry)))

(defn- relabel-all-provenance-keys
  "Fail-closed sanitizer: overwrites EVERY present tag key in `tag-keys` to
   `:scenario-pending-data`, always sets `:provenance/tag :scenario-pending-data`,
   clears any present `:measured?`/`:observed?` to `false`, and overwrites
   `:status` if it held `:measured`/`:observed`. Guarantees zero measured or
   observed assertions remain anywhere on the relabeled output."
  [entry]
  (let [with-tags (reduce (fn [acc k]
                            (if (contains? acc k)
                              (assoc acc k pending-data-tag)
                              acc))
                          (assoc entry :provenance/tag pending-data-tag)
                          tag-keys)
        with-bools (reduce (fn [acc k]
                             (if (contains? acc k)
                               (assoc acc k false)
                               acc))
                           with-tags
                           measured-boolean-keys)]
    (if (contains? measured-tags (:status with-bools))
      (assoc with-bools :status pending-data-tag)
      with-bools)))

(defn enforce-output-provenance
  "Enforce `AWAI-2838 / coefficient-free-scenario-label` on a single output or
   parameter map (fail-closed relabel mode).

   - Rejects unknown/bogus tags on ANY accepted tag key (even when `:measured?`
     is true and empirical `:source`/`:as-of` are present).
   - Rejects contradictory combinations (e.g. `:provenance/tag :scenario-not-forecast`
     with `:classification :measured`, or reverse order, or `:measured? true`
     paired with `:coefficient-free? true` / scenario tags).
   - Inspects BOTH `:parameters` and `:inputs` (never short-circuiting on empty `[]`).
   - Verified `:measured` / `:observed` outputs (backed by valid empirical
     provenance across all fields) retain their tag with `:provenance/relabeled? false`.
   - Verified scenario outputs (carrying only valid scenario tags and zero
     measured assertions) retain their tag with `:provenance/relabeled? false`.
   - Every violation fails closed: relabels ALL present tag keys to
     `:scenario-pending-data`, sets present `:measured?`/`:observed?` to `false`,
     and attaches machine-readable audit metadata."
  [output]
  (let [entry (if (map? output) output {:value output})
        {:keys [present-tags measured-tag-entries invalid-tag-entries
                measured-bools has-measured-claim? has-scenario-claim?
                contradictory? has-any-claim? status-measured?]}
        (collect-tag-claims entry)
        prior-measured-claim (or (some-> measured-tag-entries first second)
                                 (when (some #{:observed?} measured-bools) :observed)
                                 (when (some #{:measured?} measured-bools) :measured)
                                 (when status-measured? (:status entry)))]
    (cond
      ;; 1. Missing provenance tag entirely -> fail-closed
      (not has-any-claim?)
      (-> (relabel-all-provenance-keys entry)
          (assoc :provenance/status :relabeled-fail-closed
                 :provenance/relabeled? true
                 :provenance/rule rule-id
                 :provenance/failure-mode :missing-provenance-tag
                 :provenance/fail-closed-action :output-relabeled-scenario-pending-data
                 :provenance/prior-claim nil
                 :provenance/reasons (vec (distinct (conj (measured-provenance-failures entry)
                                                          :missing-provenance-tag)))))

      ;; 2. Any bogus / unrecognized tag value on ANY tag key -> fail-closed
      ;;    (checked BEFORE accepting measured or scenario claims!)
      (seq invalid-tag-entries)
      (let [bad-val (second (first invalid-tag-entries))
            reasons (vec (distinct
                          (cond-> [:invalid-provenance-tag]
                            contradictory? (conj :contradictory-measured-claim)
                            has-measured-claim? (into (measured-provenance-failures entry)))))]
        (-> (relabel-all-provenance-keys entry)
            (assoc :provenance/status :relabeled-fail-closed
                   :provenance/relabeled? true
                   :provenance/rule rule-id
                   :provenance/failure-mode :invalid-provenance-tag
                   :provenance/fail-closed-action :output-relabeled-scenario-pending-data
                   :provenance/prior-claim bad-val
                   :provenance/reasons reasons)))

      ;; 3. Contradictory measured + scenario assertions in ANY field order -> fail-closed
      contradictory?
      (let [reasons (vec (distinct (conj (measured-provenance-failures entry)
                                         :contradictory-measured-claim)))]
        (-> (relabel-all-provenance-keys entry)
            (assoc :provenance/status :relabeled-fail-closed
                   :provenance/relabeled? true
                   :provenance/rule rule-id
                   :provenance/failure-mode :scenario-assumption-reported-as-observed-parameter
                   :provenance/fail-closed-action :output-relabeled-scenario-pending-data
                   :provenance/prior-claim prior-measured-claim
                   :provenance/reasons reasons)))

      ;; 4. Measured / observed claim -> verify full empirical provenance across
      ;;    all fields and BOTH :parameters and :inputs
      has-measured-claim?
      (let [reasons (measured-provenance-failures entry)]
        (if (empty? reasons)
          (assoc entry
                 :provenance/tag prior-measured-claim
                 :provenance/status :verified
                 :provenance/relabeled? false
                 :provenance/rule rule-id)
          (-> (relabel-all-provenance-keys entry)
              (assoc :provenance/status :relabeled-fail-closed
                     :provenance/relabeled? true
                     :provenance/rule rule-id
                     :provenance/failure-mode :scenario-assumption-reported-as-observed-parameter
                     :provenance/fail-closed-action :output-relabeled-scenario-pending-data
                     :provenance/prior-claim prior-measured-claim
                     :provenance/reasons reasons))))

      ;; 5. Pure valid scenario tag (zero measured assertions, zero bogus tags)
      (and (seq present-tags)
           (every? #(contains? valid-output-tags (second %)) present-tags)
           (not has-measured-claim?))
      (assoc entry
             :provenance/tag (second (first present-tags))
             :provenance/status :verified
             :provenance/relabeled? false
             :provenance/rule rule-id)

      ;; 6. Defensive fallback -> fail-closed
      :else
      (-> (relabel-all-provenance-keys entry)
          (assoc :provenance/status :relabeled-fail-closed
                 :provenance/relabeled? true
                 :provenance/rule rule-id
                 :provenance/failure-mode :invalid-provenance-tag
                 :provenance/fail-closed-action :output-relabeled-scenario-pending-data
                 :provenance/prior-claim (some-> present-tags first second)
                 :provenance/reasons [:invalid-provenance-tag])))))

(defn validate-output-provenance!
  "Strict fail-closed validator for a single output or parameter map.
   Returns the verified output if valid; throws `ex-info` if a coefficient-free
   or scenario assumption is presented as `:measured`/`:observed`, if tags are
   contradictory, or if any provenance tag is missing or invalid."
  [output]
  (let [enforced (enforce-output-provenance output)]
    (if (:provenance/relabeled? enforced)
      (throw (ex-info (str "AWAI-2838 fail-closed provenance violation ("
                           (name (:provenance/failure-mode enforced))
                           "): output relabeled to :scenario-pending-data")
                      {:error/code (:provenance/failure-mode enforced)
                       :rule rule-id
                       :fail-closed-action :output-relabeled-scenario-pending-data
                       :fail-closed-tag pending-data-tag
                       :prior-claim (:provenance/prior-claim enforced)
                       :reasons (:provenance/reasons enforced)
                       :relabeled-output enforced}))
      enforced)))

(defn assert-measured-vs-scenario-tag-per-output
  "Verify `assert-measured-vs-scenario-tag-per-output` across a collection or
   map `{output-id output-map}` of outputs.

   Every output is verified independently so one measured output can never mask
   a mislabeled, contradictory, bogus-tagged, or untagged output. Returns:
   - `:ok?`           true iff every output carried a valid, honest per-output tag
   - `:measured-ids`  IDs verified as `:measured` / `:observed`
   - `:scenario-ids`  IDs verified as explicit scenario tags
   - `:relabeled-ids` IDs that violated the rule and were relabeled fail-closed
   - `:violations`    per-output violation details
   - `:safe-outputs`  same collection/map shape with all violations relabeled
                      to `:scenario-pending-data` and stripped of measured claims"
  [outputs]
  (let [as-map? (map? outputs)
        entries (if as-map?
                  (vec outputs)
                  (vec (map-indexed (fn [idx item]
                                      [(or (when (map? item) (:id item)) idx) item])
                                    outputs)))
        evaluated (mapv (fn [[id item]] [id (enforce-output-provenance item)]) entries)
        measured-ids (vec (for [[id res] evaluated
                                :when (and (not (:provenance/relabeled? res))
                                           (contains? measured-tags (:provenance/tag res)))]
                            id))
        scenario-ids (vec (for [[id res] evaluated
                                :when (and (not (:provenance/relabeled? res))
                                           (contains? scenario-tags (:provenance/tag res)))]
                            id))
        violations (vec (for [[id res] evaluated
                              :when (:provenance/relabeled? res)]
                          {:id id
                           :failure-mode (:provenance/failure-mode res)
                           :fail-closed-action (:provenance/fail-closed-action res)
                           :fail-closed-tag (:provenance/tag res)
                           :prior-claim (:provenance/prior-claim res)
                           :reasons (:provenance/reasons res)}))
        safe-outputs (if as-map?
                       (into {} evaluated)
                       (mapv second evaluated))]
    {:ok? (empty? violations)
     :rule rule-id
     :verification :assert-measured-vs-scenario-tag-per-output
     :checked-count (count evaluated)
     :measured-ids measured-ids
     :scenario-ids scenario-ids
     :relabeled-ids (mapv :id violations)
     :violations violations
     :safe-outputs safe-outputs}))

(defn assert-measured-vs-scenario-tag-per-output!
  "Strict variant of `assert-measured-vs-scenario-tag-per-output`: throws
   `ex-info` when any output violates `coefficient-free-scenario-label`."
  [outputs]
  (let [report (assert-measured-vs-scenario-tag-per-output outputs)]
    (when-not (:ok? report)
      (throw (ex-info (str "assert-measured-vs-scenario-tag-per-output failed: "
                           (count (:violations report))
                           " output(s) relabeled to :scenario-pending-data")
                      report)))
    report))

(defn- check-observed-evidence-map [evidence]
  (if-not (map? evidence)
    []
    (vec
     (mapcat
      (fn [[k v]]
        (cond
          (= k :unmeasured)
          (if (map? v)
            (for [[uk uv] v
                  :when (and (map? uv)
                             (:has-measured-claim? (collect-tag-claims uv)))]
              {:id [:observed-evidence :unmeasured uk]
               :failure-mode :scenario-assumption-reported-as-observed-parameter
               :fail-closed-tag pending-data-tag
               :reasons [:unmeasured-entry-claims-measured]})
            [])

          (and (map? v)
               (or (contains? non-measured-statuses (:status v))
                   (contains? unmeasured-values (:value v))
                   (= :unmeasured-assumption (:source v))))
          (if (:has-measured-claim? (collect-tag-claims v))
            [{:id [:observed-evidence k]
              :failure-mode :scenario-assumption-reported-as-observed-parameter
              :fail-closed-tag pending-data-tag
              :reasons [:unmeasured-entry-claims-measured]}]
            [])

          :else []))
      evidence))))

(defn validate-model-edn
  "Validate a committed model/results/archetype/tier-b EDN map against
   `AWAI-2838 / coefficient-free-scenario-label` and per-output provenance
   tagging. Returns `{:ok? boolean :kind keyword :violations [...] :outputs-report ...}`."
  [edn-map]
  (cond
    ;; Scenario input or generated results EDN (:model/id present)
    (contains? edn-map :model/id)
    (let [cls (:model/classification edn-map)
          scenarios (or (:model/scenarios edn-map) (:scenarios edn-map))
          cls-violations
          (cond
            (nil? cls)
            [{:id (:model/id edn-map)
              :failure-mode :missing-provenance-tag
              :fail-closed-tag pending-data-tag
              :reasons [:missing-model-classification]}]

            (contains? measured-tags cls)
            [{:id (:model/id edn-map)
              :failure-mode :scenario-assumption-reported-as-observed-parameter
              :fail-closed-tag pending-data-tag
              :prior-claim cls
              :reasons [:scenario-model-classified-as-measured]}]

            (not (contains? scenario-tags cls))
            [{:id (:model/id edn-map)
              :failure-mode :invalid-provenance-tag
              :fail-closed-tag pending-data-tag
              :prior-claim cls
              :reasons [:invalid-model-classification]}]

            :else [])
          ev-violations (check-observed-evidence-map (:model/observed-evidence edn-map))
          per-output (when (map? scenarios)
                       (into {}
                             (for [[sid sdata] scenarios
                                   :let [smap (if (map? sdata) sdata {:value sdata})
                                         claims (collect-tag-claims smap)
                                         inherited (if (:has-any-claim? claims)
                                                     smap
                                                     (cond-> smap
                                                       (some? cls) (assoc :provenance/tag cls)))]]
                               [sid (assoc inherited :scenario? true)])))
          outputs-report (when (seq per-output)
                           (assert-measured-vs-scenario-tag-per-output per-output))
          all-violations (vec (concat cls-violations
                                      ev-violations
                                      (:violations outputs-report)))]
      {:ok? (empty? all-violations)
       :kind :model
       :id (:model/id edn-map)
       :rule rule-id
       :classification cls
       :outputs-report outputs-report
       :violations all-violations})

    ;; Archetype EDN (:archetype/id present)
    (contains? edn-map :archetype/id)
    (let [cls (:archetype/classification edn-map)
          measured-entries (into {}
                                 (for [[k v] (:archetype/measured edn-map)]
                                   [[:measured k] (assoc v :provenance/tag :measured)]))
          estimated-entries (into {}
                                  (for [[k v] (:archetype/estimated edn-map)
                                        :let [claims (collect-tag-claims v)]]
                                    [[:estimated k]
                                     (cond-> (assoc v :estimate? true)
                                       (not (:has-any-claim? claims))
                                       (assoc :provenance/tag :scenario-assumption))]))
          outputs-report (assert-measured-vs-scenario-tag-per-output
                          (merge measured-entries estimated-entries))
          cls-violations (cond
                           (nil? cls)
                           [{:id (:archetype/id edn-map)
                             :failure-mode :missing-provenance-tag
                             :fail-closed-tag pending-data-tag
                             :reasons [:missing-archetype-classification]}]
                           (and (contains? measured-tags cls)
                                (seq (:archetype/estimated edn-map)))
                           [{:id (:archetype/id edn-map)
                             :failure-mode :scenario-assumption-reported-as-observed-parameter
                             :fail-closed-tag pending-data-tag
                             :prior-claim cls
                             :reasons [:estimated-archetype-classified-as-measured]}]
                           :else [])
          all-violations (vec (concat cls-violations (:violations outputs-report)))]
      {:ok? (empty? all-violations)
       :kind :archetype
       :id (:archetype/id edn-map)
       :rule rule-id
       :classification cls
       :outputs-report outputs-report
       :violations all-violations})

    ;; Tier B models EDN (:tier :b present)
    (= :b (:tier edn-map))
    (let [cls (:classification edn-map)
          cls-violations (cond
                           (nil? cls)
                           [{:id (:dataset/id edn-map)
                             :failure-mode :missing-provenance-tag
                             :fail-closed-tag pending-data-tag
                             :reasons [:missing-tier-b-classification]}]
                           (contains? measured-tags cls)
                           [{:id (:dataset/id edn-map)
                             :failure-mode :scenario-assumption-reported-as-observed-parameter
                             :fail-closed-tag pending-data-tag
                             :prior-claim cls
                             :reasons [:tier-b-scenario-classified-as-measured]}]
                           :else [])
          model-claims (vec
                        (for [[iso m] (:models edn-map)
                              :when (and (map? m)
                                         (:has-measured-claim? (collect-tag-claims m)))]
                          {:id iso
                           :failure-mode :scenario-assumption-reported-as-observed-parameter
                           :fail-closed-tag pending-data-tag
                           :reasons [:tier-b-country-model-claims-measured]}))
          all-violations (vec (concat cls-violations model-claims))]
      {:ok? (empty? all-violations)
       :kind :tier-b
       :id (:dataset/id edn-map)
       :rule rule-id
       :classification cls
       :violations all-violations})

    :else
    {:ok? true
     :kind :non-model
     :rule rule-id
     :violations []}))
