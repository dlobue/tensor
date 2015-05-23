(ns leiningen.coveralls
  (:require [clojure.string :as s]
            [leiningen.core.eval :as lein-eval]
            [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.java.io :refer [input-stream]]))

(def COVERALLS_URL "https://coveralls.io/api/v1/jobs")

(def git-fields
  [[:id "%H"]
   [:author_name "%aN"]
   [:author_email "%ae"]
   [:committer_name "%cN"]
   [:committer_email "%ce"]
   [:message "%s"]])

(defn- git-commit-attr [fmt]
  (with-out-str
    (lein-eval/sh "git" "log" "-1" (str "--pretty=format:" fmt))))

(defn- git-commit-attrs []
  (into {} (map (fn [[k v]] [k (git-commit-attr v)]) git-fields)))

(defn- git-remotes []
  (let [remotes (s/split-lines (with-out-str
                                 (lein-eval/sh "git" "remote" "-v")))]
    (vec (set (map #(zipmap [:name :url]
                            (s/split % #"\s+"))
                   remotes)))))

(defn- git-details []
  {:head (git-commit-attrs)
   :branch (System/getenv "CIRCLE_BRANCH")
   :remotes (git-remotes)})

(defn- missing-coveralls-details []
  {:repo_token (System/getenv "COVERALLS_REPO_TOKEN")
   :service_pull_request (last (s/split (System/getenv "CI_PULL_REQUEST") #"\/"))
   :service_number (System/getenv "CIRCLE_BUILD_NUM")
   :git (git-details)})

(defn- update-coveralls-json [coveralls-json-path]
  (json/generate-string
   (merge (json/parse-string (slurp coveralls-json-path))
          (missing-coveralls-details))))

(defn- submit-coverage-data [coveralls-json-path]
  (with-open [content (-> (update-coveralls-json coveralls-json-path)
                          .getBytes
                          input-stream)]
    (http/post COVERALLS_URL
               {:throw-entire-message? true
                :multipart
                [{:name "json_file"
                  :content content}]})))

(defn coveralls [project coveralls-json-path]
  (submit-coverage-data coveralls-json-path))

