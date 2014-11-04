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
  {:pre [(string? pkg)]}
  (as-> pkg p
        (string/replace p #"-" "_")
        (string/replace p #"\." "/")
        (str "/" p)))

(defn- dir-loader [pkg]
  (debug "Loading package " pkg)
  (load (pkg-to-path pkg)))

(defn coerce-list [arg]
  (if-not (sequential? arg)
    (list arg) arg))

(declare ^:dynamic *dag*)
(declare ^:dynamic *streams*)
;; TODO: see if we can use metadata to ensure that *streams* is always
;; an atom containing a map.

(defn- get-stream' [streamname]
  {:pre [(keyword? streamname)]}
  ;; if namespace returns nil, that means that no specific
  ;; stream identifiers were given, and we need to load every
  ;; stream we find in the namespace
  (if-not (nil? (namespace streamname))
    (get @*streams* streamname)

    (->> @*streams*
         (filter
          (fn [[k s]]
            (and (= (namespace k)
                    (name streamname))
                 (not (:wildcard-exclude (meta s))))))
         vals)))


(defn get-stream [streamname]
  (let [streamname (keyword streamname)]
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
  {:pre [(fn? transform-fn)
         (map? env)]}
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
                  (-> (prewalk (streamspec-walker load-stream-fn env) body)
                      coerce-list))]
       (debug "Loading stream " streamname)
       (trace "Loading stream " stream " with env: " env)
       (if (coll? stream)
         (doall (map #(apply % env body) stream))
         (apply stream env body)))))

(defn load-streams-fn [env streamspecs]
  (let [streamspecs (coerce-list streamspecs)]
    (debug "Loading streams " streamspecs)
    (doall
     (flatten
      (for [streamspec streamspecs
            :let [streamname (if (sequential? streamspec)
                               (first streamspec)
                               streamspec)
                  args (when (sequential? streamspec)
                         (rest streamspec))]]
        (load-stream-fn streamname env args))))))

(defn- update-env [opts env]
  {:pre [(map? opts)
         (map? env)]}
  (if (empty? opts)
    env
    (if-let [env' (:env opts)]
      env'
      (let [remover (if-let [env-only (:env-only opts)]
                      #(select-keys % (coerce-list env-only))
                      #(apply dissoc % (coerce-list (:env-exclude opts))))]
        (-> env
            remover
            (merge (:env-include opts)))))))

(defn separate-streamspecs-and-opts [args]
  (let [[streamspecs opts] (split-with (complement keyword?) args)
        opts (apply hash-map opts)]
    [streamspecs opts]))

(defmacro load-streams [& args]
  (let [[streamspecs opts] (separate-streamspecs-and-opts args)
        env (as-> (keys &env) e
                  (zipmap (map keyword e) e)
                  (update-env opts e))]
    `(apply sdo (load-streams-fn ~env '~streamspecs))))

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
