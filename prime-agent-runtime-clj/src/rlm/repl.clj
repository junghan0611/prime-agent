(ns rlm.repl
  "JSONL protocol driver. Frames are one locked write. host_reply and interrupt
  bypass the FIFO queue. malformed lines emit ProtocolError and keep serving."
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

(defn- error-event
  [cell-id ^Throwable e]
  ;; OPEN: ename is SCI's wrapper class and traceback carries runtime frames; not oracle-shaped yet.
  {:event "error"
   :id cell-id
   :ename (.getSimpleName (class e))
   :evalue (or (ex-message e) "")
   :traceback (mapv str (.getStackTrace e))})

(defn- handle-execute
  [runtime req]
  (let [id (get req "id")
        code (get req "code")]
    (try
      (let [value (eval/eval-cell (:ctx runtime) (:send! runtime) id code)]
        (when-not (nil? value)
          (eval/bind-_ (:ctx runtime) value)
          (send-event! runtime {:event "result" :id id :text (pr-str value)}))
        (send-event! runtime {:event "done" :id id :status "ok"}))
      (catch Throwable e
        (send-event! runtime (error-event id e))
        (send-event! runtime {:event "done" :id id :status "error"}))
      (finally
        (swap! (:inflight runtime) disj id)))))

(defn- handle-line
  [runtime raw]
  (let [req (json/read-str raw)]
    (when-not (map? req)
      (throw (IllegalArgumentException. "request is not a JSON object")))
    (let [rtype (get req "type")
          queue (:queue runtime)]
      (cond
        (= "interrupt" rtype)
        (when (and (contains? req "id") (not (string? (get req "id"))))
          (protocol-error runtime "interrupt request id must be a string"))

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
            (if (and (= "execute" rtype)
                     (contains? @(:inflight runtime) (get req "id")))
              (protocol-error runtime (str "duplicate in-flight request id: "
                                           (pr-str (get req "id"))))
              (do
                (when (= "execute" rtype)
                  (swap! (:inflight runtime) conj (get req "id")))
                (when (= "shutdown" rtype)
                  (core/fail-pending-host! runtime))
                (.put ^LinkedBlockingQueue queue req)))))))))

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
                 :pending-host (atom {})
                 :host-closed (atom false)
                 ;; id -> live entry. Only snapshots of these cross into SCI.
                 :processes (atom {})
                 :process-counter (atom 0)}
        send! (fn [event] (send-event! runtime event))
        runtime (assoc runtime :send! send!)]
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
