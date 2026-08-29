(ns rlm.harness
  "Subprocess JSONL harness, shaped after prime-agent-runtime/test/test_repl.py ReplProcess."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [rlm.sut :as sut])
  (:import [java.lang ProcessBuilder ProcessBuilder$Redirect]
           [java.util.concurrent LinkedBlockingQueue TimeUnit]))

(def ^:private eof ::eof)

(defn start
  []
  (let [pb (doto (ProcessBuilder. ^java.util.List (sut/command))
             (.redirectError ProcessBuilder$Redirect/INHERIT))
        proc (.start pb)
        q (LinkedBlockingQueue.)
        stdin (io/writer (.getOutputStream proc) :encoding "UTF-8")]
    (future
      (try
        (with-open [r (io/reader (.getInputStream proc) :encoding "UTF-8")]
          (doseq [line (line-seq r)]
            (.put q line)))
        (finally
          (.put q eof))))
    {:proc proc :q q :stdin stdin :raw (atom [])}))

(defn read-event
  ([repl] (read-event repl 60))
  ([repl timeout-s]
   (let [line (.poll ^LinkedBlockingQueue (:q repl) timeout-s TimeUnit/SECONDS)]
     (cond
       (nil? line) (throw (ex-info "timed out waiting for a protocol event" {}))
       (= eof line) (throw (ex-info "runtime closed its protocol stream" {}))
       :else
       (do (swap! (:raw repl) conj line)
           (json/read-str line))))))

(defn send!
  [repl request]
  (doto ^java.io.Writer (:stdin repl)
    (.write (str (json/write-str request) "\n"))
    (.flush)))

(defn send-raw!
  [repl line]
  (doto ^java.io.Writer (:stdin repl)
    (.write (str line "\n"))
    (.flush)))

(defn until-done
  [repl rid]
  (loop [events []]
    (let [e (read-event repl)
          events (conj events e)]
      (if (and (= "done" (get e "event"))
               (= rid (get e "id")))
        events
        (recur events)))))

(defn execute
  [repl rid code]
  (send! repl {"type" "execute" "id" rid "code" code})
  (until-done repl rid))

(defn close!
  [repl]
  (try (.close ^java.io.Writer (:stdin repl)) (catch Exception _))
  (let [proc ^Process (:proc repl)]
    (when (.isAlive proc)
      (.destroyForcibly proc)
      (.waitFor proc 10 TimeUnit/SECONDS))))

(defn wait-event
  [repl kind]
  (loop []
    (let [e (read-event repl)]
      (if (= kind (get e "event"))
        e
        (recur)))))

(defn one
  [events kind]
  (first (filter #(= kind (get % "event")) events)))

(defn done-count
  [events]
  (count (filter #(= "done" (get % "event")) events)))

(defn stream-text
  [events stream]
  (apply str (keep #(when (= stream (get % "event")) (get % "text")) events)))

(defn with-repl
  "Start a runtime, consume ready, call f with [repl ready-event], then kill."
  [f]
  (let [repl (start)]
    (try
      (f repl (read-event repl))
      (finally
        (close! repl)))))
