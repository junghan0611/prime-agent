(ns rlm.harness
  "Subprocess JSONL harness, shaped after prime-agent-runtime/test/test_repl.py ReplProcess."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [rlm.sut :as sut])
  (:import [java.lang ProcessBuilder ProcessBuilder$Redirect]
           [java.util.concurrent LinkedBlockingQueue TimeUnit]))

(def ^:private eof ::eof)

(defn start
  ([] (start {}))
  ([{:keys [env]}]
   (let [pb (doto (ProcessBuilder. ^java.util.List (sut/command))
              (.redirectError ProcessBuilder$Redirect/INHERIT))
         _ (when (seq env)
             (let [^java.util.Map e (.environment pb)]
               (doseq [[k v] env] (.put e (str k) (str v)))))
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
     {:proc proc :q q :stdin stdin :raw (atom [])})))

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

(defn drain!
  "Read every remaining protocol line until the runtime closes its stream,
  recording each one on :raw. Returns the events that parsed; a line that does
  not parse is still recorded, because naming it is first-torn-line's job."
  ([repl] (drain! repl 30))
  ([repl timeout-s]
   (loop [events []]
     (let [line (.poll ^LinkedBlockingQueue (:q repl) timeout-s TimeUnit/SECONDS)]
       (cond
         (nil? line) (throw (ex-info "timed out draining the protocol stream" {}))
         (= eof line) events
         :else
         (do (swap! (:raw repl) conj line)
             (recur (if-let [e (try (json/read-str line) (catch Exception _ nil))]
                      (conj events e)
                      events))))))))

(defn raw-lines
  "Every protocol line read so far, exactly as the runtime wrote it."
  [repl]
  @(:raw repl))

(defn first-torn-line
  "[index line] of the first protocol line that is not one complete JSON
  object, or nil when the stream is whole.

  Reads the recorded lines instead of the parsed events on purpose. read-event
  conj's a line onto :raw and only THEN parses it, so a torn frame kills the
  read with a vague parse exception before any assertion can name it. The
  framing check has to run after the fact, against the raw record, so that a
  regression reports which line stopped being JSON."
  [repl]
  (->> (raw-lines repl)
       (map-indexed vector)
       (remove (fn [[_ line]]
                 (try (map? (json/read-str line)) (catch Exception _ false))))
       first))

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
    ;; Let EOF cleanup run before reaching for the kill: SIGKILL skips the
    ;; runtime's shutdown path and would orphan whatever it still owns.
    (when-not (.waitFor proc 10 TimeUnit/SECONDS)
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

(defn error-shape?
  "Keys exist with oracle types. Do not pin ename/evalue values — OPEN."
  [e]
  (and (string? (get e "ename"))
       (string? (get e "evalue"))
       (vector? (get e "traceback"))))

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
