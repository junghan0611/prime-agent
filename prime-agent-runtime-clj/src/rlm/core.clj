(ns rlm.core
  "Host bridge and public SCI bindings.

  (rlm prompt) is synchronous. The return value is an admission/spawn handle,
  not the child's final answer. host_reply bypasses the request FIFO because
  the awaiting cell *is* the in-flight execute.

  JSON host replies arrive with string keys. The SCI workspace sees keyword
  keys so (:status reply) matches (rlm) handles. Wire JSON is unchanged.

  The child registry lives in the host, not in this process. That is what makes
  it the one piece of workspace state that outlives both host compaction and a
  kernel restart, and why (rlm-children) is a recovery verb rather than a cache."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]))

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

(defn- reply-ok!
  "Every host reply carries a status. Raise the host's own message rather than
  hand the workspace a map that quietly lacks its payload."
  [reply op]
  (let [status (reply-status reply)]
    (cond
      (= "ok" status) reply
      (= "error" status)
      (throw (RuntimeException. (str (or (reply-field reply "error")
                                         (str "host request " op " failed")))))
      :else
      (throw (RuntimeException.
              (str "host request " op " returned unexpected status: "
                   (pr-str status)))))))

(defn- dash-keys
  "Host JSON spells registry fields snake_case, while (rlm ...) already hands the
  workspace dashed keys. Normalize to one shape so a handle recovered after
  compaction or a restart matches the handle the workspace learned at spawn."
  [form]
  (let [dash (fn [[k v]]
               (if (keyword? k)
                 [(keyword (str/replace (name k) \_ \-)) v]
                 [k v]))]
    (walk/postwalk (fn [x] (if (map? x) (into {} (map dash) x) x)) form)))

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
         reply (reply-ok! (host-request runtime payload) "rlm.run")]
     {:rlm-child-id (reply-field reply "rlm_child_id")
      :name (reply-field reply "name")
      :session-dir (reply-field reply "session_dir")
      :model (reply-field reply "model")})))

(defn rlm-children
  "The direct child registry, as workspace data.

  The registry is the host's, so it survives host compaction and a kernel
  restart that empties every var in this process. A restarted workspace gets
  its child handles back here, in the key shape (rlm ...) returns."
  [runtime]
  (let [reply (reply-ok! (host-request runtime {:type "rlm.list_subagents"})
                         "rlm.list_subagents")
        subagents (reply-field reply "subagents")]
    (when-not (sequential? subagents)
      (throw (RuntimeException.
              "host request rlm.list_subagents returned no subagents list")))
    (mapv dash-keys subagents)))

(defn- model-from-payload
  "Oracle twin: rlm/__init__.py::_model_from_payload. Every one of the four
  fields must be a NON-EMPTY string; the oracle folds that into one message and
  so does this, because a caller cannot act on which field was missing."
  [entry]
  (when-not (map? entry)
    (throw (RuntimeException. "rlm.find_models returned an invalid model entry")))
  (let [fields (mapv #(reply-field entry %) ["provider" "id" "name" "selector"])]
    (when-not (every? #(and (string? %) (seq %)) fields)
      (throw (RuntimeException. "rlm.find_models returned an invalid model entry")))
    (zipmap [:provider :id :name :selector] fields)))

(defn find-models
  "Search the bounded model list the host backs with active credentials.

  Oracle twin: rlm/__init__.py::find_models, defaults and messages included.
  Returns workspace data -- a vector of maps -- never a typed handle."
  ([runtime] (find-models runtime "" 8))
  ([runtime query] (find-models runtime query 8))
  ([runtime query limit]
   (when-not (string? query)
     (throw (IllegalArgumentException.
             (str "query must be str, got " (some-> query class .getName)))))
   (when-not (integer? limit)
     (throw (IllegalArgumentException.
             (str "limit must be int, got " (some-> limit class .getName)))))
   (let [reply (reply-ok! (host-request runtime {:type "rlm.find_models"
                                                 :query query
                                                 :limit limit})
                          "rlm.find_models")
         models (reply-field reply "models")]
     (when-not (sequential? models)
       (throw (RuntimeException. "rlm.find_models returned an invalid models list")))
     (mapv model-from-payload models))))

(defn- child-target
  "A child id string, or any handle/registry entry that carries one."
  [target]
  (cond
    (string? target) (not-empty (str/trim target))
    (map? target) (some (fn [k]
                          (let [v (get target k)]
                            (when (string? v) (not-empty (str/trim v)))))
                        [:rlm-child-id :rlm_child_id])
    :else nil))

(defn rlm-delete-child
  "Drop one direct child from the registry. Takes the id or the handle itself.

  Rejected before any frame reaches the host when the target carries no id."
  [runtime target]
  (let [id (child-target target)]
    (when-not id
      (throw (IllegalArgumentException.
              (str "delete target must be a child id string or a handle map "
                   "carrying :rlm-child-id, got " (pr-str target)))))
    (-> (host-request runtime {:type "rlm.delete_subagent" :target id})
        (reply-ok! "rlm.delete_subagent")
        (dissoc :status)
        (dash-keys))))
