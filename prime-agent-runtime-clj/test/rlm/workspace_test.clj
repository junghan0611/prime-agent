(ns rlm.workspace-test
  "Persistent workspace across cells — the Phase A claim."
  (:require [clojure.test :refer [deftest is]]
            [rlm.harness :as h]))

(deftest defn-survives-across-cells
  (h/with-repl
    (fn [repl _]
      (is (= "ok" (get (h/one (h/execute repl "d1" "(defn add [a b] (+ a b))") "done") "status")))
      (let [events (h/execute repl "d2" "(add 40 2)")]
        (is (= "42" (get (h/one events "result") "text")))
        (is (= 1 (h/done-count events)))))))

(deftest defmacro-survives-across-cells
  (h/with-repl
    (fn [repl _]
      (is (= "ok" (get (h/one (h/execute repl "m1" "(defmacro twice [x] `(+ ~x ~x))") "done") "status")))
      (let [events (h/execute repl "m2" "(twice 21)")]
        (is (= "42" (get (h/one events "result") "text")))))))

(deftest atom-accumulates-across-cells
  (h/with-repl
    (fn [repl _]
      (h/execute repl "a1" "(def a (atom []))")
      (h/execute repl "a2" "(swap! a conj 1)")
      (h/execute repl "a3" "(swap! a conj 2)")
      (let [events (h/execute repl "a4" "@a")]
        (is (= "[1 2]" (get (h/one events "result") "text")))))))

(deftest closure-survives-across-cells
  (h/with-repl
    (fn [repl _]
      (h/execute repl "c1" "(def f (let [x 40] (fn [y] (+ x y))))")
      (let [events (h/execute repl "c2" "(f 2)")]
        (is (= "42" (get (h/one events "result") "text")))))))

(deftest underscore-holds-handle-for-next-cell
  (h/with-repl
    (fn [repl _]
      (h/send! repl {"type" "execute" "id" "u1" "code" "(rlm \"child task\")"})
      (let [req (h/wait-event repl "host_request")]
        (h/send! repl {"type" "host_reply" "id" (get req "id")
                       "data" {"status" "ok"
                               "rlm_child_id" "c1"
                               "name" "child"
                               "session_dir" "/tmp/x"
                               "model" "test"}})
        (let [events (h/until-done repl "u1")]
          (is (= "ok" (get (h/one events "done") "status")))
          (is (= 1 (h/done-count events)))))
      (let [events (h/execute repl "u2" "(keys _)")]
        (is (re-find #":rlm-child-id" (str (get (h/one events "result") "text"))))
        (is (re-find #":name" (str (get (h/one events "result") "text"))))
        (is (= "ok" (get (h/one events "done") "status")))))))
