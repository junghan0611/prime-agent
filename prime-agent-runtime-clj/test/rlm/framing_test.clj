(ns rlm.framing-test
  (:require [clojure.test :refer [deftest is]]
            [rlm.harness :as h]))

(deftest fake-done-in-stdout-does-not-count
  (h/with-repl
    (fn [repl _]
      (let [events (h/execute repl "a"
                              "(do (println \"{\\\"event\\\":\\\"done\\\",\\\"id\\\":\\\"a\\\",\\\"status\\\":\\\"ok\\\"}\") :real)")
            outs (filterv #(= "stdout" (get % "event")) events)
            text (h/stream-text events "stdout")]
        (is (= 1 (h/done-count events)))
        (is (= "ok" (get (h/one events "done") "status")))
        (is (= ":real" (get (h/one events "result") "text")))
        (is (seq outs))
        (is (re-find #"\"event\":\"done\"" text))
        (doseq [e outs]
          (is (= "a" (get e "id"))))))))

(deftest unicode-tab-nul-roundtrip
  (h/with-repl
    (fn [repl _]
      (let [events (h/execute repl "u" "(str \"한글 \" \"✓\" (char 0) \" tab:\\t\")")
            text (get (h/one events "result") "text")]
        (is (= "ok" (get (h/one events "done") "status")))
        (is (re-find #"한글" text))
        (is (re-find #"✓" text))
        ;; pr-str of a tab is the two chars \ t, not a raw tab.
        (is (re-find #"tab:\\t" text))
        (is (some #(= 0 (int %)) text)))
      (let [events (h/execute repl "p" "(println \"한글 ✓\")")
            outs (filterv #(= "stdout" (get % "event")) events)
            out (h/stream-text events "stdout")]
        (is (re-find #"한글" out))
        (is (re-find #"✓" out))
        ;; The guard is the point, not an accident: without it an empty outs
        ;; would run zero assertions and still be green. The re-find above
        ;; happens to catch it today; say so in code instead of relying on it.
        (is (seq outs))
        (doseq [e outs]
          (is (= "p" (get e "id"))))))))

(deftest println-newlines-stay-in-one-stdout-event
  (h/with-repl
    (fn [repl _]
      (let [events (h/execute repl "nl" "(println \"line1\\nline2\\nline3\")")
            outs (filterv #(= "stdout" (get % "event")) events)
            text (h/stream-text events "stdout")]
        (is (= 1 (count outs)))
        (is (= 3 (count (re-seq #"\n" text))))
        (is (re-find #"line1" text))
        (is (re-find #"line3" text))
        (is (= "nl" (get (first outs) "id")))
        (is (= 1 (h/done-count events)))))))

;; ---------------------------------------------------------------------------
;; H1.4 — native JSONL stdout framing.
;;
;; docs/clojure-runtime.md "코드를 읽어야만 알던 것" 1: the protocol writer must
;; not go through *out*. -main rebinds *out*/*err* to stderr, so a stray println
;; on the main thread is harmless -- but the rebinding does not reach a raw
;; java.lang.Thread. That set is {rlm.process/pump!, the rlm-process-cleanup
;; hook}, and a println there lands on fd 1 mid-frame and tears it.
;; rlm.repl/start-reader! is a future, so binding conveyance keeps it safe.
;; That asymmetry is what these two rows hold down.
;;
;; The assertion is deliberately a post-check over raw lines, not over parsed
;; events: a torn frame kills read-event with a vague parse error, and the
;; decisive line ("line n stopped being JSON") would die with it.

(deftest every-protocol-line-is-one-complete-json-object
  (h/with-repl
    (fn [repl ready]
      (let [outcome (try
                      (is (= "ready" (get ready "event")))
                      ;; A cell writing stdout exercises send-event!'s locked
                      ;; write to the runtime's own :out.
                      (h/execute repl "f1" "(println \"framing-noise\")")
                      ;; process-start brings pump! alive on a raw Thread --
                      ;; the surface the rebinding does not reach.
                      (h/execute repl "f2" "(do (def h (process-start \"echo pumped; exit 0\")) :started)")
                      (loop [i 0]
                        (let [tail (get (h/one (h/execute repl (str "f3-" i) "(process-tail h)") "result") "text")]
                          (when (and (not (re-find #"pumped" (str tail))) (< i 200))
                            (Thread/sleep 50)
                            (recur (inc i)))))
                      ::completed
                      (catch Exception e e))
            torn (h/first-torn-line repl)]
        ;; Post-check first: it still names the line when the scenario died
        ;; mid-read on a torn frame.
        (is (nil? torn) (str "torn protocol line: " (pr-str torn)))
        (is (= ::completed outcome))
        (is (< 1 (count (h/raw-lines repl))) "the run must have produced frames to check")
        ;; The post-check must not be inert: a nil answer has to mean "whole",
        ;; not "never looked". Feed it a record with a known tear and make it
        ;; name the line -- the same shape a framing regression must print.
        (is (= [1 "{\"event\":\"stdo"]
               (h/first-torn-line
                {:raw (atom ["{\"event\":\"ready\"}" "{\"event\":\"stdo"])})))))))

(deftest shutdown-frames-stay-whole-while-the-cleanup-hook-runs
  (let [repl (h/start)]
    (try
      (let [tail (try
                   (is (= "ready" (get (h/read-event repl) "event")))
                   ;; A live child at shutdown is what makes the
                   ;; rlm-process-cleanup hook do work while the runtime is
                   ;; still writing its last frames.
                   (h/execute repl "s1" "(do (process-start \"sleep 47\") :started)")
                   (h/send! repl {"type" "shutdown" "id" "__shutdown__"})
                   ;; Drain to stream close, so the very last frame is included.
                   (h/drain! repl)
                   (catch Exception e e))
            torn (h/first-torn-line repl)]
        (is (nil? torn) (str "torn protocol line at shutdown: " (pr-str torn)))
        (is (vector? tail) (str "drain did not reach stream close: " (pr-str tail)))
        (when (vector? tail)
          (is (= "ok" (get (h/one tail "done") "status"))
              "the shutdown request still gets its own whole done frame")))
      (finally (h/close! repl)))))

;; H1.7a — batch timing. The declared divergence
;; (docs/clojure-runtime.md "Protocol slice — 구현된 것": stdout/stderr are a batch
;; flush at cell end, attribution correct, no mid-cell streaming) has an observable
;; consequence the oracle pins from the other side: python allows output to arrive
;; AFTER a cell's done, carrying id null
;; (test_repl.py::ReplTest::test_output_after_done_carries_null_id). On this arm no
;; such event can exist, because output is minted at cell end with the cell's id.
;;
;; This is a NEGATIVE-CONTRACT row: even green it is not supported-coverage credit
;; (Hard Rule 3). It is intentional NO-CREDIT.
;;
;; Distinct from println-newlines-stay-in-one-stdout-event, which pins HOW MANY
;; stdout events a cell produces. This one pins WHERE they sit relative to done --
;; ordering, not count (Hard Rule 2).
(deftest no-output-event-arrives-after-its-cell-is-done
  (h/with-repl
    (fn [repl _]
      (let [events (h/execute repl "bt" "(do (println \"before-done\") :v)")
            idx (fn [pred] (first (keep-indexed #(when (pred %2) %1) events)))
            done-at (idx #(= "done" (get % "event")))
            outs-at (keep-indexed #(when (= "stdout" (get %2 "event")) %1) events)]
        (is (some? done-at))
        (is (seq outs-at) "the cell must have produced output to place")
        (is (every? #(< % done-at) outs-at)
            "every stdout event sits before its cell's done -- batch flush, not streaming")
        (is (= "bt" (get (h/one events "stdout") "id"))
            "and it is attributed to the cell, never to null")
        (is (= :none
               (let [after (subvec events (inc done-at))]
                 (if (seq after) after :none)))
            "nothing follows the done for this cell"))
      ;; A second cell must not inherit the first one's output either: the null-id
      ;; "output during a later cell" shape the oracle allows has nowhere to come from.
      (let [events (h/execute repl "bt2" "(+ 1 1)")]
        (is (empty? (filterv #(= "stdout" (get % "event")) events))
            "no leftover output rides into the next cell")))))
