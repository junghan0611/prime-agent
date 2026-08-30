(ns rlm.process
  "Process lifecycle. Shaped after the Python oracle's bash() handle
  (prime-agent-runtime/src/rlm/bash.py): spawn now, observe later.

  SCI never receives a live process. The registry keeps java.lang.Process on
  the runtime map and hands the workspace an immutable snapshot map keyed by
  :process-id; every other verb takes that id back.

  Class names are fully qualified and every interop form is hinted on purpose.
  (:import [java.lang ProcessBuilder]) compiles the constructor down to
  RT.classForName and the native image then dies at the first spawn with
  ClassNotFoundException; a reflective call would need reflect-config, which
  docs/clojure-runtime.md keeps closed."
  (:require [clojure.string :as str]))

;; Bounded capture: head + tail with the middle dropped, like the oracle's
;; _BoundedBuffer, sized down for a native runtime that holds several at once.
(def ^:private head-cap (* 128 1024))
(def ^:private tail-cap (* 128 1024))
(def ^:private read-chunk 65536)
(def ^:private max-live 16)
(def ^:private max-entries 64)
(def ^:private term-grace-ms 2000)
(def ^:private kill-wait-ms 2000)
(def ^:private drain-grace-ms 500)
(def ^:private default-tail-lines 50)
(def ^:private max-tail-lines 2000)

(def ^:private empty-capture
  {:head [] :head-size 0 :tail [] :tail-size 0 :dropped 0 :total 0})

(defn- byte-cat
  ^bytes [chunks]
  (let [total (reduce (fn [^long acc ^bytes c] (+ acc (alength c))) 0 chunks)
        out (byte-array total)]
    (loop [cs (seq chunks) off 0]
      (if cs
        (let [^bytes c (first cs)
              n (alength c)]
          (System/arraycopy c 0 out off n)
          (recur (next cs) (+ off n)))
        out))))

