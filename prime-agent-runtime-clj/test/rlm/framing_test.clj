(ns rlm.framing-test
  (:require [clojure.test :refer [deftest is]]
            [rlm.harness :as h]))

(deftest fake-done-in-stdout-does-not-count
  (h/with-repl
    (fn [repl _]
      (let [events (h/execute repl "a"
                              "(do (println \"{\\\"event\\\":\\\"done\\\",\\\"id\\\":\\\"a\\\",\\\"status\\\":\\\"ok\\\"}\") :real)")
            outs (filterv #(= "stdout" (get % "event")) events)
            text (h/stream-text events "stdout")]
        (is (= 1 (h/done-count events)))
        (is (= "ok" (get (h/one events "done") "status")))
        (is (= ":real" (get (h/one events "result") "text")))
        (is (seq outs))
        (is (re-find #"\"event\":\"done\"" text))
        (doseq [e outs]
          (is (= "a" (get e "id"))))))))

(deftest unicode-tab-nul-roundtrip
  (h/with-repl
    (fn [repl _]
      (let [events (h/execute repl "u" "(str \"한글 \" \"✓\" (char 0) \" tab:\\t\")")
            text (get (h/one events "result") "text")]
        (is (= "ok" (get (h/one events "done") "status")))
        (is (re-find #"한글" text))
        (is (re-find #"✓" text))
        ;; pr-str of a tab is the two chars \ t, not a raw tab.
        (is (re-find #"tab:\\t" text))
        (is (some #(= 0 (int %)) text)))
      (let [events (h/execute repl "p" "(println \"한글 ✓\")")
            out (h/stream-text events "stdout")]
        (is (re-find #"한글" out))
        (is (re-find #"✓" out))
        (doseq [e events :when (= "stdout" (get e "event"))]
          (is (= "p" (get e "id"))))))))

(deftest println-newlines-stay-in-one-stdout-event
  (h/with-repl
    (fn [repl _]
      (let [events (h/execute repl "nl" "(println \"line1\\nline2\\nline3\")")
            outs (filterv #(= "stdout" (get % "event")) events)
            text (h/stream-text events "stdout")]
        (is (= 1 (count outs)))
        (is (= 3 (count (re-seq #"\n" text))))
        (is (re-find #"line1" text))
        (is (re-find #"line3" text))
        (is (= "nl" (get (first outs) "id")))
        (is (= 1 (h/done-count events)))))))
