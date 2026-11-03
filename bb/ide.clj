(ns ide
  "`bb ijdev <cmd>`: the ijdev CLI (repls repo) with the sandbox IDE on a hidden display.

  Bare ijdev puts the IDE on $IJDEV_DISPLAY, else $DISPLAY, else :0 — the user's screen. Here it
  always gets :77, an Xvnc server (TigerVNC; the host has no Xvfb) with no TCP port: nothing shows.
  Xvnc insists on one way in, so it gets a unix socket only user can open, .ijdev/xvnc.sock (to
  look anyway: `vncviewer .ijdev/xvnc.sock`). It runs in its own tmux window `xvnc-inliner`,
  beside the IDE's: `start` and `restart` bring it up first, `stop` takes it down after the IDE."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]))

(def display ":77")
(def window "xvnc-inliner")

(defn- die [& xs] (binding [*out* *err*] (println (str "bb ijdev: " (str/join " " xs)))) (System/exit 1))

(defn- root [] (str (fs/parent (System/getProperty "babashka.config"))))

(defn- ijdev-bin
  "The launcher: $IJDEV_HOME/bin/ijdev, else ~/projects/repls/ijdev/bin/ijdev (the repls checkout)."
  []
  (let [home (or (System/getenv "IJDEV_HOME")
                 (str (fs/path (System/getProperty "user.home") "projects/repls/ijdev")))
        bin (fs/path home "bin/ijdev")]
    (when-not (fs/exists? bin) (die "no launcher at" (str bin) "(set IJDEV_HOME to the repls ijdev directory)"))
    (str bin)))

(defn- socket [] (str "/tmp/.X11-unix/X" (subs display 1)))

(defn- tmux [& args] (apply p/sh "tmux" args))

(defn- in-tmux? [] (some? (System/getenv "TMUX")))

(defn- window-alive? []
  (if (in-tmux?)
    (boolean (some #{window} (str/split-lines (:out (tmux "list-windows" "-F" "#{window_name}")))))
    (zero? (:exit (tmux "has-session" "-t" window)))))

(defn- ensure-display! []
  (cond
    (window-alive?) nil
    (fs/exists? (socket)) (die "display" display "is taken by something else; pick another in bb/ide.clj")
    :else
    (let [rfb (str (fs/path (doto (fs/path (root) ".ijdev") fs/create-dirs) "xvnc.sock"))
          cmd (str "Xvnc " display " -geometry 1920x1200 -depth 24 -nolisten tcp -desktop inliner-ide"
                   " -rfbport -1 -rfbunixpath " rfb " -rfbunixmode 0600 -SecurityTypes None")]
      (if (in-tmux?)
        (tmux "new-window" "-d" "-n" window cmd)
        (tmux "new-session" "-d" "-s" window cmd))
      (loop [i 0]
        (cond (fs/exists? (socket)) (println "display" display "up (Xvnc, hidden) in tmux window" window)
              (or (> i 50) (not (window-alive?))) (die "Xvnc did not come up on" display)
              :else (do (Thread/sleep 100) (recur (inc i))))))))

(defn- stop-display! []
  (when (window-alive?)
    ;; closing the window sends SIGHUP, which an X server takes as "reset", not "exit": Ctrl-C it
    (tmux "send-keys" "-t" window "C-c")
    (loop [i 0] (when (and (< i 50) (fs/exists? (socket))) (Thread/sleep 100) (recur (inc i))))
    (when (window-alive?) (if (in-tmux?) (tmux "kill-window" "-t" window) (tmux "kill-session" "-t" window)))
    (println "display" display (if (fs/exists? (socket)) "still up: see `pgrep -a Xvnc`" "stopped"))))

(defn -main [& args]
  (let [cmd (first args)]
    (when (#{"start" "restart"} cmd) (ensure-display!))
    (let [{:keys [exit]} @(p/process (into ["bb" (ijdev-bin)] args)
                                     {:dir (root) :inherit true :extra-env {"IJDEV_DISPLAY" display}})]
      (when (= "stop" cmd) (stop-display!))
      (System/exit exit))))
