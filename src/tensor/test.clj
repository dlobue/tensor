(ns tensor.test
  (:require [tensor.core :refer :all]
            riemann.logging
            [riemann.test :refer [with-test-env] :as test]
            [riemann.time.controlled :as time.controlled]))


(defn setup-test-env-fixture [f]
  (riemann.logging/init)
  (with-test-env
    (binding [*streams* (atom {})]
      (f))))

(defn load-streams-fixture-fn [env & stream-names]
  (fn [f]
    (reset! *streams* {})
    (binding [test/*streams* [(apply load-streams-fn env stream-names)]]
      (f))))

(defmacro load-streams-fixture [env & stream-names]
  (let [stream-names (if (map? env) stream-names (cons env stream-names))
        stream-names (map keyword stream-names)
        env (if (map? env) env {})]
    `(load-streams-fixture-fn ~env ~@stream-names)))

(defn per-test-fixture [f]
  (binding [test/*results* (test/fresh-results @test/*taps*)]
    (time.controlled/with-controlled-time!
      (time.controlled/reset-time!)
      (f))))
