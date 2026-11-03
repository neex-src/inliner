(ns ide
  "`bb ijdev <cmd>`: the ijdev launcher (repls repo) run from this project's root.

  The hidden display is ijdev's own (`:display :hidden` in .ijdev.edn): an Xvnc per checkout, so
  two worktrees run two IDEs and stopping one touches nothing of the other. The launcher is
  $IJDEV_HOME/bin/ijdev, else ~/projects/repls/ijdev/bin/ijdev."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]))

(defn- die [& xs] (binding [*out* *err*] (println (str "bb ijdev: " (str/join " " xs)))) (System/exit 1))

(defn- root [] (str (fs/parent (System/getProperty "babashka.config"))))

(defn- ijdev-bin []
  (let [home (or (System/getenv "IJDEV_HOME")
                 (str (fs/path (System/getProperty "user.home") "projects/repls/ijdev")))
        bin (fs/path home "bin/ijdev")]
    (when-not (fs/exists? bin) (die "no launcher at" (str bin) "(set IJDEV_HOME to the repls ijdev directory)"))
    (str bin)))

(defn -main [& args]
  (let [{:keys [exit]} @(p/process (into ["bb" (ijdev-bin)] args) {:dir (root) :inherit true})]
    (System/exit exit)))
