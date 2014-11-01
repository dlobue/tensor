(ns tensor.core
  (:require [clojure.string :as string]
            [clojure.tools.logging :refer :all]
            [clojure.tools.macro :refer [name-with-attributes]]
            [clojure.walk :refer [prewalk]]
            [com.stuartsierra.dependency :as dep]
            [medley.core :refer [filter-keys deref-swap!]]
            [riemann.streams :refer [sdo]]))

(defn- pkg-to-path
  [pkg]
  (as-> pkg p
        (string/replace p #"-" "_")
        (string/replace p #"\." "/")
        (str "/" p)))

(defn- dir-loader [pkg]
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
                          (seq
                           (filter
                            (complement
                             #(:wildcard-exclude (meta %)))
                            (vals
                             (filter-keys
                              (fn [k]
                                (= (namespace k)
                                   stream-ns))
                              @*streams*)))))))]
    (debug "Getting stream " streamname)
    (if-let [stream (get-stream' streamname)]
      stream
      (do
        (debug "Stream " streamname " yet not in registry")
        (dir-loader (or (namespace streamname)
                        (name streamname)))
        (if-let [stream (get-stream' streamname)]
          stream
          (throw (ex-info (str "Stream " streamname " not found")
                         {:type :no-stream-found})))))))

(defn- streamspec-walker [transform-fn env]
  (fn [element]
    (let [[element' & r] (if (list? element) element [element])]
      (if (and (symbol? element')
               (nil? (resolve element'))
               (not (contains? env (keyword element'))))
        (transform-fn element' env r)
        element))))

(defn load-stream-fn
  ([streamname env]
     (load-stream-fn streamname env nil))
  ([streamname env body]
     (let [stream (get-stream streamname)
           body (when-not (empty? body)
                  (let [b
                        (prewalk (streamspec-walker load-stream-fn env) body)]
                    ;; ugh
                    (if-not (or (vector? b)
                                (list? b))
                      (list b)
                      b)))]
       (debug "Loading stream " streamname)
       (trace "Loading stream " stream " with env: " env)
       (if (coll? stream)
         (doall (map #(apply % env body) stream))
         (apply stream env body)))))

(defn load-streams-fn [env streamspecs]
  (let [streamspecs (if (sequential? streamspecs)
                      streamspecs
                      [streamspecs])]
    (debug "Loading streams " streamspecs)
    (apply sdo
           (doall
            (flatten
             (for [streamspec streamspecs
                   :let [streamname (if (sequential? streamspec)
                                      (first streamspec)
                                      streamspec)
                         args (when (sequential? streamspec)
                                (rest streamspec))]]
               (load-stream-fn streamname env args)))))))

(defn- update-env [opts env]
  (if (empty? opts)
    env
    (if-let [env' (:env opts)]
      env'
      (let [remover (if-let [env-only (:env-only opts)]
                      #(select-keys % (if-not (sequential? env-only)
                                        [env-only]
                                        env-only))
                      (let [env-exclude (:env-exclude opts)
                            env-exclude (if-not (sequential? env-exclude)
                                          [env-exclude]
                                          env-exclude)]
                        #(apply dissoc % env-exclude)))]
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
         ;;TODO: replace underscores in streamspec with dashes to
         ;;compensate for typos
         (swap! *dag* dep/depend parent (keyword
                                         (if (list? streamspec)
                                           (first streamspec)
                                           streamspec))))
       (some coll? form) form))))

(defn def-stream-fn [streamname body]
  (debug "Creating stream " streamname)
  (if-not (deref-swap! *streams* assoc streamname body)
    (warn "Overriding already-existing stream " streamname)))

(defmacro def-stream [streamname & decl]
  ;; TODO: add documentation that explains that the return body must
  ;; return a single riemann-compatible stream. That if there are
  ;; multiple streams in the body they must be surrounded by an `sdo`
  ;; or they will be disregarded.
  (let [[streamname decl] (name-with-attributes streamname decl)
        streamname' (keyword (str *ns*) (name streamname))]
    ;; Create dependency from the namespace to the streams within the
    ;; namespace in order to support cyclical dependency detection with
    ;; wildcard loads.
    (swap! *dag* dep/depend (keyword (namespace streamname')) streamname')
    (prewalk (register-deps streamname') decl)
    `(def-stream-fn ~streamname'
       (with-meta (fn ~@decl)
         ~(meta streamname)))))
