(ns rlm.host-bridge-test
  (:require [clojure.test :refer [deftest is]]
            [rlm.harness :as h])
  (:import [java.util.concurrent TimeUnit]))

(deftest host-request-reply-bypasses-fifo
  (h/with-repl
    (fn [repl _]
      (h/send! repl {"type" "execute" "id" "hr"
                     "code" "(host-request {:type \"demo\" :value 7})"})
      (let [req (h/wait-event repl "host_request")]
        (is (string? (get req "id")))
        (is (= {"type" "demo" "value" 7} (get req "data")))
        (h/send! repl {"type" "host_reply"
                       "id" (get req "id")
                       "data" {"status" "ok" "answer" 42}})
        (let [events (h/until-done repl "hr")
              result (h/one events "result")]
          (is (re-find #"42" (str (get result "text"))))
          (is (= 1 (h/done-count events)))
          (is (= "ok" (get (h/one events "done") "status"))))))))

(deftest host-reply-unknown-id-dropped
  (h/with-repl
    (fn [repl _]
      (h/send! repl {"type" "host_reply" "id" "no-such-request"
                     "data" {"status" "ok"}})
      (let [events (h/execute repl "ok" "(+ 8 8)")]
        (is (= "16" (get (h/one events "result") "text")))))))

(deftest rlm-returns-handle-not-answer
  (h/with-repl
    (fn [repl _]
      (h/send! repl {"type" "execute" "id" "rlm1"
                     "code" "(rlm \"child task\")"})
      (let [req (h/wait-event repl "host_request")]
        (is (= "rlm.run" (get-in req ["data" "type"])))
        (is (= "child task" (get-in req ["data" "prompt"])))
        (h/send! repl {"type" "host_reply"
                       "id" (get req "id")
                       "data" {"status" "ok"
                               "rlm_child_id" "c1"
                               "name" "child"
                               "session_dir" "/tmp/x"
                               "model" "test"
                               "answer" "THE ANSWER"}})
        (let [events (h/until-done repl "rlm1")
              text (get (h/one events "result") "text")]
          (is (re-find #"c1" text))
          (is (re-find #":rlm-child-id" text))
          (is (not (re-find #"THE ANSWER" text)))
          (is (= 1 (h/done-count events)))
          (is (= "ok" (get (h/one events "done") "status"))))))))

(deftest shutdown-clean-exit
  (let [repl (h/start)]
    (try
      (h/read-event repl)
      (h/send! repl {"type" "shutdown" "id" "__shutdown__"})
      (is (= "ok" (get (h/one (h/until-done repl "__shutdown__") "done") "status")))
      (is (.waitFor ^Process (:proc repl) 10 TimeUnit/SECONDS))
      (is (zero? (.exitValue ^Process (:proc repl))))
      (finally
        (h/close! repl)))))

(deftest shutdown-fails-pending-host
  (let [repl (h/start)]
    (try
      (h/read-event repl)
      (h/send! repl {"type" "execute" "id" "hr-pending"
                     "code" "(host-request {:type \"never-answered\"})"})
      (h/wait-event repl "host_request")
      (h/send! repl {"type" "shutdown" "id" "__shutdown__"})
      (let [events (h/until-done repl "hr-pending")]
        (is (some? (h/one events "error")))
        (is (re-find #"host connection closed"
                     (str (get (h/one events "error") "evalue"))))
        (is (= 1 (h/done-count events)))
        (is (= "error" (get (h/one events "done") "status"))))
      (is (= "ok" (get (h/one (h/until-done repl "__shutdown__") "done") "status")))
      (is (.waitFor ^Process (:proc repl) 10 TimeUnit/SECONDS))
      (is (zero? (.exitValue ^Process (:proc repl))))
      (finally
        (h/close! repl)))))

(deftest stdin-eof-with-pending-host-exits
  (let [repl (h/start)]
    (try
      (h/read-event repl)
      (h/send! repl {"type" "execute" "id" "hr-pending"
                     "code" "(host-request {:type \"never-answered\"})"})
      (h/wait-event repl "host_request")
      (.close ^java.io.Writer (:stdin repl))
      (is (.waitFor ^Process (:proc repl) 10 TimeUnit/SECONDS))
      (is (zero? (.exitValue ^Process (:proc repl))))
      (finally
        (h/close! repl)))))
