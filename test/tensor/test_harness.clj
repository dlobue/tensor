(ns tensor.test-harness
  (:require [clojure.test :refer :all]
            [tensor.core :refer :all]
            [clojure.string :refer [split]]))

(def test-atom (atom []))
(def load-counter-atom (atom nil))
(def original-load clojure.core/load)

(defn wrapped-load [n]
  (swap! load-counter-atom inc)
  (original-load n))

(defn setup-stream [f]
  (reset! load-counter-atom 0)
  (with-redefs [clojure.core/load wrapped-load]
    (binding [*streams* (atom {})]
      (f))))

(use-fixtures :once setup-stream)

(defmacro test-with-env
  "Excercise the stream using both load-streams and load-streams-fn"
  [stream]
  (let [env-vec ['event1 :event1 'event2 :event2]
        env-map (apply hash-map (map keyword env-vec))]
    `(do
       (reset! test-atom [])
       (let [real-stream#
             (let ~env-vec
               (load-streams ~stream))
             ;; pass the stream an event and make sure the prn substream
             ;;  prints it out
             s# (with-out-str (real-stream# :event3))
             ;; now run the same test but load the stream with load-streams-fun
             _# (reset! test-atom [])
             real-stream-fn#
             (load-streams-fn ~env-map '~stream)
             s-fn# (with-out-str (real-stream-fn# :event3))]
         (is (= s# ":event3\n"))
         (is (= s-fn# ":event3\n"))))))

(deftest test-all
  (testing "that the environment is not used"
    (test-with-env tensor.ns-to-reload/test-no-env)
    (is (= @test-atom [:event3]))
    (test-with-env tensor.ns-to-reload/test-no-env-fn)
    (is (= @test-atom [:event3])))

  (testing "that the environment is used"
    (test-with-env tensor.ns-to-reload/test-env)
    (is (= @test-atom [:event1 :event2 :event3]))
    (test-with-env tensor.ns-to-reload/test-env-fn)
    (is (= @test-atom [:event1 :event2 :event3])))

  (testing "that initial reload happened"
    (is (= 1 @load-counter-atom)))

  ;;Reset the stream to force load
  (reset! *streams* {})

  (testing "that reload happens again"
    ;; force the reload
    (test-with-env tensor.ns-to-reload/test-env)
    (is (= 2 @load-counter-atom))))
