(ns tensor.ns-to-reload
  (:require [riemann.streams :refer [sdo]]
            [tensor.core :refer :all]))

(def keyword-to-replace :original)

(defmacro defmacro-and-fn
  "Create identical streams with def-stream and def-stream-fn
  env-keys param are the keywords to appear in the environment"
  [name env-keys]
  `(do
     (def-stream ~(symbol name)
       [{:keys ~env-keys :as env#} & children#]
       (sdo
        prn
        #(swap! tensor.test-harness/test-atom conj ~@env-keys %)))
     (def-stream-fn ~(keyword (str *ns*) (str name "-fn"))
       (fn [{:keys ~env-keys :as env#} & children#]
         (sdo
          prn
          #(swap! tensor.test-harness/test-atom conj ~@env-keys %))))))

(defmacro-and-fn "test-no-env" [])
(defmacro-and-fn "test-env" [event1 event2])
