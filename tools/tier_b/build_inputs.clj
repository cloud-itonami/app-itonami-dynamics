;; AWAI-3384 input builder (babashka only: uses bb's built-in clojure.data.csv).
;;   bb tools/tier_b/build_inputs.clj <captures-dir> <out.edn>
;; Reads the two retained UN WPP 2024 CSV files and writes the Tier B inputs for
;; every Country/Area plus the World row, for 2022 and 2023 (estimates; 2024+ in
;; WPP 2024 are projections and are not used). Values are copied; units are
;; converted from thousands to millions; nothing is filled in.
(require '[clojure.data.csv :as csv] '[clojure.java.io :as io] '[clojure.pprint :as pp] '[clojure.string :as str])
(import 'java.util.zip.GZIPInputStream 'java.security.MessageDigest)
(defn sha256 [f] (let [d (MessageDigest/getInstance "SHA-256")]
                   (apply str (map #(format "%02x" %) (.digest d (java.nio.file.Files/readAllBytes (.toPath (io/file f))))))))
(defn rows [f] (let [r (io/reader (GZIPInputStream. (io/input-stream f)) :encoding "UTF-8")
                     [h & body] (csv/read-csv r)
                     h (mapv #(str/replace % "﻿" "") h)]
                 (map #(zipmap h %) body)))
(defn num [s] (when-not (str/blank? s) (Double/parseDouble s)))
(def years #{"2022" "2023"})
(defn keep? [r] (and (years (get r "Time")) (or (= "Country/Area" (get r "LocTypeName")) (= "900" (get r "LocID")))))
(let [[dir out] *command-line-args*
      ind-f (io/file dir "WPP2024_Demographic_Indicators_Medium.csv.gz")
      age-f (io/file dir "WPP2024_PopulationByAge5GroupSex_Medium.csv.gz")
      ind (reduce (fn [m r] (if (keep? r)
                              (assoc-in m [(get r "LocID") (get r "Time")]
                                        {:iso3 (get r "ISO3_code") :name (get r "Location")
                                         :p (some-> (num (get r "TPopulation1July")) (/ 1000.0))
                                         :cbr (num (get r "CBR")) :cdr (num (get r "CDR"))
                                         :netm (some-> (num (get r "NetMigrations")) (/ 1000.0))})
                              m))
                  {} (rows ind-f))
      e65 (reduce (fn [m r] (if (and (keep? r) (>= (Long/parseLong (get r "AgeGrpStart")) 65))
                              (update-in m [(get r "LocID") (get r "Time")] (fnil + 0.0) (/ (num (get r "PopTotal")) 1000.0))
                              m))
                  {} (rows age-f))
      entry (fn [loc] (let [y22 (get-in ind [loc "2022"]) y23 (get-in ind [loc "2023"])]
                        {:iso3 (:iso3 y23) :name (:name y23)
                         :p2022 (:p y22) :p2023 (:p y23)
                         :e2022 (get-in e65 [loc "2022"]) :e2023 (get-in e65 [loc "2023"])
                         :cbr2023 (:cbr y23) :cdr2023 (:cdr y23) :netm2023 (:netm y23)}))
      world (entry "900")
      countries (into (sorted-map) (for [loc (keys ind) :when (not= "900" loc) :let [e (entry loc)]] [(:iso3 e) e]))
      data {:dataset/id "awai-3384-wpp2024-tier-b-inputs"
            :dataset/version "2026-11-18.1"
            :issue "AWAI-3384"
            :source {:cite "United Nations, DESA, Population Division. World Population Prospects 2024, Medium variant (CSV: Demographic Indicators; Population by 5-year age group and sex)."
                     :base-year 2023 :note "WPP 2024 values up to 2023 are estimates; 2024 onward are projections and are not used."
                     :units {:p "million persons, 1 July" :e "million persons aged 65+, 1 July" :cbr "births per 1,000 population" :cdr "deaths per 1,000 population" :netm "million persons per year"}
                     :retrieved (str/trim (slurp (io/file dir "retrieved-at-wpp.txt")))
                     :captures [{:file (.getName ind-f) :url (str/trim (slurp (io/file dir "wpp-indicators.url"))) :bytes (.length ind-f) :sha256 (sha256 ind-f)}
                                {:file (.getName age-f) :url (str/trim (slurp (io/file dir "wpp-age.url"))) :bytes (.length age-f) :sha256 (sha256 age-f)}]
                     :capture-retention ".agent-output/awai-3384/captures in the AWAI-3384 worktree (not committed)"}
            :world world
            :countries countries}]
  (spit out (with-out-str (pp/pprint data)))
  (println "wrote" out "countries" (count countries) "world p2023" (:p2023 world)))
