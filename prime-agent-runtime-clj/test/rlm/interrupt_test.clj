(ns rlm.interrupt-test
  "H9 — cell cancellation. The cell runs on its own thread and an interrupt is
  delivered with Thread.interrupt, so what the oracle does with SIGINT this
  runtime does with an interrupted thread.

  The frame shape is the contract these rows hold: a string `id` on an error
  means THAT CELL IS OVER AND THIS IS ITS OUTCOME, a nil `id` means the cell
  reported its own outcome and this frame is a kernel diagnostic about an
  interrupt that could not be honoured. repl-manager.ts::handleEvent is what
  makes the distinction load-bearing -- it folds a non-string id to undefined
  and routes that error to appendKernelDiagnostic, while a string id is
  attributed to the execution and marks it errored."
  (:require [clojure.java.io :as jio]
            [clojure.test :refer [deftest is testing]]
            [rlm.harness :as h]))

(defn- errors
  [events]
  (filterv #(= "error" (get % "event")) events))

(defn- block-code
  "A cell that parks in host-request. Deref of the reply promise is a
  CountDownLatch await, which is where Thread.interrupt actually lands -- the
  SCI sandbox exposes no other blocking call to a cell."
  [kind]
  (str "(host-request {:type \"" kind "\"})"))

;; ---------------------------------------------------------------------------
;; Oracle row: test_repl.py::ReplTest::test_host_request_cancelled_cell_drops_pending_future
;; ---------------------------------------------------------------------------
(deftest an-interrupt-cancels-a-cell-blocked-in-host-request
  (h/with-repl
    (fn [repl _]
      (h/send! repl {"type" "execute" "id" "hr-cancel" "code" (block-code "never-answered")})
      (let [req (h/wait-event repl "host_request")]
        ;; Untargeted, the shape the oracle's own row sends: an id-less
        ;; interrupt applies to the running request.
        (h/send! repl {"type" "interrupt"})
        (let [events (h/until-done repl "hr-cancel")
              e (h/one events "error")]
          (is (= "KeyboardInterrupt" (get e "ename")))
          (is (= "hr-cancel" (get e "id"))
              "a real cancellation is attributed to the cell it ended")
          (is (= "" (get e "evalue")) "oracle _interrupt_event carries an empty evalue")
          (is (= ["KeyboardInterrupt\n"] (get e "traceback")))
          (is (= "error" (get (h/one events "done") "status")))
          (is (= 1 (h/done-count events)) "a cancelled request ends exactly once"))
        ;; The pending host request died with the cell: a reply arriving after
        ;; the cancel is dropped, not matched, and does not kill the runtime.
        (h/send! repl {"type" "host_reply" "id" (get req "id") "data" {"status" "ok"}})
        (let [events (h/execute repl "after-cancel" "(+ 8 8)")]
          (is (= "16" (get (h/one events "result") "text")))
          (is (empty? (errors events))
              "the dropped reply left nothing latched on the next cell"))))))

;; ---------------------------------------------------------------------------
;; Oracle row: test_repl.py::ReplTest::test_interrupt_written_back_to_back_with_execute
;; The oracle's own row is the same construction: the interrupt reaches the
;; reader before the request is active, so it parks and is consumed at
;; activation -- the cell is cancelled BEFORE ITS FIRST STEP, which is what the
;; absent stdout proves here.
;; ---------------------------------------------------------------------------
(deftest an-interrupt-for-a-queued-request-is-parked-and-cancels-it-before-its-first-step
  (h/with-repl
    (fn [repl _]
      (h/send! repl {"type" "execute" "id" "front" "code" (block-code "hold")})
      (let [req (h/wait-event repl "host_request")]
        ;; "queued" is inflight (the reader added it) but not active: "front"
        ;; still holds the serve loop. A targeted interrupt therefore parks.
        (h/send! repl {"type" "execute" "id" "queued" "code" "(println \"ran\") 1"})
        (h/send! repl {"type" "interrupt" "id" "queued"})
        (h/send! repl {"type" "host_reply" "id" (get req "id") "data" {"status" "ok"}})
        (let [front (h/until-done repl "front")]
          (is (= "ok" (get (h/one front "done") "status"))
              "parking an interrupt for another request must not disturb the running one")
          (is (empty? (errors front))))
        (let [events (h/until-done repl "queued")]
          (is (= "KeyboardInterrupt" (get (h/one events "error") "ename")))
          (is (= "queued" (get (h/one events "error") "id")))
          (is (= "error" (get (h/one events "done") "status")))
          (is (= "" (h/stream-text events "stdout"))
              "consumed at activation: the cell body never ran")
          (is (nil? (h/one events "result"))))))))

;; ---------------------------------------------------------------------------
;; Oracle row: test_repl.py::ReplTest::test_stale_target_from_finished_interrupt_ignores_later_sigint_on_reused_id
;; The oracle's stray signal is external; the clj twin is a late interrupt
;; FRAME. Either way the point is the same: the target dies with its request,
;; so a later request reusing the id must not inherit it.
;; ---------------------------------------------------------------------------
(deftest a-target-dies-with-its-request-and-does-not-cancel-a-reused-id
  (h/with-repl
    (fn [repl _]
      (h/send! repl {"type" "execute" "id" "reuse" "code" (block-code "first")})
      (h/wait-event repl "host_request")
      (h/send! repl {"type" "interrupt" "id" "reuse"})
      (let [events (h/until-done repl "reuse")]
        (is (= "KeyboardInterrupt" (get (h/one events "error") "ename")))
        (is (= "error" (get (h/one events "done") "status"))))
      ;; Late frame for the id that just finished: nothing is inflight, so it is
      ;; dropped in silence -- the one silence the oracle also keeps.
      (h/send! repl {"type" "interrupt" "id" "reuse"})
      (let [events (h/execute repl "reuse" "(+ 3 4)")]
        (is (empty? (errors events))
            "a stale target must not cancel the request that reused its id")
        (is (= "7" (get (h/one events "result") "text")))
        (is (= "ok" (get (h/one events "done") "status")))))))

;; ---------------------------------------------------------------------------
;; The honest negative half. Oracle row:
;; test_repl.py::ReplTest::test_interrupt_survives_cell_rebinding_sigint --
;; there a cell installs SIG_IGN and later interrupts must still work; here a
;; cell swallows the InterruptedException and later interrupts must still work.
;; The clj half owes one thing the oracle does not: the oracle's SIGINT always
;; lands, so it never has to report a delivery that went unobserved. This one
;; does, and reporting it is the contract -- silence would read as the oracle's
;; "unknown or finished request", which is a different fact.
;; ---------------------------------------------------------------------------
(deftest an-interrupt-the-cell-swallows-is-reported-not-silently-dropped
  (h/with-repl
    (fn [repl _]
      (h/send! repl {"type" "execute" "id" "swallow"
                     "code" (str "(host-request {:type \"sync\"}) "
                                 "(try (host-request {:type \"swallowed\"}) "
                                 "     (catch Exception _ :swallowed))")})
      (let [sync-req (h/wait-event repl "host_request")]
        (h/send! repl {"type" "host_reply" "id" (get sync-req "id") "data" {"status" "ok"}})
        ;; Waiting for the SECOND host_request is what makes this deterministic:
        ;; the cell is provably inside the interruptible deref.
        (h/wait-event repl "host_request")
        (h/send! repl {"type" "interrupt" "id" "swallow"})
        (let [events (h/until-done repl "swallow")]
          (testing "the cell reported its own outcome, not a cancellation"
            (is (= "ok" (get (h/one events "done") "status")))
            (is (= ":swallowed" (get (h/one events "result") "text")))
            (is (empty? (errors events))
                "the diagnostic must not be attributed to the cell, so it cannot ride inside its frames"))
          (testing "and the runtime says out loud that the interrupt went unhonoured"
            (let [e (h/read-event repl)]
              (is (= "error" (get e "event")))
              (is (= "InterruptNotDelivered" (get e "ename")))
              (is (nil? (get e "id"))
                  "a nil id keeps this on the kernel-diagnostic channel, off the cell")
              (is (h/error-shape? e))
              (is (re-find #"\"swallow\"" (str (get e "evalue")))
                  "the report names the request it could not cancel")
              (is (re-find #"ran to completion" (str (get e "evalue")))
                  "and says WHICH unhonoured case this was")
              (is (re-find #"\d+ ms" (str (get e "evalue")))
                  "'not observed' is only precise as 'not observed within N ms'")))))
      ;; Nothing latched: the next cell is still cancellable.
      (h/send! repl {"type" "execute" "id" "after-swallow" "code" (block-code "again")})
      (h/wait-event repl "host_request")
      (h/send! repl {"type" "interrupt" "id" "after-swallow"})
      (let [events (h/until-done repl "after-swallow")]
        (is (= "KeyboardInterrupt" (get (h/one events "error") "ename"))
            "a cell that ignored its interrupt must not disable the next one's")
        (is (= "after-swallow" (get (h/one events "error") "id")))
        (is (= "error" (get (h/one events "done") "status")))))))

;; ---------------------------------------------------------------------------
;; The refusal contract, on the target that actually cannot observe an
;; interrupt: a pure SCI compute loop (docs/clojure-runtime.md's measured case).
;; This row is why the report cannot wait for the cell's own done frame -- a
;; tight loop never sends one, so reporting after done would be silence exactly
;; where the caller has no other signal. The runtime says it on a clock instead.
;;
;; H1.8's negative contract anchors here. It is NOT a coverage PASS: declaring
;; that something is refused is a scope statement, not a supported contract.
;;
;; DECLARED DIVERGENCE (H11): the cell keeps the serve loop, so this runtime
;; does not recover from a tight loop -- only the host's kernel restart does.
;; Teardown is measured, not assumed: EOF cleanup cannot finish while the cell
;; spins, so h/close! falls through to destroyForcibly after its 10s wait. That
;; is safe here only because this cell owns no child process to orphan.
;; ---------------------------------------------------------------------------
(deftest a-cell-that-cannot-observe-its-interrupt-is-reported-while-it-still-runs
  (let [dir (str "target/h9-" (System/nanoTime))
        marker (str dir "/started")]
    (.mkdirs (jio/file dir))
    (try
      (h/with-repl
        (fn [repl _]
          (h/send! repl {"type" "execute" "id" "spin"
                         "code" (str "(host-request {:type \"sync\"}) "
                                     "(write-text \"" marker "\" \"1\") "
                                     "(loop [] (recur))")})
          (let [req (h/wait-event repl "host_request")]
            (h/send! repl {"type" "host_reply" "id" (get req "id") "data" {"status" "ok"}}))
          ;; The marker is the proof that the cell left the one interruptible
          ;; wait it had and is now inside the loop. Without it this test would
          ;; race the deref and measure a cancellation instead of a refusal.
          (let [deadline (+ (System/currentTimeMillis) 30000)]
            (while (and (not (.isFile (jio/file marker)))
                        (< (System/currentTimeMillis) deadline))
              (Thread/sleep 20))
            (is (.isFile (jio/file marker)) "the cell reached its uninterruptible loop"))
          (h/send! repl {"type" "interrupt" "id" "spin"})
          (let [e (h/read-event repl 30)]
            (is (= "error" (get e "event")))
            (is (= "InterruptNotDelivered" (get e "ename")))
            (is (nil? (get e "id"))
                "the cell is still running: attributing this to it would report a live cell as failed")
            (is (h/error-shape? e))
            (is (re-find #"\"spin\"" (str (get e "evalue"))))
            (is (re-find #"still running" (str (get e "evalue")))
                "the report says the cell did not stop, which is the whole fact")
            (is (re-find #"\d+ ms" (str (get e "evalue")))
                "and bounds it: not observed WITHIN a stated window"))))
      (finally
        (doseq [f (reverse (file-seq (jio/file dir)))]
          (.delete ^java.io.File f))))))
