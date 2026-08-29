(ns rlm.test-runner
  (:require [clojure.test :as t]
            [rlm.capability-test]
            [rlm.framing-test]
            [rlm.host-bridge-test]
            [rlm.repl-test]
            [rlm.sut :as sut]
            [rlm.workspace-test]))

(def ^:private nses
  '[rlm.repl-test rlm.host-bridge-test rlm.workspace-test rlm.capability-test rlm.framing-test])

(defn -main
  [& _]
  (try
    (println (sut/banner))
    (flush)
    (let [{:keys [fail error]} (apply t/run-tests nses)]
      (System/exit (if (zero? (+ fail error)) 0 1)))
    (catch clojure.lang.ExceptionInfo e
      (binding [*out* *err*]
        (println (ex-message e)))
      (System/exit 2))))
