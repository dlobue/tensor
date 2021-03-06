(ns tensor.test
  (:require [tensor.core :refer :all]
            [clojure.data :as data]
            [clojure.test :refer [join-fixtures]]
            [medley.core :refer [deref-reset!]]
            riemann.logging
            [riemann.test :refer [with-test-env] :as test]
            [riemann.time.controlled :as time.controlled]))


(defn tap-stream
  [name child]
  ;; TODO: ensure that *results* is bound and :type metadata is :tensor
  (fn stream [event]
    ;; Record event
    (swap! test/*results* conj (list name event))
    ;; Forward downstream
    (child event)))


;; Fixtures

(defn setup-test-env-fixture [f]
  (with-test-env
    (with-reloadable-streams
      (f))))

(defn load-streams-fixture-fn [env stream-names]
  (fn [f]
    (binding [test/*streams* (coerce-list (load-streams-fn env stream-names))]
      (f))))

(defmacro load-streams-fixture [env & stream-names]
  (let [stream-names (if (map? env) stream-names (cons env stream-names))
        env (if (map? env) env {})]
    `(load-streams-fixture-fn ~env '~stream-names)))

(defn controlled-time-fixture [f]
  (time.controlled/with-controlled-time!
    (time.controlled/reset-time!)
    (f)))

(defn riemann-results-fixture [f]
  (binding [test/*results* (test/fresh-results @test/*taps*)]
    ;; TODO: set metadata on atom to denote it is riemann style/type
    ;; TODO: add validator function to ensure results isn't reset to
    ;; tensor style
    (f)))

(defn tensor-results-fixture [f]
  (binding [test/*results* (atom [] :meta {:type :tensor})]
    (with-redefs [riemann.test/tap-stream tap-stream]
      ;; TODO: add validator function to ensure results isn't reset to
      ;; riemann style
      (f))))

(defmacro tensor-fixtures [& args]
  `(join-fixtures [setup-test-env-fixture
                   tensor-results-fixture
                   controlled-time-fixture
                   (load-streams-fixture ~@args)]))

;; Result helpers

(defn match-map
  "Recursively check that all items in `ref-val` equals to
  the ones in `actual-val`.

  Keys existing in `actual-val` but missing in `ref-val` will be ignored.

  Ex: `(match-map {:foo :bar} {:foo :bar :baz :biz})` would be `true`, even
  though `:baz` only exists in `actual-val`."
  [ref-val actual-val]
  (if (map? ref-val)
    (every?
      true?
      (for [[k v] (seq ref-val)]
        (match-map v (k actual-val))))
    (= ref-val actual-val)))

(defn- match-actual
  "Generate a readable reference of a map that did not match.

  Create a readable representation of what the actual match looked like
  compared to the reference object."
  [ref-val actual-val]
  (if (and actual-val (map? ref-val))
    (apply
     hash-map
     (flatten
      (for [[k v] (seq ref-val)]
        [k (match-actual v (k actual-val))])))
    actual-val))


;; TODO: change function name!
(defn check-taps-fn
  "Check that all taps that is specified in `call-defs` has been called
  in the order specified.

  This is a flexible assertion method that uses the specified calls
  (`call-defs`) as template to the actual calls being made, and will report
  when-ever there is a mis-match through `clojure.test/report`

  The structure of the external call is matched using
  [`match-map`](#match-map).

  Resets `*external-reports*` after each call."
  [opts & call-defs]
  {:pre [(even? (count call-defs))]}
  ;; TODO: ensure test/*results* type is tensor
  (let [results (->> (deref-reset! test/*results* [])
                     (remove #((set (:ignore-taps opts)) (first %))))
        calls (partition 2 call-defs)
        indexed-reports (map-indexed vector results)]

    (let [call-count (count calls)
          results-count (count results)]
      (clojure.test/report
       {:type (if (= call-count results-count) :pass :fail)
        :message (str "Amount of external calls should match")
        :expected call-count
        :actual results-count}))

    (dorun
     (map-indexed
      (fn [idx [[actual-tap-name actual-event] [expected-tap-name expected-event]]]
        (let [external-matches? (= expected-tap-name actual-tap-name)
              value-matches? (match-map expected-event actual-event)
              all-matches? (and external-matches? value-matches?)
              report-message (if (nil? expected-tap-name)
                               (str "Did not expect external call #" (inc idx))
                               (str "Expected external call #" (inc idx)))
              report-type (if all-matches?
                            :pass
                            :fail)
              reduced-actual (match-actual expected-event actual-event)
              value-actual (if (or (nil? expected-event)
                                   (empty? reduced-actual))
                             actual-event reduced-actual)]

          (clojure.test/report
           {:type report-type
            :message report-message
            :expected (list expected-tap-name expected-event)
            :actual (list actual-tap-name value-actual)
            :diffs [[(list actual-tap-name value-actual)
                     (take 2
                           (data/diff (list expected-tap-name expected-event)
                                      (list actual-tap-name value-actual)))]]})))
      (partition 2
                 (interleave results (lazy-cat calls (repeat [nil nil]))))))))

(defn check-taps [& args]
  (if (empty? args)
    (check-taps-fn nil)

    (let [opt-keys #{:event-base :sole-tap-key :ignore-taps}
          [opts args] (if (some (first args) opt-keys)
                        [(first args) (rest args)]
                        [{} args])
          event-base (or (:event-base opts) {})
          sole-tap-key (:sole-tap-key opts)
          bplate-gen (fn bpgen
                       ([[name event]] (bpgen name event))
                       ([name event]
                          [name (merge event-base event)]))]
      (when (and (nil? sole-tap-key)
                 (odd? (count args)))
        ;; TODO: make sure the args alternates keyword map
        ;; TODO: throw exception or error or something!
        nil)
      (apply check-taps-fn
             opts
             (if (not (nil? sole-tap-key))
               (mapcat bplate-gen  (repeat sole-tap-key) args)
               (mapcat bplate-gen (partition 2 args)))))))


;; Inject helpers

(defn send-events!
  "Takes a sequence of streams, initiates controlled time and resets the
  scheduler, applies a sequence of events to those streams, and returns a map
  of tap names to the events each tap received. Absolutely NOT threadsafe;
  riemann.time.controlled is global. Streams may be omitted, in which case
  inject! applies events to the *streams* dynamic var."
  ([events]
   (send-events! nil events))
  ([opts events]
   (send-events! test/*streams* opts events))
  ([streams opts events]
     (let [event-defaults {:host :default-host :service :default-service}
           ;; TODO: re-enable next when given a certain option
           ;; event-update (comp riemann.common/event
           ;;                    (partial merge event-defaults (:event-base opts)))
           event-update (partial merge event-defaults (:event-base opts))
           events (map event-update events)]

       ;; Apply events
       (doseq [e events]
         (when-let [t (:time e)]
           (time.controlled/advance! t))

         (doseq [stream streams]
           (stream e)))

       ;; If :end-time specified, run the current tasks up until specified time.
       (when-let [end-time (:end-time opts)]
         (time.controlled/advance! end-time)))))
