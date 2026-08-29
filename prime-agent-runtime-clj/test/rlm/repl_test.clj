(ns rlm.repl-test
  (:require [clojure.test :refer [deftest is testing]]
            [rlm.harness :as h]))

(deftest ready-is-first-frame
  (h/with-repl
    (fn [_repl ready]
      (is (= "ready" (get ready "event")))
      (is (= 2 (get ready "protocol")))
      (is (= "clojure-native" (get ready "python")))
      (is (= {"language" "clojure" "engine" "sci" "native" true}
             (get ready "runtime"))))))

(deftest expression-result
  (h/with-repl
    (fn [repl _]
      (let [events (h/execute repl "a" "(+ 1 1)")]
        (is (= "2" (get (h/one events "result") "text")))
        (is (= 1 (h/done-count events)))
        (is (= "ok" (get (h/one events "done") "status")))
        (is (= "done" (get (last events) "event")))))))

(deftest persistent-binding-across-cells
  (h/with-repl
    (fn [repl _]
      (let [first-events (h/execute repl "c1" "(def x 41)")
            second (h/execute repl "c2" "(inc x)")]
        (is (= 1 (h/done-count first-events)))
        (is (= "ok" (get (h/one first-events "done") "status")))
        (is (= "42" (get (h/one second "result") "text")))
        (is (= 1 (h/done-count second)))
        (is (= "ok" (get (h/one second "done") "status")))))))

(deftest nil-last-value-has-no-result
  (h/with-repl
    (fn [repl _]
      (let [events (h/execute repl "n" "nil")]
        (is (nil? (h/one events "result")))
        (is (= 1 (h/done-count events)))
        (is (= "ok" (get (h/one events "done") "status"))))
      (let [events (h/execute repl "n2" "(do (def y 1) nil)")]
        (is (nil? (h/one events "result")))
        (is (= 1 (h/done-count events)))
        (is (= "ok" (get (h/one events "done") "status")))))))

(deftest stdout-stderr-attribution
  (h/with-repl
    (fn [repl _]
      (let [events (h/execute repl "io" "(do (println \"py-out\") (binding [*out* *err*] (println \"py-err\")) :done)")]
        (is (re-find #"py-out" (h/stream-text events "stdout")))
        (is (re-find #"py-err" (h/stream-text events "stderr")))
        (doseq [e events
                :when (#{"stdout" "stderr"} (get e "event"))]
          (is (= "io" (get e "id"))))
        (is (= 1 (h/done-count events)))
        (is (= "done" (get (last events) "event")))))))

(deftest underscore-binding
  (h/with-repl
    (fn [repl _]
      (is (= "ok" (get (h/one (h/execute repl "u1" "(+ 1 1)") "done") "status")))
      (let [events (h/execute repl "u2" "(+ _ 40)")]
        (is (= "42" (get (h/one events "result") "text")))))))

(deftest malformed-json-keeps-serving
  (h/with-repl
    (fn [repl _]
      (h/send-raw! repl "{not json")
      (let [e (h/read-event repl)]
        (is (= "error" (get e "event")))
        (is (= "ProtocolError" (get e "ename")))
        (is (nil? (get e "id"))))
      (let [events (h/execute repl "ok" "(+ 8 8)")]
        (is (= "16" (get (h/one events "result") "text")))))))

(deftest error-then-next-execute-works
  (h/with-repl
    (fn [repl _]
      (testing "eval error"
        (let [events (h/execute repl "bad" "(no-such-fn 1)")]
          (is (some? (h/one events "error")))
          (is (= 1 (h/done-count events)))
          (is (= "error" (get (h/one events "done") "status")))))
      (testing "reader error"
        (let [events (h/execute repl "syn" "(+ 1")]
          (is (some? (h/one events "error")))
          (is (= 1 (h/done-count events)))
          (is (= "error" (get (h/one events "done") "status")))))
      (let [events (h/execute repl "ok" "(+ 40 2)")]
        (is (= "42" (get (h/one events "result") "text")))
        (is (= 1 (h/done-count events)))
        (is (= "ok" (get (h/one events "done") "status")))))))

(deftest exactly-one-done-per-execute
  (h/with-repl
    (fn [repl _]
      (let [a (h/execute repl "a" "(+ 1 1)")]
        (is (= 1 (h/done-count a)))
        (is (= "a" (get (h/one a "done") "id"))))
      (let [b (h/execute repl "b" "(+ 2 2)")]
        (is (= 1 (h/done-count b)))
        (is (= "b" (get (h/one b "done") "id")))
        (is (nil? (some #(= "a" (get % "id")) (filter #(= "done" (get % "event")) b))))))))

(deftest duplicate-inflight-id-rejected
  (h/with-repl
    (fn [repl _]
      (h/send! repl {"type" "execute" "id" "busy"
                     "code" "(host-request {:type \"block\"})"})
      (let [req (h/wait-event repl "host_request")]
        (h/send! repl {"type" "execute" "id" "busy" "code" "(+ 1 1)"})
        (let [e (h/read-event repl)]
          (is (= "error" (get e "event")))
          (is (= "ProtocolError" (get e "ename")))
          (is (nil? (get e "id")))
          (is (re-find #"duplicate" (str (get e "evalue")))))
        (h/send! repl {"type" "host_reply" "id" (get req "id")
                       "data" {"status" "ok"}})
        (let [events (h/until-done repl "busy")]
          (is (= 1 (h/done-count events)))
          (is (= "ok" (get (h/one events "done") "status"))))))))
