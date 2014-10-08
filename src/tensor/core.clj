(ns tensor.core
  (:require [clojure.string :as string]
            [clojure.tools.logging :refer :all]
            [riemann.streams :refer [sdo]]))

(defn pkg-to-path
  [pkg]
  (as-> pkg p
        (string/replace p #"-" "_")
        (string/replace p #"\." "/")
        (str "/" p)))

(defn dir-loader [pkg]
  (debug "Loading package " pkg)
  (load (pkg-to-path pkg)))

(declare ^:dynamic *streams*)

(defn get-stream [streamname]
  (let [streamname (keyword streamname)]
    (debug "Getting stream " streamname)
    (if-let [stream (streamname @*streams*)]
      stream
      (do
        (debug "Stream " streamname " yet not in registry")
        (dir-loader (namespace streamname))
        (streamname @*streams*)))))

(defn load-stream-fn [streamname env & body]
  (let [stream (get-stream streamname)]
    (debug "Loading stream " streamname)
    (trace "Loading stream " streamname "with env: " env)
    (apply stream env body)))

(defn load-streams-fn [env & streamnames]
  (debug "Loading streams " streamnames)
  (apply sdo (doall (map #(apply load-stream-fn (keyword %) env []) streamnames))))

(defmacro load-streams [& streamnames]
  (let [symbols (keys &env)
        env (zipmap (map keyword symbols) symbols)]
    `(load-streams-fn ~env ~@(map keyword streamnames))))

(defmacro with-reloadable-streams [& body]
  `(binding [*streams* (atom {})]
     ~@body))

(defn def-stream-fn [streamname body]
  (let [streamname (keyword (str *ns*) streamname)]
    (debug "Creating stream " streamname)
    (swap! *streams* assoc streamname body)))

(defmacro def-stream [streamname params & body]
  ;; TODO: add documentation that explains that the return body must
  ;; return a single riemann-compatible stream. That if there are
  ;; multiple streams in the body they must be surrounded by an `sdo`
  ;; or they will be disregarded.
  ;; TODO: support metadata and doc strings on def-stream
 `(def-stream-fn ~(name streamname)
     (fn ~params ~@body)))
