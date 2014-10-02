(ns tensor.core
  (:require [clojure.string :as string]
            [riemann.streams :refer [sdo]]))

(defn pkg-to-path
  [pkg]
  (as-> pkg p
        (string/replace p #"-" "_")
        (string/replace p #"\." "/")
        (str "/" p)))

(defn dir-loader [pkg]
  (load (pkg-to-path pkg)))

;(def ^:dynamic *streams* (atom {}))
(declare *streams*)

(defn get-stream [streamname]
  (let [streamname (keyword streamname)]
    (if-let [stream (streamname @*streams*)]
      stream
      (do
        (dir-loader (namespace streamname))
        (streamname @*streams*)))))

(defn load-stream-fn [streamname env & body]
  (let [stream (get-stream streamname)]
    (apply stream env body)))

(defn load-streams-fn [env & streamnames]
  (apply sdo (map #(apply load-stream-fn (keyword %) env []) streamnames)))

(defmacro load-streams [& streamnames]
  (let [symbols (keys &env)
        env (zipmap (map keyword symbols) symbols)]
    `(load-streams-fn ~env ~@(map keyword streamnames))))

(defn def-stream-fn [streamname body]
  (swap! *streams* assoc (keyword (str *ns*) streamname) body))

(defmacro def-stream [streamname params body]
  `(def-stream-fn ~(name streamname)
     (fn ~params ~body)))
