(defproject clj-statecharts "0.0.1-SNAPSHOT"
  :description "StateChart for Clojure(script)"
  :url "https://statecharts.github.io/"
  :min-lein-version "2.5.0"

  :aliases {"kaocha" ["with-profile" "+dev" "run" "-m" "kaocha.runner"]
            "test" ["version"]}

  :dependencies [[org.clojure/clojure "1.10.1" :scope "provided"]
                 [medley "1.3.0"]
                 [metosin/malli "0.0.1-SNAPSHOT"]]

  :jvm-opts
  [
   ;; ignore things not recognized for our Java version instead of
   ;; refusing to start
   "-XX:+IgnoreUnrecognizedVMOptions"
   ;; disable bytecode verification when running in dev so it starts
   ;; slightly faster
   "-Xverify:none"]
  :target-path "target/%s"
  :profiles {:dev {:jvm-opts ["-XX:+UnlockDiagnosticVMOptions"
                              "-XX:+DebugNonSafepoints"]

                   :injections [(require 'hashp.core)
                                (require 'debux.core)]

                   :source-paths ["dev/src" "local/src" "docs/samples/src"]
                   :dependencies [[philoskim/debux "0.7.5"]
                                  [org.clojure/clojurescript "1.10.773"]
                                  [org.clojure/test.check "1.1.0"]
                                  [expectations/clojure-test "1.2.1"]
                                  [lambdaisland/kaocha "1.0.663"]
                                  [hashp "0.2.0"]
                                  [nubank/matcher-combinators "3.1.1"
                                   :exclusions [midje]]
                                  [kaocha-noyoda "2019-06-03"]]}})