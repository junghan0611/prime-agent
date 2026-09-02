(ns rlm.display-test
  "H12 — the display frame.

  This is not a host verb: it is a kernel -> host protocol frame,
  {event:\"display\", id:<cell>, data:{<mime>: payload}}, which
  repl-manager.ts::handleEvent already routes (including after the execution has
  settled). So the hop is (emit) in the workspace plus the frame in rlm.repl."
  (:require [clojure.test :refer [deftest is testing]]
            [rlm.harness :as h]))

(def ^:private diff-mime "application/vnd.prime-agent.diff+json")
(def ^:private attachment-mime "application/vnd.prime-agent.attachment+json")
(def ^:private agent-message-mime "application/vnd.prime-agent.agent-message+json")

;; ---------------------------------------------------------------------------
;; Oracle row: test_repl.py::ReplTest::test_emit_display
;; The oracle walks the three prime-agent MIME types, each alongside text/plain,
;; and asserts the payload survives whole and the frame carries the CELL id.
;; The MIME strings are the host's own constants (kernel/shared.ts:
;; DIFF_DISPLAY_MIME / ATTACHMENT_DISPLAY_MIME / AGENT_MESSAGE_DISPLAY_MIME).
;; ---------------------------------------------------------------------------
(deftest emit-ships-one-display-frame-tagged-with-the-running-cell
  (h/with-repl
    (fn [repl _]
      (doseq [[i mime payload code]
              [[0 diff-mime
                {"path" "/tmp/file.py" "old_str" "a" "new_str" "b" "start_line" 3}
                "{\"path\" \"/tmp/file.py\" \"old_str\" \"a\" \"new_str\" \"b\" \"start_line\" 3}"]
               [1 attachment-mime
                {"mime_type" "image/png" "data" "aGVsbG8=" "path" "/tmp/img.png"}
                "{\"mime_type\" \"image/png\" \"data\" \"aGVsbG8=\" \"path\" \"/tmp/img.png\"}"]
               [2 agent-message-mime
                {"id" "agentmsg_1" "message" "hi" "deliveryStatus" "delivered"
                 "receiverRole" "parent" "target" {"sessionId" "s1"}}
                (str "{\"id\" \"agentmsg_1\" \"message\" \"hi\" \"deliveryStatus\" \"delivered\" "
                     "\"receiverRole\" \"parent\" \"target\" {\"sessionId\" \"s1\"}}")]]]
        (let [rid (str "emit" i)
              events (h/execute repl rid
                                (str "(emit {\"" mime "\" " code " \"text/plain\" \"label\"})"))
              display (h/one events "display")]
          (is (some? display) mime)
          (is (= payload (get-in display ["data" mime]))
              "the payload crosses whole, key shape included")
          (is (= "label" (get-in display ["data" "text/plain"])))
          (is (= rid (get display "id"))
              "the frame is tagged with the cell running at call time")
          (is (= "ok" (get (h/one events "done") "status")))))
      (testing "an empty or non-string-keyed map is refused before any frame"
        (doseq [code ["(emit {})" "(emit {:not-a-mime 1})" "(emit \"nope\")"]]
          (let [events (h/execute repl (str "bad-" (hash code)) code)]
            (is (nil? (h/one events "display")) code)
            (is (= "IllegalArgumentException" (get (h/one events "error") "ename")) code)
            (is (re-find #"non-empty map keyed by MIME type strings"
                         (str (get (h/one events "error") "evalue")))
                code)))))))

;; ---------------------------------------------------------------------------
;; Oracle row: test_repl.py::ReplTest::test_emit_nan_payload_errors_in_cell_and_keeps_framing
;;
;; MEASURED DIVERGENCE IN THE REASON, NOT THE OBSERVABLE (2026-09-02).
;; The oracle's comment names NaN as the ONE corruption vector, because Python's
;; json.dumps defaults to allow_nan=True and writes bare NaN -- non-JSON text
;; that tears framing. clojure.data.json has no such default: it REFUSES ##NaN
;; and ##Inf ("JSON error: cannot write Double NaN"), and send-event!
;; serializes outside the write lock before any byte moves. So a torn frame is
;; structurally impossible here and the pre-flight buys the ERROR CONTRACT
;; instead: this arm's own IllegalArgumentException at the emit call rather than
;; whatever class data.json throws from inside the send. Killing the pre-flight
;; therefore does NOT produce a display frame -- it changes ename/evalue, and
;; the kill receipt says so.
;; ---------------------------------------------------------------------------
(deftest a-nan-payload-fails-in-the-cell-and-no-frame-is-written
  (h/with-repl
    (fn [repl _]
      (doseq [[rid code] [["emit-nan" "(emit {\"application/json\" ##NaN})"]
                          ["emit-inf" "(emit {\"application/json\" [1 ##Inf 3]})"]]]
        (let [events (h/execute repl rid code)
              err (h/one events "error")]
          (is (= "IllegalArgumentException" (get err "ename")) rid)
          (is (re-find #"not JSON-representable" (str (get err "evalue"))) rid)
          (is (= rid (get err "id")) "the failure belongs to the cell that called emit")
          (is (= "error" (get (h/one events "done") "status")) rid)
          (is (nil? (h/one events "display")) rid)))
      (testing "framing survived: every line the runtime wrote is still one JSON object"
        (let [events (h/execute repl "after-emit-nan" "(+ 1 1)")]
          (is (= "2" (get (h/one events "result") "text")))
          (is (= "ok" (get (h/one events "done") "status"))))
        (is (nil? (h/first-torn-line repl))
            "a NaN that reached the writer would have torn a frame; none did")))))
