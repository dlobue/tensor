(defproject tensor "0.2.0-SNAPSHOT"
  :description "A Riemann library for organizing your configuration"
  :url "https://github.com/dlobue/tensor"
  :license {:name "Apache License 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0.html"}
  :profiles {:dev {:dependencies [[org.clojure/clojure "1.9.0"]
                                  [riemann "0.3.0"]
                                  [cloverage "1.0.10"]]
                   :resource-paths ["test/resources"]}}
  :plugins [[lein-cloverage "1.0.6"]]
  :dependencies [[org.clojure/tools.logging "0.4.0"]
                 [org.clojure/tools.macro "0.1.5"]
                 [com.stuartsierra/dependency "0.2.0"]
                 [medley "1.0.0"]])
