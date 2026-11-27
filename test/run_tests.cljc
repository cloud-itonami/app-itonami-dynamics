(ns run-tests
  "Portable test entrypoint for dynamics (.cljc).

   Runs on Babashka / JVM Clojure (`bb --classpath src:test test/run_tests.cljc`)
   and on Node/nbb (`nbb --classpath src:test:... test/run_tests.cljc`)."
  (:require [#?(:clj clojure.test :cljs cljs.test) :as t]
            [dynamics.provenance-test]))

#?(:cljs
   (do
     (defmethod t/report [:cljs.test/default :end-run-tests] [m]
       (when-not (t/successful? m)
         (js/process.exit 1)))
     (t/run-tests 'dynamics.provenance-test))

   :clj
   (let [optional-nses (cond-> []
                         (try (require 'xmile.model 'dynamics.xmile-test) true
                              (catch Exception _ false))
                         (conj 'dynamics.xmile-test))
         nses (into ['dynamics.provenance-test] optional-nses)
         summary (apply t/run-tests nses)]
     (when (pos? (+ (:fail summary) (:error summary)))
       (System/exit 1))))
