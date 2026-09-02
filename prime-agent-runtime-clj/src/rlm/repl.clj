(ns rlm.repl
  "JSONL protocol driver. Frames are one locked write. host_reply and interrupt
  bypass the FIFO queue. malformed lines emit ProtocolError and keep serving.

  Cells run on their own thread so an interrupt has something to deliver to.
  The interrupt state machine is the oracle's, ported by name: :active is
  _active, :finishing is _finishing_rid, :parked-ids/:parked-any are
  _pending_interrupts, and one lock (:interrupt-lock) orders every decision
  exactly as rlm/repl.py orders them under _interrupt_lock."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [rlm.core :as core]
            [rlm.eval :as eval]
            [rlm.process :as process])
  (:import [java.io BufferedReader BufferedWriter InputStreamReader OutputStreamWriter]
           [java.util.concurrent LinkedBlockingQueue])
  (:gen-class))

(def protocol-version 2)

(def ready-event
  {:event "ready"
   :protocol protocol-version
   :python "clojure-native"
   :runtime {:language "clojure" :engine "sci" :native true}})

(def required-fields
  {"execute" ["id" "code"]
   "shutdown" []})

(defn- json-key [k]
  (if (keyword? k) (name k) (str k)))

(defn send-event!
  [runtime event]
  ;; Bind the shared lock out of the locking form. clj-kondo treats
  ;; (locking (:write-lock runtime) …) as "object is local to locking scope";
  ;; the Object lives on the runtime map for the process lifetime.
  (let [line (str (json/write-str event :key-fn json-key) "\n")
        lock ^Object (:write-lock runtime)]
    (locking lock
      (doto ^java.io.Writer (:out runtime)
        (.write line)
        (.flush)))))

(defn- protocol-error
  [runtime message]
  (send-event! runtime {:event "error"
                        :id nil
                        :ename "ProtocolError"
                        :evalue message
                        :traceback []}))

(def interrupt-ename "KeyboardInterrupt")
(def interrupt-not-delivered-ename "InterruptNotDelivered")

;; How long a delivered interrupt gets to be observed before the runtime says
;; out loud that it was not. A runtime constant on purpose: the number belongs
;; in the frame it explains, not in a document or a gate that would go stale
;; against it. Long enough that an interruptible wait (the host-request deref)
;; always unwinds first, short enough that a caller learns inside one turn.
(def ^:private not-delivered-window-ms 750)

;; ---------------------------------------------------------------------------
;; Interrupt state machine. Oracle twin: rlm/repl.py's _interrupt_lock section.
;; ---------------------------------------------------------------------------

(defn- locked
  "Run f on the interrupt state under the one lock that orders every interrupt
  decision. The lock is bound out of the locking form for the same clj-kondo
  reason send-event! does it. Never send a protocol frame from inside f: the
  write can block on backpressure and the oracle keeps its sends out of the
  lock for that reason."
  [runtime f]
  (let [lock ^Object (:interrupt-lock runtime)]
    (locking lock (f (:interrupt-state runtime)))))

(defn- make-reporter
  "One cell run's at-most-once report that its interrupt went unhonoured.

  Two different facts reach it and both are the same failure: the window
  expired while the cell was still running (the tight-loop case, which never
  reaches a done frame at all -- reporting only after done would be silence
  exactly where the cell can least afford it), or the cell finished inside the
  window without ever observing the interrupt. The window length rides in the
  message so 'not observed' is the precise sentence 'not observed within N ms'.

  id is nil on the frame, and that is what separates this from a real
  cancellation on the same channel. repl-manager.ts::handleEvent folds a
  non-string id to undefined and routes such an error to
  appendKernelDiagnostic; a string id would be attributed to the execution and
  mark it errored. The named cell reports its own outcome -- when it can."
  [runtime id]
  (let [reported (atom false)]
    (fn [reason]
      (when (compare-and-set! reported false true)
        (send-event! runtime
                     {:event "error"
                      :id nil
                      :ename interrupt-not-delivered-ename
                      :evalue (str "the interrupt for " (pr-str id)
                                   (case reason
                                     :still-running
                                     (str " was delivered but not observed within "
                                          not-delivered-window-ms
                                          " ms; the cell is still running")
                                     :ran-to-completion
                                     (str " was delivered but never observed; the cell ran to"
                                          " completion inside the " not-delivered-window-ms
                                          " ms window")))
                      :traceback []})))))