(defn- trim-tail
  [state]
  (loop [state state]
    (let [excess (- (long (:tail-size state)) tail-cap)]
      (if-not (pos? excess)
        state
        (let [^bytes oldest (nth (:tail state) 0)
              len (alength oldest)]
          (recur
           (if (<= len excess)
             (-> state
                 (update :tail #(subvec % 1))
                 (update :tail-size - len)
                 (update :dropped + len))
             (-> state
                 (assoc-in [:tail 0] (java.util.Arrays/copyOfRange oldest (int excess) len))
                 (update :tail-size - excess)
                 (update :dropped + excess)))))))))

(defn- capture-chunk
  [state ^bytes chunk]
  (let [n (alength chunk)
        state (update state :total + n)
        room (max 0 (- head-cap (long (:head-size state))))
        take-n (min room n)
        state (if (pos? take-n)
                (-> state
                    (update :head conj (java.util.Arrays/copyOfRange chunk 0 (int take-n)))
                    (update :head-size + take-n))
                state)]
    (if (< take-n n)
      (trim-tail (-> state
                     (update :tail conj (java.util.Arrays/copyOfRange chunk (int take-n) n))
                     (update :tail-size + (- n take-n))))
      state)))

(defn- capture-text
  ^String [state]
  (let [dropped (long (:dropped state))
        utf8 java.nio.charset.StandardCharsets/UTF_8]
    (if (zero? dropped)
      ;; One decode over head+tail: a multi-byte char split across the seam
      ;; stays whole while nothing has been dropped yet.
      (String. (byte-cat (concat (:head state) (:tail state))) utf8)
      (str (String. (byte-cat (:head state)) utf8)
           "\n... [" dropped " bytes dropped] ...\n"
           (String. (byte-cat (:tail state)) utf8)))))

(defn- pump!
  "Drain the child's merged stdout/stderr into the bounded capture. This thread
  never writes to System/out — the protocol stream is the writer's alone."
  [^java.io.InputStream in capture eof]
  (let [buf (byte-array read-chunk)]
    (try
      (loop []
        (let [n (.read in buf)]
          (when (pos? n)
            (swap! capture capture-chunk (java.util.Arrays/copyOfRange buf 0 (int n)))
            (recur))))
      (catch Throwable _ nil)
      (finally
        (try (.close in) (catch Throwable _ nil))
        (deliver eof true)))))

(defn- executable?
  [^String path]
  (let [f (java.io.File. path)]
    (and (.isFile f) (.canExecute f))))

(defn- on-path
  ^String [^String name]
  (first (filter executable?
                 (map #(str % "/" name)
                      (str/split (or (System/getenv "PATH") "") #":")))))

(defn- shell-path
  "PRIME_AGENT_BASH_SHELL (absolute) wins, then bash on PATH, then /bin/sh —
  the oracle's _shell() order."
  ^String []
  (let [override (System/getenv "PRIME_AGENT_BASH_SHELL")]
    (if (seq override)
      (if (.startsWith ^String override "/")
        override
        (throw (IllegalArgumentException. "PRIME_AGENT_BASH_SHELL must be an absolute path")))
      (or (on-path "bash") "/bin/sh"))))

(defn- setsid-path
  "util-linux setsid, the only containment we have: the JVM cannot setpgid a
  child and Java has no killpg. Absent, spawning still works and cleanup falls
  back to the descendants sweep alone."
  ^String []
  (on-path "setsid"))

(defn- signal-group!
  "Signal whole process groups. This is what reaches a child the leader left
  behind: `cmd & exit 0` reparents it out of .descendants() but never out of
  its process group. Java has no killpg, so one short-lived shell signals every
  group at once. Its streams are discarded, so nothing it prints can reach the
  protocol writer."
  [pgids ^String signal]
  (when (seq pgids)
    (try
      (let [script (str "for g in " (str/join " " (map #(str "-" %) pgids))
                        "; do kill -s " signal " -- \"$g\" 2>/dev/null; done; exit 0")
            ^java.util.List argv [(shell-path) "-c" script]
            pb (java.lang.ProcessBuilder. argv)]
        (.redirectOutput pb java.lang.ProcessBuilder$Redirect/DISCARD)
        (.redirectError pb java.lang.ProcessBuilder$Redirect/DISCARD)
        (let [^java.lang.Process p (.start pb)]
          (try (.close (.getOutputStream p)) (catch Throwable _ nil))
          (when-not (.waitFor p 2 java.util.concurrent.TimeUnit/SECONDS)
            (.destroyForcibly p))))
      (catch Throwable _ nil))))

(defn- settle!
  "Give the pump a bounded moment to finish once the process is gone, so poll
  and tail do not report a half-read pipe."
  [entry]
  (when-not (.isAlive ^java.lang.Process (:proc entry))
    (deref (:eof entry) drain-grace-ms nil)))

(defn- snapshot
  "The only shape that crosses into SCI: plain data, no live object."
  [entry]
  (let [^java.lang.Process p (:proc entry)
        alive (.isAlive p)
        cap @(:capture entry)]
    {:process-id (:id entry)
     :command (:command entry)
     :pid (.pid p)
     :status (if alive :running :exited)
     :exit-code (when-not alive (.exitValue p))
     :killed @(:killed entry)
     ;; false means this host has no setsid: a backgrounded child that outlives
     ;; the leader can escape cleanup.
     :contained (some? (:pgid entry))
     :output-bytes (:total cap)
     :output-truncated (pos? (long (:dropped cap)))}))

(defn- coerce-id
  [x]
  (cond
    (string? x) x
    (map? x) (let [v (:process-id x)] (when (string? v) v))
    :else nil))

(defn- require-entry
  [runtime x]
  (let [id (coerce-id x)]
    (when-not id
      (throw (IllegalArgumentException.
              "process id must be a string or a handle map with :process-id")))
    (or (get @(:processes runtime) id)
        (throw (IllegalArgumentException. (str "unknown process id: " id))))))

(defn- tree-handles
  "Descendants snapshot taken while the leader is still alive. The handles stay
  usable after the leader dies and its children reparent out of .descendants()."
  [^java.lang.Process p]
  (try
    (vec (.toList (.descendants (.toHandle p))))
    (catch Throwable _ [])))

(defn- all-dead?
  [trees]
  (every? (fn [[entry hs]]
            (and (not (.isAlive ^java.lang.Process (:proc entry)))
                 (not-any? (fn [^java.lang.ProcessHandle h] (.isAlive h)) hs)))
          trees))

(defn- await-dead
  [trees ^long budget-ms]
  (let [deadline (+ (System/currentTimeMillis) budget-ms)]
    (loop []
      (cond
        (all-dead? trees) true
        (>= (System/currentTimeMillis) deadline) false
        :else (do (Thread/sleep 20) (recur))))))

(defn- terminate!
  "Group TERM, then group KILL. The process group is the primary handle: it
  still holds a child whose leader already exited, which .descendants() cannot
  see. The descendants sweep stays as the secondary path — it is all there is
  when the host has no setsid."
  [entries]
  (let [trees (mapv (fn [entry] [entry (tree-handles ^java.lang.Process (:proc entry))]) entries)
        pgids (into [] (keep :pgid entries))]
    (doseq [[entry _] trees]
      (when (.isAlive ^java.lang.Process (:proc entry))
        (reset! (:killed entry) true)))
    (signal-group! pgids "TERM")
    (doseq [[entry hs] trees]
      (doseq [^java.lang.ProcessHandle h hs]
        (try (.destroy h) (catch Throwable _ nil)))
      (try (.destroy ^java.lang.Process (:proc entry)) (catch Throwable _ nil)))
    (await-dead trees term-grace-ms)
    ;; Re-enumerate once: a descendant spawned while the leader was still alive
    ;; is not in the first snapshot.
    (let [trees (mapv (fn [[entry hs]]
                        [entry (into hs (tree-handles ^java.lang.Process (:proc entry)))])
                      trees)]
      (signal-group! pgids "KILL")
      (doseq [[entry hs] trees]
        (doseq [^java.lang.ProcessHandle h hs]
          (try (.destroyForcibly h) (catch Throwable _ nil)))
        (try (.destroyForcibly ^java.lang.Process (:proc entry)) (catch Throwable _ nil)))
      (await-dead trees kill-wait-ms))))

(defn- live-count
  [runtime]
  (count (filter (fn [entry] (.isAlive ^java.lang.Process (:proc entry)))
                 (vals @(:processes runtime)))))

(defn- prune!
  "Drop the oldest finished entries once the registry outgrows its cap. A live
  process is never pruned."
  [runtime]
  (swap! (:processes runtime)
         (fn [m]
           (if (<= (count m) max-entries)
             m
             (let [dead (->> (vals m)
                             (remove (fn [entry] (.isAlive ^java.lang.Process (:proc entry))))
                             (sort-by :started-ms))]
               (reduce (fn [acc entry] (dissoc acc (:id entry)))
                       m
                       (take (- (count m) max-entries) dead)))))))

(defn start
  "Spawn command under the shell and return its handle snapshot.

  The child gets pipes, never our protocol descriptors: stdout and stderr are
  merged into the bounded capture and stdin is closed so a reader sees EOF.

  setsid puts the command in its own session, so the leader pid doubles as the
  process group id and cleanup can signal the whole group. setsid execs in
  place here (a Java-spawned child is never already a group leader), which is
  what keeps pid == pgid; the group-kill test pins that."
  [runtime command]
  (when-not (string? command)
    (throw (IllegalArgumentException. "process-start command must be a string")))
  (when (str/blank? command)
    (throw (IllegalArgumentException. "process-start command must not be blank")))
  (prune! runtime)
  (when (>= (live-count runtime) max-live)
    (throw (IllegalStateException.
            (str "process-start refused: " max-live " live processes already; kill one first"))))
  ;; The hint has to sit on a local: on the literal itself the compiler falls
  ;; back to the reflective ctor, which the native image cannot resolve.
  (let [setsid (setsid-path)
        shell (shell-path)
        ^java.util.List argv (if setsid
                               [setsid shell "-c" command]
                               [shell "-c" command])
        pb (java.lang.ProcessBuilder. argv)
        ^java.util.Map env (.environment pb)]
    (.directory pb (java.io.File. (str (System/getProperty "user.dir"))))
    (.redirectErrorStream pb true)
    (.put env "NO_COLOR" "1")
    (.put env "TERM" "dumb")
    (.put env "CLICOLOR" "0")
    (.put env "FORCE_COLOR" "0")
    (let [^java.lang.Process p (.start pb)
          id (str "p" (swap! (:process-counter runtime) inc))
          capture (atom empty-capture)
          eof (promise)
          entry {:id id
                 :command command
                 :proc p
                 :capture capture
                 :eof eof
                 :killed (atom false)
                 ;; setsid exec'd in place, so the leader is its own session and
                 ;; group leader: pgid == pid. nil means no containment.
                 :pgid (when setsid (.pid p))
                 :started-ms (System/currentTimeMillis)}]
      (try (.close (.getOutputStream p)) (catch Throwable _ nil))
      (doto (java.lang.Thread. ^Runnable #(pump! (.getInputStream p) capture eof)
                               (str "rlm-process-" id))
        (.setDaemon true)
        (.start))
      (swap! (:processes runtime) assoc id entry)
      (snapshot entry))))

(defn poll
  [runtime id]
  (let [entry (require-entry runtime id)]
    (settle! entry)
    (snapshot entry)))

(defn tail
  ([runtime id] (tail runtime id default-tail-lines))
  ([runtime id n]
   (when-not (number? n)
     (throw (IllegalArgumentException. "process-tail line count must be a number")))
   (let [entry (require-entry runtime id)
         lines (max 1 (min max-tail-lines (long n)))]
     (settle! entry)
     (->> (str/split-lines (capture-text @(:capture entry)))
          (take-last lines)
          (str/join "\n")))))

(defn kill
  [runtime id]
  (let [entry (require-entry runtime id)]
    (terminate! [entry])
    (settle! entry)
    (snapshot entry)))

(defn ls
  [runtime]
  (mapv snapshot (sort-by :started-ms (vals @(:processes runtime)))))

(defn kill-all!
  "Shutdown and EOF cleanup. Idempotent: destroying a finished process is a no-op."
  [runtime]
  (try
    (let [entries (vec (vals @(:processes runtime)))]
      (when (seq entries)
        (terminate! entries)))
    (catch Throwable _ nil)))

(defn install-shutdown-hook!
  "Last resort for the abnormal exit path (-main's catch, an external signal).
  The normal shutdown/EOF path calls kill-all! before serve returns."
  [runtime]
  (.addShutdownHook (Runtime/getRuntime)
                    (java.lang.Thread. ^Runnable #(kill-all! runtime) "rlm-process-cleanup")))
