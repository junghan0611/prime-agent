(ns rlm.sut
  "Native SUT only. The test runner is a Clojure CLI process; it spawns
  target/rlm-repl. There is no JVM protocol runtime."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(defn mode
  []
  (let [m (System/getProperty "rlm.sut")]
    (when-not (= "native" m)
      (throw (ex-info
              "rlm.sut must be native (clojure -M:test or :test-native). no JVM SUT."
              {:got m})))
    m))

(defn native-file
  []
  (io/file (or (not-empty (System/getenv "RLM_REPL_BIN")) "target/rlm-repl")))

(defn- src-clj-files
  []
  (->> (file-seq (io/file "src"))
       (filter #(and (.isFile ^java.io.File %)
                     (re-find #"\.(clj|cljc)$" (.getName ^java.io.File %))))))

(defn assert-native-ready!
  []
  (let [bin (native-file)]
    (when-not (.isFile bin)
      (throw (ex-info (str "native SUT missing: " (.getAbsolutePath bin)
                           "\nrun native-image/build.sh first")
                      {:type :rlm/sut-missing})))
    (let [bin-mtime (.lastModified bin)
          stale (filterv #(> (.lastModified ^java.io.File %) bin-mtime)
                         (src-clj-files))]
      (when (seq stale)
        (throw (ex-info (str "native SUT is stale vs src: "
                             (str/join ", " (map #(.getPath ^java.io.File %) stale))
                             "\nrun native-image/build.sh first")
                        {:type :rlm/sut-stale}))))
    bin))

(defn- built-clock
  [^java.io.File bin]
  (let [fmt (doto (java.text.SimpleDateFormat. "HH:mm:ss")
              (.setTimeZone (java.util.TimeZone/getDefault)))]
    (.format fmt (java.util.Date. (.lastModified bin)))))

(defn banner
  []
  (mode)
  (let [bin (assert-native-ready!)]
    (str "SUT: native " (.getAbsolutePath bin) " (built " (built-clock bin) ")")))

(defn command
  []
  (mode)
  [(.getAbsolutePath (assert-native-ready!))])