(defn- report-fn-for-run
  "The reporter of the cell run still holding either window, or nil once that
  run is over. Identity on the run token, not the id: a later request may reuse
  the id and must never inherit this run's watchdog."
  [runtime run]
  (locked runtime
          (fn [st]
            (let [{:keys [active finishing]} @st]
              (cond
                (and active (identical? run (:run active))) (:report! active)
                (and finishing (identical? run (:run finishing))) (:report! finishing)
                :else nil)))))

(defn- watch-delivery!
  "Delivery is not observation. Thread.interrupt sets a flag; a cell that never
  reaches an interruptible point never sees it, and a tight loop never reaches
  one at all. This is the clock that turns that silence into a frame."
  [runtime run]
  (doto (Thread. ^Runnable
                 (fn []
                   (try
                     ;; (long ...) is load-bearing, not decoration: a var deref
                     ;; leaves the overload unresolved, and build.sh warns that a
                     ;; reflective interop form links fine and then dies in the
                     ;; native image -- which it did, silently, inside this catch.
                     (Thread/sleep (long not-delivered-window-ms))
                     (when-let [report! (report-fn-for-run runtime run)]
                       (report! :still-running))
                     (catch Throwable _ nil)))
                 "rlm-interrupt-watch")
    (.setDaemon true)
    (.start)))

(defn- request-interrupt!
  "Reader-thread half. Oracle twin: rlm/repl.py::_request_interrupt, in its
  order. The active request owns an interrupt that names it or names nothing;
  otherwise a request in its finishing window owns it (never parked -- parking
  there would leak the interrupt onto the NEXT request); otherwise it parks for
  a still-inflight request; otherwise it is dropped, silently, because that is
  the one silence the oracle also keeps."
  [runtime target]
  (let [entry (locked runtime
                      (fn [st]
                        (let [{:keys [active finishing]} @st]
                          (cond
                            (and active (or (nil? target) (= target (:id active))))
                            (do (swap! st assoc-in [:active :interrupted?] true)
                                active)

                            (and finishing (or (nil? target) (= target (:id finishing))))
                            (do (swap! st assoc-in [:finishing :interrupted?] true)
                                finishing)

                            (and target (contains? @(:inflight runtime) target))
                            (do (swap! st update :parked-ids conj target) nil)

                            (and (nil? target) (seq @(:inflight runtime)))
                            (do (swap! st assoc :parked-any true) nil)

                            :else nil))))]
    ;; Delivery and the watchdog are side effects on other threads; keep them
    ;; out of the lock so a cell unwinding on the interrupt cannot block the
    ;; reader, and so the watchdog can take the lock itself when it wakes.
    (when entry
      (.interrupt ^Thread (:thread entry))
      (watch-delivery! runtime (:run entry)))))

(defn- activate!
  "Move id into the active slot and consume any interrupt parked for it.
  Oracle twin: _run_guarded's activation block, which calls
  _consume_pending_interrupt(rid) under the lock and cancels before the first
  step. Returns true when this cell is cancelled before it ever runs."
  [runtime id ^Thread thread run report!]
  (locked runtime
          (fn [st]
            (let [{:keys [parked-ids parked-any]} @st
                  parked? (or parked-any (contains? parked-ids id))]
              (swap! st (fn [s]
                          (-> s
                              (assoc :active {:id id :thread thread :run run
                                              :report! report! :interrupted? parked?})
                              (assoc :parked-any false)
                              (update :parked-ids disj id))))
              parked?))))

(defn- begin-finishing!
  "Close the active window and open the finishing one in a single locked step.
  Oracle twin: _run_guarded's finally sets _finishing_rid BEFORE clearing
  _active so the lock-free reader always finds the id in exactly one slot --
  a torn in-between state would let the interrupt fall through to parking,
  where the next request would eat it. Returns true when the active window
  already took an interrupt."
  [runtime id ^Thread thread run report!]
  (locked runtime
          (fn [st]
            (let [was (get-in @st [:active :interrupted?])]
              (swap! st assoc
                     :finishing {:id id :thread thread :run run
                                 :report! report! :interrupted? false}
                     :active nil)
              (boolean was)))))

