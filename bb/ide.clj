(ns ide
  "`bb ijdev <cmd>`: the ijdev launcher (repls repo) run from this project's root.

  The hidden display is ijdev's own (`:display :hidden` in .ijdev.edn): an Xvnc per checkout, so
  two worktrees run two IDEs and stopping one touches nothing of the other. The launcher is
  $IJDEV_HOME/bin/ijdev, else the `ijdev` on the PATH (the sandbox box has it there), else
  ~/projects/repls/ijdev/bin/ijdev (a developer machine)."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]))

(defn- die [& xs] (binding [*out* *err*] (println (str "bb ijdev: " (str/join " " xs)))) (System/exit 1))

(defn- root [] (str (fs/parent (System/getProperty "babashka.config"))))

(defn launcher
  "The ijdev script to run, or nil. `ijdev-home` is $IJDEV_HOME (nil when unset or blank), `which` finds a program on the PATH,
  `exists?` says whether a file is there; the user's home is where the old default lives. $IJDEV_HOME always wins: when it is set
  and has no launcher the answer is nil, never a silent fall-through to another ijdev."
  [{:keys [ijdev-home user-home which exists?]}]
  (let [from-home (fn [h] (str (fs/path h "bin/ijdev")))]
    (cond
      (not (str/blank? ijdev-home)) (let [f (from-home ijdev-home)] (when (exists? f) f))
      (which "ijdev") (which "ijdev")
      :else (let [f (from-home (fs/path user-home "projects/repls/ijdev"))] (when (exists? f) f)))))

(defn- ijdev-bin []
  (or (launcher {:ijdev-home (System/getenv "IJDEV_HOME")
                 :user-home (System/getProperty "user.home")
                 :which (fn [x] (some-> (fs/which x) str))
                 :exists? fs/exists?})
      (die "no ijdev launcher: set IJDEV_HOME to the repls ijdev directory (its bin/ijdev), or put ijdev on the PATH")))

(defn -main [& args]
  (let [{:keys [exit]} @(p/process (into ["bb" (ijdev-bin)] args) {:dir (root) :inherit true})]
    (System/exit exit)))
