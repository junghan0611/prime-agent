(ns rlm.eval
  "Persistent SCI context. One context lives for the process lifetime."
  (:require [sci.core :as sci]
            [rlm.core :as core]
            [rlm.harness-state :as harness]
            [rlm.io :as io]
            [rlm.process :as process]))

(defn make-ctx
  [runtime]
  (let [host-fn (fn [data] (core/host-request runtime data))
        rlm-fn (fn
                 ([prompt] (core/rlm runtime prompt))
                 ([prompt kwargs] (core/rlm runtime prompt kwargs)))
        ;; Registry verbs. The registry is the host's, so these are how a
        ;; workspace emptied by a kernel restart recovers its child handles.
        children-fn (fn [] (core/rlm-children runtime))
        delete-child-fn (fn [target] (core/rlm-delete-child runtime target))
        ;; Model search is workspace data, not a typed handle -- same shape rule
        ;; as (rlm-children).
        find-models-fn (fn
                         ([] (core/find-models runtime))
                         ([query] (core/find-models runtime query))
                         ([query limit] (core/find-models runtime query limit)))
        ;; Display frames belong to the protocol driver, so the verb is the
        ;; function rlm.repl installed on the runtime rather than a call back
        ;; into that namespace.
        emit-fn (fn [data] ((:emit! runtime) data))
        read-fn (fn [path] (io/read-text path))
        ;; Write verbs return receipt maps. No file handle, no spit.
        write-fn (fn [path content] (io/write-text path content))
        edit-fn (fn [path old new] (io/edit-text path old new))
        ;; Harness verbs are the workspace's own notes. Entries go in and come
        ;; back as data maps; the store itself is a file this side owns, and the
        ;; opts a cell may set never include where that file lives.
        wopts harness/workspace-opts
        h-create-fn (fn
                      ([kind title content] (harness/create kind title content))
                      ([kind title content opts] (harness/create kind title content (wopts opts))))
        h-update-fn (fn
                      ([kind id title content] (harness/update-entry kind id title content))
                      ([kind id title content opts] (harness/update-entry kind id title content (wopts opts))))
        h-upsert-fn (fn
                      ([kind title content] (harness/upsert kind title content))
                      ([kind title content opts] (harness/upsert kind title content (wopts opts))))
        h-get-fn (fn
                   ([kind id] (harness/get-entry kind id))
                   ([kind id opts] (harness/get-entry kind id (wopts opts))))
        h-delete-fn (fn
                      ([kind id] (harness/delete kind id))
                      ([kind id opts] (harness/delete kind id (wopts opts))))
        h-list-fn (fn
                    ([] (harness/entries))
                    ([kind] (harness/entries kind))
                    ([kind opts] (harness/entries kind (wopts opts))))
        h-refine-fn (fn
                      ([trigger changes] (harness/record-refinement trigger changes))
                      ([trigger changes opts] (harness/record-refinement trigger changes (wopts opts))))
        h-refinements-fn (fn
                           ([] (harness/refinements))
                           ([opts] (harness/refinements (wopts opts))))
        ;; Process verbs take and return ids and data maps. The live
        ;; java.lang.Process stays on the runtime registry.
        start-fn (fn [command] (process/start runtime command))
        poll-fn (fn [id] (process/poll runtime id))
        tail-fn (fn
                  ([id] (process/tail runtime id))
                  ([id n] (process/tail runtime id n)))
        kill-fn (fn [id] (process/kill runtime id))
        list-fn (fn [] (process/ls runtime))]
    (sci/init {:namespaces {'user {'host-request host-fn
                                   'rlm rlm-fn
                                   'find-models find-models-fn
                                   'emit emit-fn
                                   'rlm-children children-fn
                                   'rlm-delete-child delete-child-fn
                                   'read-text read-fn
                                   'write-text write-fn
                                   'edit-text edit-fn
                                   'harness-create h-create-fn
                                   'harness-update h-update-fn
                                   'harness-upsert h-upsert-fn
                                   'harness-get h-get-fn
                                   'harness-delete h-delete-fn
                                   'harness-list h-list-fn
                                   'harness-record-refinement h-refine-fn
                                   'harness-refinements h-refinements-fn
                                   'process-start start-fn
                                   'process-poll poll-fn
                                   'process-tail tail-fn
                                   'process-kill kill-fn
                                   'process-list list-fn}}})))

(defn bind-_
  [ctx value]
  (sci/intern ctx 'user '_ value)
  value)

(defn eval-cell
  "Evaluate every form in source. Returns the last value (possibly nil).

  accepted Phase A deviation: output is batched at cell end, not streamed from
  the writing thread (oracle repl.md). Attribution (cell id) matches; mid-cell
  streaming does not. Not protocol v2 output parity."
  [ctx send! cell-id source]
  (let [out (java.io.StringWriter.)
        err (java.io.StringWriter.)]
    (try
      (sci/binding [sci/out out
                    sci/err err]
        (sci/eval-string* ctx source))
      (finally
        (let [o (str out)
              e (str err)]
          (when (pos? (count o))
            (send! {:event "stdout" :id cell-id :text o}))
          (when (pos? (count e))
            (send! {:event "stderr" :id cell-id :text e})))))))
