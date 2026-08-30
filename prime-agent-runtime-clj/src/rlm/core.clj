(ns rlm.core
  "Host bridge and public SCI bindings.

  (rlm prompt) is synchronous. The return value is an admission/spawn handle,
  not the child's final answer. host_reply bypasses the request FIFO because
  the awaiting cell *is* the in-flight execute.

  JSON host replies arrive with string keys. The SCI workspace sees keyword
  keys so (:status reply) matches (rlm) handles. Wire JSON is unchanged."
  (:require [clojure.walk :as walk]))

(def closed ::closed)

(defn fail-pending-host!
  "Unblock every waiting host-request. Called on shutdown and stdin EOF."
  [runtime]
  (reset! (:host-closed runtime) true)
  (doseq [p (vals @(:pending-host runtime))]
    (deliver p closed)))

(defn resolve-host-reply!
  "Reader-thread half of the bridge. Unknown or late ids are dropped."
  [runtime id data]
  (when-let [p (get @(:pending-host runtime) id)]
    (deliver p data)))

(defn host-request
  "Send one typed request to the host and block until the matching reply."
  [runtime data]
  (when @(:host-closed runtime)
    (throw (RuntimeException. "host connection closed; host_request cannot be answered")))
  (let [id (str (random-uuid))
        p (promise)]
    (swap! (:pending-host runtime) assoc id p)
    (try
      ((:send! runtime) {:event "host_request" :id id :data data})
      (let [reply (deref p)]
        (when (identical? closed reply)
          (throw (RuntimeException. "host connection closed; host_request cannot be answered")))
        (walk/keywordize-keys reply))
      (finally
        (swap! (:pending-host runtime) dissoc id)))))

(defn- reply-status [reply]
  (or (get reply "status") (:status reply)))

(defn- reply-field [reply k]
  (or (get reply k) (get reply (keyword k))))

(defn rlm
  "Spawn a recursive child and return once the host admits it.

  Return value is a handle map, not the child's answer."
  ([runtime prompt] (rlm runtime prompt {}))
  ([runtime prompt kwargs]
   (when-not (string? prompt)
     (throw (IllegalArgumentException.
             (str "prompt must be str, got " (some-> prompt class .getName)))))
   (let [payload {:type "rlm.run"
                  :prompt prompt
                  :kwargs (if (map? kwargs) kwargs {})}
         reply (host-request runtime payload)
         status (reply-status reply)]
     (cond
       (= "ok" status)
       {:rlm-child-id (reply-field reply "rlm_child_id")
        :name (reply-field reply "name")
        :session-dir (reply-field reply "session_dir")
        :model (reply-field reply "model")}
       (= "error" status)
       (throw (RuntimeException. (str (or (reply-field reply "error")
                                          "host request rlm.run failed"))))
       :else
       (throw (RuntimeException.
               (str "host request rlm.run returned unexpected status: "
                    (pr-str status))))))))
