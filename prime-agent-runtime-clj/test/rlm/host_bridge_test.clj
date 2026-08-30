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

(deftest host-request-reply-is-keyword-nested-map
  ;; H2 leftover: string keys made (:status roster) silent nil. Nested fixture.
  (h/with-repl
    (fn [repl _]
      (h/send! repl {"type" "execute" "id" "kw"
                     "code" (str "(let [r (host-request {:type \"roster\"})]
                                     [(:status r)
                                      (get-in r [:current :name])
                                      (get-in r [:subagents 0 :status])])")})
      (let [req (h/wait-event repl "host_request")]
        (h/send! repl {"type" "host_reply"
                       "id" (get req "id")
                       "data" {"status" "ok"
                               "current" {"name" "root"}
                               "subagents" [{"status" "completed"}]}})
        (let [events (h/until-done repl "kw")
              text (get (h/one events "result") "text")]
          (is (re-find #"ok" text))
          (is (re-find #"root" text))
          (is (re-find #"completed" text))
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

(deftest rlm-rejects-non-string-prompt
  (h/with-repl
    (fn [repl _]
      (let [events (h/execute repl "bad" "(rlm 42)")
            err (h/one events "error")]
        (is (h/error-shape? err))
        (is (re-find #"prompt must be str" (str (get err "evalue"))))
        (is (= "error" (get (h/one events "done") "status")))
        (is (= 1 (h/done-count events))))
      (let [events (h/execute repl "ok" "(+ 1 1)")]
        (is (= "2" (get (h/one events "result") "text")))))))

(deftest rlm-host-error-status
  (h/with-repl
    (fn [repl _]
      (h/send! repl {"type" "execute" "id" "re" "code" "(rlm \"x\")"})
      (let [req (h/wait-event repl "host_request")]
        (h/send! repl {"type" "host_reply" "id" (get req "id")
                       "data" {"status" "error" "error" "nope"}})
        (let [events (h/until-done repl "re")
              err (h/one events "error")]
          (is (h/error-shape? err))
          (is (re-find #"nope" (str (get err "evalue"))))
          (is (= "error" (get (h/one events "done") "status")))
          (is (= 1 (h/done-count events))))))))

(deftest rlm-unexpected-status
  (h/with-repl
    (fn [repl _]
      (h/send! repl {"type" "execute" "id" "rw" "code" "(rlm \"x\")"})
      (let [req (h/wait-event repl "host_request")]
        (h/send! repl {"type" "host_reply" "id" (get req "id")
                       "data" {"status" "weird"}})
        (let [events (h/until-done repl "rw")
              err (h/one events "error")]
          (is (h/error-shape? err))
          (is (re-find #"unexpected status" (str (get err "evalue"))))
          (is (= "error" (get (h/one events "done") "status")))
          (is (= 1 (h/done-count events))))))))
