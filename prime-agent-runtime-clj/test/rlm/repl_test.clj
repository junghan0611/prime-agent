(ns rlm.repl-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
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
        (let [events (h/execute repl "bad" "(no-such-fn 1)")
              err (h/one events "error")]
          (is (some? err))
          (is (h/error-shape? err))
          (is (= 1 (h/done-count events)))
          (is (= "error" (get (h/one events "done") "status")))))
      (testing "reader error"
        (let [events (h/execute repl "syn" "(+ 1")
              err (h/one events "error")]
          (is (some? err))
          (is (h/error-shape? err))
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

(deftest protocol-error-non-object-json
  (h/with-repl
    (fn [repl _]
      (h/send-raw! repl "[1,2]")
      (let [e (h/read-event repl)]
        (is (= "error" (get e "event")))
        (is (= "ProtocolError" (get e "ename")))
        (is (nil? (get e "id")))
        (is (h/error-shape? e)))
      (let [events (h/execute repl "ok" "(+ 1 1)")]
        (is (= "2" (get (h/one events "result") "text")))))))

(deftest protocol-error-missing-execute-fields
  (h/with-repl
    (fn [repl _]
      (h/send! repl {"type" "execute"})
      (let [e (h/read-event repl)]
        (is (= "ProtocolError" (get e "ename")))
        (is (nil? (get e "id")))
        (is (h/error-shape? e)))
      (let [events (h/execute repl "ok" "(+ 2 2)")]
        (is (= "4" (get (h/one events "result") "text")))))))

(deftest protocol-error-unknown-type
  (h/with-repl
    (fn [repl _]
      (h/send! repl {"type" "snapshot" "id" "s"})
      (let [e (h/read-event repl)]
        (is (= "ProtocolError" (get e "ename")))
        (is (nil? (get e "id")))
        (is (h/error-shape? e)))
      (let [events (h/execute repl "ok" "(+ 3 3)")]
        (is (= "6" (get (h/one events "result") "text")))))))

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

;; H1.6 — a non-string interrupt id is a protocol error and the runtime keeps
;; serving. The oracle asserts the identical message
;; (test_repl.py::ReplTest::test_interrupt_with_non_string_id_is_protocol_error,
;; assertIn "interrupt request id must be a string"), and rlm.repl/handle-line
;; emits that string literally -- validation ported to the character, test not.
;;
;; SCOPE: this row is only about a MALFORMED id. What the runtime does with a
;; WELL-FORMED interrupt is a different contract -- D-INTERRUPT and row H1.8 own
;; it. Its drop half is the deftest below; cancellation and the refusal that
;; survives it live in rlm.interrupt-test (H9). Two contracts live on the same
;; symbol; they are not the same row.
(deftest interrupt-with-non-string-id-is-a-protocol-error
  (h/with-repl
    (fn [repl _]
      ;; Sent before the execute rather than mid-cell: handle-line answers the
      ;; interrupt on the reader thread the moment it arrives, so the ordering
      ;; is deterministic here and racy if we straddle a cell.
      (h/send! repl {"type" "interrupt" "id" 123})
      (let [events (h/execute repl "i1" "(+ 1 1)")
            errs (filterv #(= "error" (get % "event")) events)]
        (is (= 1 (count errs)))
        (is (= "ProtocolError" (get (first errs) "ename")))
        (is (= "interrupt request id must be a string" (get (first errs) "evalue")))
        (is (nil? (get (first errs) "id")) "a protocol error is not attributed to a cell")
        ;; The malformed control frame must not disturb the request it arrived beside.
        (is (= "2" (get (h/one events "result") "text")))
        (is (= "ok" (get (h/one events "done") "status"))))
      (let [events (h/execute repl "i2" "(+ 2 2)")]
        (is (= "4" (get (h/one events "result") "text")))
        (is (empty? (filterv #(= "error" (get % "event")) events))
            "the error was consumed once, not latched")))))

;; The other half of the same contract: an interrupt with nothing to cancel is
;; NOT an unsupported-cancellation report. rlm/repl.py::_request_interrupt
;; returns without a trace for an unknown or finished request, so answering
;; those out loud would invent a divergence rather than declare one.
(deftest an-interrupt-with-nothing-to-cancel-stays-silent-like-the-oracle
  (h/with-repl
    (fn [repl _]
      (h/send! repl {"type" "interrupt" "id" "never-ran"})
      (h/send! repl {"type" "interrupt"})
      (let [events (h/execute repl "quiet" "(+ 5 5)")]
        (is (empty? (filterv #(= "error" (get % "event")) events))
            "an interrupt naming no live work is dropped, as the oracle drops it")
        (is (= "10" (get (h/one events "result") "text")))
        (is (= "ok" (get (h/one events "done") "status")))))))

;; A hostile or malformed control line must cost the sender its request, never
;; the reader thread. rlm.repl/start-reader! wraps handle-line in a
;; catch-Throwable that answers with a protocol error, so even a parser blow-up
;; stays a protocol error rather than ending the loop.
(deftest a-deeply-nested-json-line-does-not-kill-the-reader
  ;; Oracle twin: test_repl.py::ReplTest::test_hostile_deeply_nested_json_line_does_not_kill_reader.
  (h/with-repl
    (fn [repl _]
      (h/send-raw! repl (str (apply str (repeat 20000 "[")) (apply str (repeat 20000 "]"))))
      (let [e (h/read-event repl)]
        (is (= "error" (get e "event")))
        (is (= "ProtocolError" (get e "ename")))
        (is (nil? (get e "id")))
        (is (h/error-shape? e)))
      (let [events (h/execute repl "deep-ok" "(+ 5 5)")]
        (is (= "10" (get (h/one events "result") "text")))
        (is (= "ok" (get (h/one events "done") "status")))))))

(deftest a-non-scalar-request-type-does-not-kill-the-reader
  ;; Oracle twin: test_repl.py::ReplTest::test_unhashable_request_type_does_not_kill_reader.
  ;; protocol-error-unknown-type only ever sends a STRING type ("snapshot"), so
  ;; nothing said what a map or a list in that slot does.
  (h/with-repl
    (fn [repl _]
      (h/send! repl {"type" {"a" 1} "id" "u1"})
      (let [e (h/read-event repl)]
        (is (= "ProtocolError" (get e "ename")))
        (is (nil? (get e "id")))
        (is (h/error-shape? e)))
      (h/send! repl {"type" [1 2] "id" "u2"})
      (let [e (h/read-event repl)]
        (is (= "ProtocolError" (get e "ename"))))
      (let [events (h/execute repl "u-ok" "(+ 6 6)")]
        (is (= "12" (get (h/one events "result") "text")))))))

(deftest a-value-whose-printing-throws-is-reported-as-a-cell-error
  ;; Oracle twin: test_repl.py::ReplTest::test_exception_with_broken_str_reported_safely.
  ;; There the exception's own __str__ raises; here the nearest constructible
  ;; shape is a value that only blows up when the runtime prints it. Either way
  ;; the contract is the same: the blow-up is reported as this cell's error and
  ;; does not take the runtime with it.
  (h/with-repl
    (fn [repl _]
      (let [events (h/execute repl "bp" "(map (fn [_] (throw (ex-info \"boom\" {}))) [1 2 3])")
            err (h/one events "error")]
        (is (some? err) "printing must fail the cell, not the process")
        (is (h/error-shape? err))
        (is (= "error" (get (h/one events "done") "status")))
        (is (= 1 (h/done-count events))))
      (let [events (h/execute repl "bp-ok" "(+ 7 7)")]
        (is (= "14" (get (h/one events "result") "text")))))))

(deftest a-cell-cannot-redirect-its-own-output-and-says-so
  ;; Oracle twin: test_repl.py::ReplTest::test_rebound_stdout_without_flush_still_completes.
  ;; Rebinding the stream is ordinary there. Here it is closed, and MEASURING it
  ;; found something worth writing down: with-out-str -- a core Clojure macro, not
  ;; an interop form on its face -- expands to (java.io.StringWriter.) and dies in
  ;; the native image with "No matching ctor found". That is
  ;; docs/clojure-runtime.md "코드를 읽어야만 알던 것" 8 (reflective interop links
  ;; and dies native) surfacing in a place a model would plausibly reach for.
  ;;
  ;; The row pins the observable, not an opinion about it: the attempt fails as
  ;; THIS cell's error, the protocol stream is untouched, and the next cell
  ;; prints normally. Whether the message should teach instead of leaking a
  ;; reflection error is not settled here.
  (h/with-repl
    (fn [repl _]
      (let [events (h/execute repl "wos" "(with-out-str (print \"captured, never flushed\"))")
            err (h/one events "error")]
        (is (some? err) "redirecting a cell's own output is closed on this arm")
        (is (h/error-shape? err))
        (is (= "error" (get (h/one events "done") "status")))
        (is (= 1 (h/done-count events)))
        (is (empty? (filterv #(= "stdout" (get % "event")) events))
            "the failed redirect leaks nothing onto the protocol stream"))
      (testing "and the runtime's own stream is intact for the next cell"
        (let [events (h/execute repl "wos2" "(println \"back on the wire\")")]
          (is (= "back on the wire\n" (h/stream-text events "stdout")))
          (is (= "wos2" (get (h/one events "stdout") "id"))))))))

(deftest a-cell-error-is-anchored-in-the-cell-not-in-the-runtime
  ;; Oracle twin: test_repl.py::ReplTest::test_traceback_clean_with_source_line.
  ;; There the traceback names <cell-...>, carries the failing source line, and
  ;; must NOT contain repl.py or an ANSI escape. Same three here, against this
  ;; runtime's own plumbing names.
  (h/with-repl
    (fn [repl _]
      (testing "an error SCI attributed carries the cell location and the source line"
        (let [events (h/execute repl "tb" "(+ 1 1)\n(undefined-fn 1)")
              text (apply str (get (h/one events "error") "traceback"))]
          (is (str/includes? text "<cell-tb>:2") "the cell and the line it failed on")
          (is (str/includes? text "(undefined-fn 1)") "the cell's own source line, not a description of it")
          (is (str/ends-with? text "Could not resolve symbol: undefined-fn\n")
              "and the failure itself is the last line")))
      (testing "the runtime's own frames never reach the cell"
        (let [events (h/execute repl "tb2" "(undefined-fn 1)")
              frames (get (h/one events "error") "traceback")
              text (apply str frames)]
          ;; The named list says which frames are plumbing; the exact shape is
          ;; what makes the claim bite. A raw stack here is 24 frames deep, and
          ;; a plumbing name the list happens not to mention would still pass
          ;; the list while failing the shape.
          (is (= ["  at <cell-tb2>:1:1\n"
                  "    (undefined-fn 1)\n"
                  "ExceptionInfo: Could not resolve symbol: undefined-fn\n"]
                 frames)
              "an attributed error is the anchor, the source line, and the failure -- nothing else")
          (doseq [plumbing ["rlm.repl" "rlm.eval" "sci.impl" "sci.lang" "sci.core"
                            "clojure.lang.AFn" "java.base" "java.lang.Thread"
                            "org.graalvm" "com.oracle.svm"]]
            (is (not (str/includes? text plumbing))
                (str "the traceback must not carry " plumbing)))
          (is (not (str/includes? text (str (char 27) "[")))
              "and it carries no ANSI escape")))
      (testing "a host verb that throws straight through keeps the frame that threw"
        ;; SCI attributes what it evaluated itself. A host IFn raising on its own
        ;; is not wrapped, so there is no cell line to name -- the anchor is bare
        ;; and the frames that survive are the ones that actually threw.
        (let [events (h/execute repl "tb3" "(harness-get \"bogus\" \"x\")")
              text (apply str (get (h/one events "error") "traceback"))]
          (is (str/includes? text "<cell-tb3>") "still anchored in the cell")
          (is (str/includes? text "rlm.harness_state") "and the host frame that threw is kept, not swallowed")
          (is (not (str/includes? text "rlm.repl")) "while the driver frames are still gone")))
      (testing "a cell's own throw is not attributed on this arm"
        ;; MEASURED DEVIATION, native vs JVM: on the JVM sci/eval-string* wraps a
        ;; cell's (throw ...) with :line/:column, and in the native image it does
        ;; not, so this shape gets the bare anchor. Recorded rather than papered
        ;; over -- the row says what the arm does, not what it wishes.
        (let [events (h/execute repl "tb4" "(throw (ex-info \"nope\" {}))")
              text (apply str (get (h/one events "error") "traceback"))]
          (is (= "  at <cell-tb4>\nExceptionInfo: nope\n" text))))
      (testing "and the runtime keeps serving"
        (is (= "2" (get (h/one (h/execute repl "after-tb" "(+ 1 1)") "result") "text")))))))
