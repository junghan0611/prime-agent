(ns rlm.test-runner
  (:require [clojure.test :as t]
            [rlm.host-bridge-test]
            [rlm.repl-test]
            [rlm.sut :as sut]))

(defn -main
  [& _]
  (try
    (println (sut/banner))
    (flush)
    (let [{:keys [fail error]} (t/run-tests 'rlm.repl-test 'rlm.host-bridge-test)]
      (System/exit (if (zero? (+ fail error)) 0 1)))
    (catch clojure.lang.ExceptionInfo e
      (binding [*out* *err*]
        (println (ex-message e)))
      (System/exit 2))))
