(ns tensor.core
  (:require [clojure.string :as string]
            [clojure.tools.logging :refer :all]
            [clojure.walk :refer [prewalk]]
            [com.stuartsierra.dependency :as dep]
            [medley.core :refer [filter-keys deref-swap!]]
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

(declare ^:dynamic *dag*)
(declare ^:dynamic *streams*)
;; TODO: see if we can use metadata to ensure that *streams* is always
;; an atom containing a map.

(defn get-stream [streamname]
  (let [streamname (keyword streamname)
        ;; if namespace returns nil, that means that no specific
        ;; stream identifiers were given, and we need to load every
        ;; stream we find in the namespace
        load-all? (nil? (namespace streamname))
        get-stream' (if-not load-all?
                      (fn [sname] (get @*streams* sname))
                      (fn [stream-ns]
                        (let [stream-ns (name stream-ns)]
                          (filter
                           (complement
                            #(:wildcard-exclude (meta %)))
                           (vals
                            (filter-keys
                             (fn [k]
                               (= (namespace k)
                                  stream-ns))
                             @*streams*))))))]
    (debug "Getting stream " streamname)
    (if-let [stream (get-stream' streamname)]
      stream
      (do
        (debug "Stream " streamname " yet not in registry")
        (dir-loader (or (namespace streamname)
                        (name streamname)))
        (get-stream' streamname)))))

(defn load-stream-fn
  ([streamname env]
     (load-stream-fn streamname env []))
  ([streamname env body]
     (let [stream (get-stream streamname)]
       (debug "Loading stream " streamname)
       (trace "Loading stream " streamname "with env: " env)
       (if (coll? stream)
         (doall (map #(apply % env body) stream))
         (apply stream env body)))))

(defn load-streams-fn [env streamspecs]
  (let [streamspecs (if (sequential? streamspecs)
                      streamspecs
                      [streamspecs])]
    (debug "Loading streams " streamspecs)
    (apply sdo
           (flatten
            (for [streamspec streamspecs
                  :let [streamname (keyword (if (sequential? streamspec)
                                              (first streamspec)
                                              streamspec))
                        args (when (sequential? streamspec)
                               (rest streamspec))]]
              (load-stream-fn streamname env args))))))

(defn- update-env [opts env]
  (if (empty? opts)
    env
    (if-let [env' (:env opts)]
      env'
      (let [remover (if-let [env-only (:env-only opts)]
                      #(select-keys % env-only)
                      #(apply dissoc % (:env-exclude opts)))]
        (-> env
            remover
            (merge (:env-include opts)))))))

(defmacro load-streams [& streamnames]
  (let [symbols (keys &env)
        env (zipmap (map keyword symbols) symbols)
        [streamnames opts] (split-with (complement keyword?) streamnames)
        opts (apply hash-map opts)
        env (update-env opts env)]
    `(load-streams-fn ~env '~streamnames)))

(defmacro with-reloadable-streams [& body]
  `(binding [*streams* (atom {})
             *dag* (atom (dep/graph))]
     ~@body))



(defn- register-deps [parent]
  (fn [form]
    (when (coll? form)
      (cond
       (= 'load-streams (first form))
       (doseq [streamspec (take-while (complement keyword?) (rest form))]
         ;;TODO: need to handle wildcard case
         ;;TODO: replace underscores in streamspec with dashes to
         ;;compensate for typos
         (swap! *dag* dep/depend parent (keyword
                                         (if (list? streamspec)
                                           (first streamspec)
                                           streamspec))))
       (some coll? form) form))))



(defn def-stream-fn [streamname body]
  (let [streamname (keyword (str *ns*) streamname)]
    (debug "Creating stream " streamname)
    (if-not (deref-swap! *streams* assoc streamname body)
      (warn "Overriding already-existing stream " streamname))))

(defmacro def-stream [streamname & decl]
  ;; TODO: add documentation that explains that the return body must
  ;; return a single riemann-compatible stream. That if there are
  ;; multiple streams in the body they must be surrounded by an `sdo`
  ;; or they will be disregarded.
  ;; TODO: support doc strings on def-stream
  (prewalk (register-deps (keyword (str *ns*) (name streamname))) decl)
 `(def-stream-fn ~(name streamname)
    (with-meta (fn ~@decl)
      ~(merge (meta streamname)
              (meta decl)))))
