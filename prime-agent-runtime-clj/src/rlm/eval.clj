(ns rlm.eval
  "Persistent SCI context. One context lives for the process lifetime."
  (:require [sci.core :as sci]
            [rlm.core :as core]
            [rlm.io :as io]
            [rlm.process :as process]))

(defn make-ctx
  [runtime]
  (let [host-fn (fn [data] (core/host-request runtime data))
        rlm-fn (fn
                 ([prompt] (core/rlm runtime prompt))
                 ([prompt kwargs] (core/rlm runtime prompt kwargs)))
        read-fn (fn [path] (io/read-text path))
        ;; Write verbs return receipt maps. No file handle, no spit.
        write-fn (fn [path content] (io/write-text path content))
        edit-fn (fn [path old new] (io/edit-text path old new))
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
                                   'read-text read-fn
                                   'write-text write-fn
                                   'edit-text edit-fn
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