(defn- finish-request!
  "Drop a finished request. Oracle twin: _finish_locked -- the id leaves
  inflight, its parked target dies with it (so a later request reusing the id
  cannot be cancelled by a stale one), and a parked untargeted interrupt
  survives only while another request is still inflight. Returns true when the
  finishing window took an interrupt."
  [runtime id]
  (locked runtime
          (fn [st]
            (let [was (when (= id (get-in @st [:finishing :id]))
                        (get-in @st [:finishing :interrupted?]))]
              (swap! (:inflight runtime) disj id)
              (swap! st (fn [s]
                          (cond-> s
                            (= id (get-in s [:finishing :id])) (assoc :finishing nil)
                            :always (update :parked-ids disj id)
                            (empty? @(:inflight runtime)) (assoc :parked-any false))))
              (boolean was)))))

(defn- active-interrupted?
  [runtime]
  (locked runtime (fn [st] (boolean (get-in @st [:active :interrupted?])))))

(defn- finishing-interrupted?
  [runtime]
  (locked runtime (fn [st] (boolean (get-in @st [:finishing :interrupted?])))))

(defn- current-cell-id
  "The cell running at call time. Oracle twin: rlm/repl.py's _current_cell
  ContextVar, read at emit(). No new state -- H9 already keeps the running id
  in the :active slot, and reading it under the same lock keeps the answer
  whole while the reader thread is moving that slot."
  [runtime]
  (locked runtime (fn [st] (get-in @st [:active :id]))))

(defn- emit-display!
  "Ship one display frame: a map of MIME type -> JSON payload, tagged with the
  cell running now. Oracle twin: rlm/repl.py::emit.

  The pre-flight serialization is NOT a framing guard in this arm, and the
  reason matters. The oracle needs one because Python's json.dumps defaults to
  allow_nan=True and would write bare NaN -- non-JSON text that tears the
  host's framing. Measured here (2026-09-02): clojure.data.json REFUSES ##NaN
  and ##Inf outright ('JSON error: cannot write Double NaN'), and send-event!
  serializes OUTSIDE the write lock, before a single byte moves, so a torn
  frame is structurally impossible. What the pre-flight buys is the ERROR
  CONTRACT: the failure surfaces as this arm's own IllegalArgumentException at
  the emit call, not as whatever class data.json happens to throw from inside
  the send. Pinning a contract on a dependency's exception would make that
  dependency's behaviour our promise."
  [runtime data]
  (when-not (and (map? data) (seq data) (every? string? (keys data)))
    (throw (IllegalArgumentException.
            "emit requires a non-empty map keyed by MIME type strings")))
  (try
    (json/write-str data :key-fn json-key)
    (catch Throwable e
      (throw (IllegalArgumentException.
              (str "emit payload is not JSON-representable: " (or (ex-message e) ""))))))
  (send-event! runtime {:event "display"
                        :id (current-cell-id runtime)
                        :data data}))

(defn- interrupted-cause?
  "True when this throwable chain is an unwinding Thread.interrupt. SCI wraps a
  cell failure, so the InterruptedException is a cause, not the head."
  [^Throwable e]
  (loop [^Throwable t e
         depth 0]
    (cond
      (nil? t) false
      (> depth 32) false
      (instance? InterruptedException t) true
      (instance? java.nio.channels.ClosedByInterruptException t) true
      :else (recur (.getCause t) (inc depth)))))

(defn- interrupt-event
  "A cancelled cell reports as the oracle's KeyboardInterrupt. Oracle twin:
  _interrupt_event, whose traceback is exactly this one line when there is no
  cell stack to format -- and this runtime has no cell-source stack (declared
  deviation, docs/clojure-runtime.md)."
  [cell-id]
  {:event "error"
   :id cell-id
   :ename interrupt-ename
   :evalue ""
   :traceback ["KeyboardInterrupt\n"]})

(defn- error-event
  [cell-id ^Throwable e]
  ;; OPEN: ename is SCI's wrapper class and traceback carries runtime frames; not oracle-shaped yet.
  {:event "error"
   :id cell-id
   :ename (.getSimpleName (class e))
   :evalue (or (ex-message e) "")
   :traceback (mapv str (.getStackTrace e))})

