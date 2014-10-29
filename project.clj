(defproject tensor "0.2.0-SNAPSHOT"
  :description "A Riemann library for organizing your configuration"
  :url "https://github.com/dlobue/tensor"
  :license {:name "Apache License 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0.html"}
  :profiles {:dev {:dependencies [[org.clojure/clojure "1.6.0"]
                                 [riemann "0.2.6"]]
                   :resource-paths ["test/resources"]}}
  :dependencies [[org.clojure/tools.logging "0.2.6"]
                 [com.stuartsierra/dependency "0.1.1"]
                 [medley "0.5.1"]])
