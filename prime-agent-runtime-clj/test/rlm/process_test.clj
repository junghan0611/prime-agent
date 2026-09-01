(ns rlm.process-test
  "H4 process lifecycle. The workspace holds ids and data maps; the live
  process stays host-side and must not outlive the runtime."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rlm.harness :as h])
  (:import [java.util.concurrent TimeUnit]))

(defn- result-text
  [events]
  (get (h/one events "result") "text"))

(defn- eval-edn
  "Read the cell's result back as data. Snapshots are plain edn by contract."
  [repl id code]
  (let [events (h/execute repl id code)]
    (is (nil? (h/one events "error")) code)
    (edn/read-string (result-text events))))

(defn- wait-exit
  "Poll across cells: SCI has no sleep, so waiting is the caller's job."
  [repl form]
  (loop [i 0]
    (let [snap (eval-edn repl (str "wait-" i) form)]
      (if (or (= :exited (:status snap)) (>= i 200))
        snap
        (do (Thread/sleep 50) (recur (inc i)))))))

(defn- handle-of
  ^java.lang.ProcessHandle [pid]
  (.orElse (java.lang.ProcessHandle/of (long pid)) nil))

(defn- descendant-pids
  [pid]
  (if-let [ph (handle-of pid)]
    (mapv (fn [^java.lang.ProcessHandle d] (.pid d)) (.toList (.descendants ph)))
    []))

(defn- gone?
  "Bounded wait: a killed child can sit as a zombie until its parent reaps it."
  [pid]
  (let [deadline (+ (System/currentTimeMillis) 5000)]
    (loop []
      (let [ph (handle-of pid)]
        (cond
          (or (nil? ph) (not (.isAlive ph))) true
          (>= (System/currentTimeMillis) deadline) false
          :else (do (Thread/sleep 25) (recur)))))))

(defn- descendants-of
  "Wait for the shell to fork: a start that just returned may not have spawned
  its children yet."
  [pid expected]
  (let [deadline (+ (System/currentTimeMillis) 5000)]
    (loop []
      (let [ds (descendant-pids pid)]
        (if (or (>= (count ds) expected) (>= (System/currentTimeMillis) deadline))
          ds
          (do (Thread/sleep 25) (recur)))))))

(defn- alive?
  [pid]
  (let [ph (handle-of pid)]
    (boolean (and ph (.isAlive ph)))))

(defn- pgid-of
  "No ProcessHandle field carries the group, so read it the way the shell does."
  [pid]
  (let [p (.start (java.lang.ProcessBuilder.
                   ^java.util.List ["ps" "-o" "pgid=" "-p" (str pid)]))
        out (slurp (.getInputStream p))]
    (.waitFor p 10 TimeUnit/SECONDS)
    (Long/parseLong (str/trim out))))

(defn- await-runtime-exit
  [repl]
  (.waitFor ^java.lang.Process (:proc repl) 15 TimeUnit/SECONDS))

