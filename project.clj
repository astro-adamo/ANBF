(defproject anbf "0.5.0-SNAPSHOT"
  :description "A Nethack Bot Framework"
  :url "https://github.com/krajj7/ANBF"
  :license {:name "GPLv2"}
  :repositories {"local" "file:repo"}
  :java-source-paths ["jta26/de/mud" "java"]
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.clojure/data.priority-map "0.0.5"]
                 [org.clojure/tools.logging "0.2.6"]
                 [org.clojure/core.logic "0.8.8"]
                 [com.cemerick/pomegranate "0.3.0"]
                 [org.flatland/ordered "1.5.2"]
                 [org.clojars.achim/multiset "0.1.0-SNAPSHOT"]
                 [com.jcraft/jsch "0.1.42"]
                 [criterium "0.4.3"]
                 [log4j/log4j "1.2.17" :exclusions [javax.mail/mail
                                                    javax.jms/jms
                                                    com.sun.jmdk/jmxtools
                                                    com.sun.jmx/jmxri]]]
  ;:global-vars {*warn-on-reflection* true}
  :aot [anbf.bot anbf.delegator anbf.actions anbf.term anbf.ttyrec anbf.main]
  :main anbf.main)