(defn- cell-body
  [runtime req send-done!]
  (let [id (get req "id")
        code (get req "code")
        thread (Thread/currentThread)
        ;; Identity, not the id: a later request may reuse the id, and a
        ;; watchdog left over from this run must never speak for that one.
        run (Object.)
        report! (make-reporter runtime id)
        pre-cancelled? (activate! runtime id thread run report!)
        ;; ---- active window ----
        outcome (if pre-cancelled?
                  {:status "error" :cancelled? true}
                  (try
                    {:status "ok" :value (eval/eval-cell (:ctx runtime) (:send! runtime) id code)}
                    (catch Throwable e
                      (if (and (active-interrupted? runtime) (interrupted-cause? e))
                        {:status "error" :cancelled? true}
                        {:status "error" :throwable e}))))
        active-interrupt? (begin-finishing! runtime id thread run report!)
        ;; ---- finishing window: the post-run bind and repr ----
        outcome (if (and (= "ok" (:status outcome)) (some? (:value outcome)))
                  (try
                    (let [v (:value outcome)]
                      (eval/bind-_ (:ctx runtime) v)
                      (assoc outcome :result-text (pr-str v)))
                    (catch Throwable e
                      (if (and (or active-interrupt? (finishing-interrupted? runtime))
                               (interrupted-cause? e))
                        {:status "error" :cancelled? true}
                        {:status "error" :throwable e})))
                  outcome)
        finishing-interrupt? (finish-request! runtime id)
        ;; The window is closed before any send, so a late interrupt can never
        ;; tear a frame mid-write. Oracle twin: _finish_request runs before the
        ;; result/error/done sends for exactly that reason. Clearing the flag
        ;; here also keeps an unhonoured interrupt out of the protocol writes.
        _ (Thread/interrupted)
        cancelled? (boolean (:cancelled? outcome))
        owned? (or pre-cancelled? active-interrupt? finishing-interrupt?)]
    (when-let [text (:result-text outcome)]
      (send-event! runtime {:event "result" :id id :text text}))
    (cond
      cancelled? (send-event! runtime (interrupt-event id))
      (:throwable outcome) (send-event! runtime (error-event id (:throwable outcome))))
    (send-done! (:status outcome))
    ;; At-most-once: if the watchdog already spoke while this cell was running,
    ;; the fact is on the wire and saying it twice would invent a second event.
    (when (and owned? (not cancelled?))
      (report! :ran-to-completion))))

(defn- run-cell!
  "Run one execute on its own thread. The request must end even if the driver
  itself fails, and it must end exactly once."
  [runtime req]
  (let [id (get req "id")
        done? (atom false)
        send-done! (fn [status]
                     (when (compare-and-set! done? false true)
                       (send-event! runtime {:event "done" :id id :status status})))]
    (try
      (cell-body runtime req send-done!)
      (catch Throwable e
        (finish-request! runtime id)
        (when-not @done?
          (send-event! runtime (error-event id e)))
        (send-done! "error")))))

(defn- handle-execute
  [runtime req]
  (let [^Thread t (doto (Thread. ^Runnable #(run-cell! runtime req)
                                 (str "rlm-cell-" (get req "id")))
                    (.setDaemon true)
                    (.start))]
    ;; One cell at a time, as before: the serve loop waits here. What changed is
    ;; that the cell now has a thread of its own for an interrupt to land on.
    (.join t)))