(defn- which
  ^String [^String name]
  (first (keep (fn [^String dir]
                 (let [f (java.io.File. dir name)]
                   (when (and (.isFile f) (.canExecute f)) (.getAbsolutePath f))))
               (str/split (or (System/getenv "PATH") "") #":"))))

(defn- empty-path-dir
  "A PATH with no setsid (and no bash, so the shell falls back to /bin/sh)."
  ^String []
  (let [dir (java.io.File. (str (System/getProperty "java.io.tmpdir"))
                           (str "rlm-nopath-" (System/nanoTime)))]
    (.mkdirs dir)
    (.deleteOnExit dir)
    (.getAbsolutePath dir)))

(deftest start-poll-tail-follow-one-command
  (h/with-repl
    (fn [repl _]
      (eval-edn repl "s1" "(do (def h (process-start \"echo hello-h4; exit 3\")) :started)")
      (let [snap (wait-exit repl "(process-poll h)")]
        (is (= :exited (:status snap)))
        (is (= 3 (:exit-code snap)))
        (is (false? (:killed snap)))
        (is (= "echo hello-h4; exit 3" (:command snap)))
        (is (false? (:output-truncated snap))))
      (is (= "hello-h4" (eval-edn repl "s2" "(process-tail h)")))
      (testing "the id alone is enough; the handle map is a convenience"
        (is (= "hello-h4" (eval-edn repl "s3" "(process-tail \"p1\")")))))))

(deftest sci-never-sees-a-live-process
  (h/with-repl
    (fn [repl _]
      (let [text (result-text (h/execute repl "n1" "(pr-str (process-start \"true\"))"))]
        (is (not (re-find #"#object" text)))
        (is (not (re-find #"java\.lang\.Process" text))))
      (let [snap (eval-edn repl "n2" "(process-poll \"p1\")")]
        (is (string? (:process-id snap)))
        (is (number? (:pid snap)))
        (is (keyword? (:status snap)))
        (is (contains? #{:running :exited} (:status snap))))
      (testing "no interop path back to the object"
        (is (some? (h/one (h/execute repl "n3" "(.destroy (process-poll \"p1\"))") "error")))
        (is (some? (h/one (h/execute repl "n4" "(.getInputStream (process-poll \"p1\"))") "error"))))
      (let [events (h/execute repl "n5" "(+ 40 2)")]
        (is (= "42" (result-text events)))))))

(deftest captured-output-is-bounded
  (h/with-repl
    (fn [repl _]
      ;; ~2.4 MB, an order of magnitude past the 256 KiB head+tail capture.
      (eval-edn repl "b1"
                (str "(do (def h (process-start "
                     "\"yes 0123456789012345678901234567890123456789 | head -n 60000\")) :started)"))
      (let [snap (wait-exit repl "(process-poll h)")]
        (is (= :exited (:status snap)))
        (is (true? (:output-truncated snap)))
        (is (> (long (:output-bytes snap)) 2000000)))
      (testing "tail is line-bounded"
        (is (= 50 (count (str/split-lines (eval-edn repl "b2" "(process-tail h)")))))
        (is (= 5 (count (str/split-lines (eval-edn repl "b3" "(process-tail h 5)"))))))
      (testing "the whole capture stays bounded even when the model asks for everything"
        (let [n (eval-edn repl "b4" "(count (process-tail h 1000000))")]
          (is (< (long n) 400000))))
      (let [events (h/execute repl "b5" "(+ 1 1)")]
        (is (= "2" (result-text events)))))))

(deftest child-output-cannot-forge-protocol-frames
  (h/with-repl
    (fn [repl _]
      ;; The forged id is one no cell ever uses, so a leaked child line is
      ;; distinguishable from the runtime's own done frame.
      (eval-edn repl "f1"
                (str "(do (def h (process-start "
                     "\"echo '{\\\"event\\\":\\\"done\\\",\\\"id\\\":\\\"ghost\\\",\\\"status\\\":\\\"ok\\\"}'\")) :started)"))
      (wait-exit repl "(process-poll h)")
      (let [text (eval-edn repl "f2" "(process-tail h)")]
        (is (re-find #"\"event\":\"done\"" text))
        (is (re-find #"ghost" text)))
      (let [events (h/execute repl "f3" "(+ 40 2)")]
        (is (= "42" (result-text events)))
        (is (= 1 (h/done-count events))))
      (testing "the forged line never reached the protocol stream"
        (let [forged "{\"event\":\"done\",\"id\":\"ghost\",\"status\":\"ok\"}"
              frames (map #(try (json/read-str %) (catch Exception _ {})) @(:raw repl))]
          (is (not-any? #(= forged (str/trim %)) @(:raw repl)))
          (is (not-any? #(= "ghost" (get % "id")) frames))
          (is (= 1 (count (filter #(and (= "done" (get % "event")) (= "f1" (get % "id")))
                                  frames))))))
      (testing "a chatty child does not break framing"
        (eval-edn repl "f4" "(do (def n (process-start \"seq 1 5000\")) :started)")
        (wait-exit repl "(process-poll n)")
        (is (= "42" (result-text (h/execute repl "f5" "(+ 40 2)"))))))))

(deftest kill-terminates-the-process-tree
  (h/with-repl
    (fn [repl _]
      (eval-edn repl "k1" "(do (def h (process-start \"sleep 45 & sleep 46 & wait\")) :started)")
      (let [snap (eval-edn repl "k2" "(process-poll h)")
            pid (long (:pid snap))
            kids (descendants-of pid 2)]
        (is (= :running (:status snap)))
        (is (= 2 (count kids)) "the shell forks two sleepers")
        (let [after (eval-edn repl "k3" "(process-kill h)")]
          (is (= :exited (:status after)))
          (is (true? (:killed after)))
          (is (some? (:exit-code after))))
        (is (gone? pid) "the leader must be dead")
        (doseq [kid kids]
          (is (gone? kid) (str "descendant " kid " must not survive the kill"))))
      (let [events (h/execute repl "k4" "(+ 40 2)")]
        (is (= "42" (result-text events)))))))

(deftest shutdown-kills-live-processes
  (let [repl (h/start)]
    (try
      (h/read-event repl)
      (let [snap (eval-edn repl "sd1" "(process-start \"sleep 47\")")
            pid (long (:pid snap))]
        (is (= :running (:status snap)))
        (h/send! repl {"type" "shutdown" "id" "sd2"})
        (is (true? (await-runtime-exit repl)) "runtime exits on shutdown")
        (is (gone? pid) "shutdown must not leave the child behind"))
      (finally (h/close! repl)))))

(deftest eof-kills-live-processes
  (let [repl (h/start)]
    (try
      (h/read-event repl)
      (let [snap (eval-edn repl "e1" "(process-start \"sleep 48 & sleep 49 & wait\")")
            pid (long (:pid snap))
            kids (descendants-of pid 2)]
        (is (= 2 (count kids)))
        (.close ^java.io.Writer (:stdin repl))
        (is (true? (await-runtime-exit repl)) "runtime exits on stdin EOF")
        (is (gone? pid))
        (doseq [kid kids]
          (is (gone? kid) (str "descendant " kid " must not survive EOF"))))
      (finally (h/close! repl)))))

(deftest setsid-makes-the-leader-its-own-process-group
  ;; The containment claim rests on setsid exec'ing in place, so pin it: if it
  ;; ever forked instead, :pid would not be the group we signal.
  (h/with-repl
    (fn [repl _]
      (let [snap (eval-edn repl "pg1" "(process-start \"sleep 60 & wait\")")
            pid (long (:pid snap))]
        (is (true? (:contained snap)))
        (is (= pid (pgid-of pid)) "setsid exec'd in place: pgid == leader pid")
        (let [after (eval-edn repl "pg2" "(process-kill \"p1\")")]
          (is (= :exited (:status after))))
        (is (gone? pid))))))

(deftest group-kill-reclaims-a-child-that-outlived-its-leader
  ;; `cmd & exit 0`: the leader exits at once and the child reparents out of
  ;; .descendants(). Only the process group still holds it.
  (h/with-repl
    (fn [repl _]
      (eval-edn repl "o1" "(do (def h (process-start \"sleep 301 & echo $!; exit 0\")) :started)")
      (let [snap (wait-exit repl "(process-poll h)")
            orphan (Long/parseLong (str/trim (eval-edn repl "o2" "(process-tail h)")))]
        (is (= :exited (:status snap)))
        (is (= 0 (:exit-code snap)))
        (is (true? (:contained snap)))
        (is (empty? (descendant-pids (long (:pid snap))))
            "the leader is gone, so the descendants sweep sees nothing")
        (is (alive? orphan) "the child outlives the leader — that is the whole case")
        (eval-edn repl "o3" "(process-kill h)")
        (is (gone? orphan) "group kill must reclaim it")))))

(deftest shutdown-reclaims-a-child-that-outlived-its-leader
  (let [repl (h/start)]
    (try
      (h/read-event repl)
      (eval-edn repl "os1" "(do (def h (process-start \"sleep 305 & echo $!; exit 0\")) :started)")
      (let [snap (wait-exit repl "(process-poll h)")
            orphan (Long/parseLong (str/trim (eval-edn repl "os2" "(process-tail h)")))]
        (is (true? (:contained snap)))
        (is (alive? orphan))
        (h/send! repl {"type" "shutdown" "id" "os3"})
        (is (true? (await-runtime-exit repl)))
        (is (gone? orphan) "shutdown must reclaim the whole group, not just the tree"))
      (finally (h/close! repl)))))

(deftest without-setsid-the-runtime-degrades-not-breaks
  ;; A host with no setsid keeps working; it just loses group containment and
  ;; falls back to the descendants sweep. :contained says so out loud.
  (let [repl (h/start {:env {"PATH" (empty-path-dir)}})]
    (try
      (h/read-event repl)
      (eval-edn repl "ns0" "(do (process-start \"echo no-setsid\") :started)")
      (let [snap (wait-exit repl "(process-poll \"p1\")")]
        (is (= :exited (:status snap)))
        (is (false? (:contained snap)))
        (is (= "no-setsid" (eval-edn repl "ns1" "(process-tail \"p1\")"))))
      ;; PATH is empty for this runtime, so the child cannot look up `sleep`.
      (let [sleep-bin (which "sleep")
            snap (eval-edn repl "ns2"
                           (str "(process-start \"" sleep-bin " 62 & " sleep-bin " 63 & wait\")"))
            pid (long (:pid snap))
            kids (descendants-of pid 2)]
        (is (false? (:contained snap)))
        (is (= 2 (count kids)))
        (eval-edn repl "ns3" "(process-kill \"p2\")")
        (is (gone? pid))
        (doseq [kid kids]
          (is (gone? kid) "the descendants sweep still reaps a live tree")))
      (finally (h/close! repl)))))

(deftest signalled-runtime-still-cleans-up
  ;; Neither shutdown nor EOF: the host goes away and the runtime takes a
  ;; SIGTERM. The shutdown hook is what covers this path.
  (let [repl (h/start)]
    (try
      (h/read-event repl)
      (let [snap (eval-edn repl "sig1" "(process-start \"sleep 50\")")
            pid (long (:pid snap))]
        (is (= :running (:status snap)))
        (.destroy ^java.lang.Process (:proc repl))
        (is (true? (await-runtime-exit repl)))
        (is (gone? pid) "SIGTERM to the runtime must not orphan the child"))
      (finally (h/close! repl)))))

(deftest live-processes-are-capped
  (h/with-repl
    (fn [repl _]
      (let [started (eval-edn repl "c1"
                              "(mapv (fn [_] (:process-id (process-start \"sleep 51\"))) (range 16))")]
        (is (= 16 (count started))))
      (let [events (h/execute repl "c2" "(process-start \"sleep 52\")")]
        (is (some? (h/one events "error")) "the 17th live process is refused")
        (is (re-find #"live processes" (get (h/one events "error") "evalue"))))
      (testing "the cap is on live processes, not on the registry"
        (eval-edn repl "c3" "(mapv (fn [h] (:status (process-kill h))) (process-list))")
        (let [snap (eval-edn repl "c4" "(process-start \"true\")")]
          (is (= "p17" (:process-id snap))))))))

(deftest registry-is-id-addressed
  (h/with-repl
    (fn [repl _]
      (eval-edn repl "r1" "(do (process-start \"true\") (process-start \"true\") :ok)")
      (is (= ["p1" "p2"] (eval-edn repl "r2" "(mapv :process-id (process-list))")))
      (testing "argument shapes that must fail the cell, not the runtime"
        (doseq [[id code] [["r3" "(process-poll \"nope\")"]
                           ["r4" "(process-start 42)"]
                           ["r5" "(process-start \"\")"]
                           ["r6" "(process-kill {:no :id})"]
                           ["r7" "(process-tail \"p1\" \"five\")"]]]
          (let [events (h/execute repl id code)]
            (is (some? (h/one events "error")) code)
            (is (h/error-shape? (h/one events "error")) code)
            (is (= "error" (get (h/one events "done") "status")) code))))
      (let [events (h/execute repl "r8" "(+ 40 2)")]
        (is (= "42" (result-text events)))))))

;; H4.1 — a relative PRIME_AGENT_BASH_SHELL override is refused. rlm.process/shell-path
;; documents itself as "the oracle's _shell() order", and the oracle pins the same
;; contract (test_bash.py::BashTest::test_relative_bash_shell_override_rejected,
;; ValueError on PRIME_AGENT_BASH_SHELL="bash"). Implemented on both arms, tested on
;; one until now.
;;
;; kill mode: mode-1 (env). This row needs no source edit to break -- start the
;; runtime with an absolute override that is not executable, or drop the leading
;; "/" check, and the fault rides the existing :env seam. Cheap for Pass C.
(deftest a-relative-bash-shell-override-is-refused
  (let [repl (h/start {:env {"PRIME_AGENT_BASH_SHELL" "bash"}})]
    (try
      (h/read-event repl)
      (let [events (h/execute repl "rs1" "(process-start \"echo should-not-run\")")
            err (h/one events "error")]
        (is (some? err) "a relative shell override must fail the cell, not silently pick a shell")
        (is (h/error-shape? err))
        (is (re-find #"PRIME_AGENT_BASH_SHELL must be an absolute path"
                     (str (get err "evalue"))))
        (is (= "error" (get (h/one events "done") "status"))))
      ;; The refusal is per-call, not a poisoned runtime: the workspace still serves.
      (let [events (h/execute repl "rs2" "(+ 3 4)")]
        (is (= "7" (get (h/one events "result") "text")))
        (is (= "ok" (get (h/one events "done") "status"))))
      (finally (h/close! repl)))))

(deftest an-absolute-bash-shell-override-is-honoured
  ;; The other half of the same seam: absoluteness is the gate, not the name.
  ;; Without this, "refuses relative" could pass by refusing every override.
  (let [repl (h/start {:env {"PRIME_AGENT_BASH_SHELL" (which "sh")}})]
    (try
      (h/read-event repl)
      (eval-edn repl "as1" "(do (process-start \"echo absolute-ok\") :started)")
      (let [snap (wait-exit repl "(process-poll \"p1\")")]
        (is (= :exited (:status snap)))
        (is (zero? (:exit-code snap))))
      (is (= "absolute-ok" (eval-edn repl "as2" "(process-tail \"p1\")")))
      (finally (h/close! repl)))))
