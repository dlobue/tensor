(defproject tensor "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "https://github.com/dlobue/tensor"
  :license {:name "Apache License 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0.html"}
  :profiles {:dev {:dependencies [[org.clojure/clojure "1.6.0"]
                                 [riemann "0.2.6"]]
                   :resource-paths ["test/resources"]}}
  :dependencies [[org.clojure/tools.logging "0.2.6"]])