(defn- handle-line
  [runtime raw]
  (let [req (json/read-str raw)]
    (when-not (map? req)
      (throw (IllegalArgumentException. "request is not a JSON object")))
    (let [rtype (get req "type")
          queue (:queue runtime)]
      (cond
        (= "interrupt" rtype)
        (let [id (get req "id")]
          (if (and (contains? req "id") (not (string? id)))
            (protocol-error runtime "interrupt request id must be a string")
            (request-interrupt! runtime id)))

        (= "host_reply" rtype)
        (let [id (get req "id")
              data (get req "data")]
          (if (and (string? id) (map? data))
            (core/resolve-host-reply! runtime id data)
            (protocol-error runtime "host_reply request needs string id and dict data")))

        (not (contains? required-fields rtype))
        (protocol-error runtime (str "unknown request type: " (pr-str rtype)))

        :else
        (let [missing (filterv #(not (string? (get req %))) (get required-fields rtype))]
          (if (seq missing)
            (protocol-error runtime (str rtype " request needs string fields: "
                                         (str/join ", " missing)))
            ;; The duplicate check and the inflight add are one step under the
            ;; interrupt lock: a reused in-flight id would corrupt interrupt and
            ;; finish bookkeeping. Oracle twin: _handle_line's `with
            ;; _interrupt_lock` around exactly this pair -- and, like the oracle,
            ;; the protocol write happens after the lock is released.
            (let [duplicate? (and (= "execute" rtype)
                                  (locked runtime
                                          (fn [_]
                                            (let [id (get req "id")]
                                              (if (contains? @(:inflight runtime) id)
                                                true
                                                (do (swap! (:inflight runtime) conj id)
                                                    false))))))]
              (if duplicate?
                (protocol-error runtime (str "duplicate in-flight request id: "
                                             (pr-str (get req "id"))))
                (do
                  (when (= "shutdown" rtype)
                    (core/fail-pending-host! runtime))
                  (.put ^LinkedBlockingQueue queue req))))))))))

(defn- start-reader!
  [runtime]
  (future
    (try
      (loop []
        (let [line (.readLine ^BufferedReader (:in runtime))]
          (if (nil? line)
            (do (core/fail-pending-host! runtime)
                (.put ^LinkedBlockingQueue (:queue runtime) {"type" "shutdown"}))
            (do
              (let [trimmed (str/trim line)]
                (when (seq trimmed)
                  (try
                    (handle-line runtime trimmed)
                    (catch Throwable e
                      (protocol-error runtime (str (.getSimpleName (class e)) ": "
                                                   (or (ex-message e) "")))))))
              (recur)))))
      (catch Throwable _
        (core/fail-pending-host! runtime)
        (.put ^LinkedBlockingQueue (:queue runtime) {"type" "shutdown"})))))

(defn make-runtime
  [in out]
  (let [runtime {:in in
                 :out out
                 :write-lock (Object.)
                 :queue (LinkedBlockingQueue.)
                 :inflight (atom #{})
                 ;; One lock orders every interrupt decision, as _interrupt_lock
                 ;; does in the oracle. Its state is the oracle's, by name.
                 :interrupt-lock (Object.)
                 :interrupt-state (atom {:active nil
                                         :finishing nil
                                         :parked-ids #{}
                                         :parked-any false})
                 :pending-host (atom {})
                 :host-closed (atom false)
                 ;; id -> live entry. Only snapshots of these cross into SCI.
                 :processes (atom {})
                 :process-counter (atom 0)}
        send! (fn [event] (send-event! runtime event))
        ;; Installed like :send! -- an accessor over state repl already owns, so
        ;; rlm.eval can bind the verb without a cycle back into this namespace.
        emit! (fn [data] (emit-display! runtime data))
        runtime (assoc runtime :send! send! :emit! emit!)]
    (process/install-shutdown-hook! runtime)
    (assoc runtime :ctx (eval/make-ctx runtime))))

(defn serve
  "Block until shutdown/EOF. Sends ready as the first frame."
  [in out]
  (let [runtime (make-runtime in out)]
    (start-reader! runtime)
    (send-event! runtime ready-event)
    (loop []
      (let [req (.take ^LinkedBlockingQueue (:queue runtime))]
        (case (get req "type")
          "shutdown"
          ;; Kill the tree before answering: a host that exits on `done` must
          ;; not leave our children behind.
          (do (process/kill-all! runtime)
              (when (string? (get req "id"))
                (send-event! runtime {:event "done" :id (get req "id") :status "ok"})))
          ;; reader only queues execute/shutdown (unknown types die in handle-line).
          "execute"
          (do (handle-execute runtime req)
              (recur)))))))

(defn -main
  [& _]
  (let [in (BufferedReader. (InputStreamReader. System/in "UTF-8"))
        out (BufferedWriter. (OutputStreamWriter. System/out "UTF-8"))]
    (binding [*out* (java.io.PrintWriter. System/err true)
              *err* (java.io.PrintWriter. System/err true)]
      (try
        (serve in out)
        (System/exit 0)
        (catch Throwable e
          (.printStackTrace e System/err)
          (System/exit 1))))))
