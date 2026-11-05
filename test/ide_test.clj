(ns ide-test
  "The ijdev launcher choice of bb/ide.clj: $IJDEV_HOME wins, then the PATH, then the old ~/projects default."
  (:require [clojure.test :refer [deftest is run-tests]]
            [ide]))

(defn- world [& {:keys [ijdev-home path files]}]
  {:ijdev-home ijdev-home :user-home "/home/u"
   :which (fn [x] (get path x))
   :exists? (set files)})

(deftest ijdev-home-wins-over-the-path
  (is (= "/h/ijdev/bin/ijdev"
         (ide/launcher (world :ijdev-home "/h/ijdev" :path {"ijdev" "/usr/bin/ijdev"} :files ["/h/ijdev/bin/ijdev"])))))

(deftest a-set-home-without-a-launcher-does-not-fall-through
  (is (nil? (ide/launcher (world :ijdev-home "/h/ijdev" :path {"ijdev" "/usr/bin/ijdev"} :files [])))))

(deftest the-path-when-no-home-is-set
  (is (= "~/.local/bin/ijdev"
         (ide/launcher (world :path {"ijdev" "~/.local/bin/ijdev"} :files [])))))

(deftest a-blank-home-counts-as-unset
  (is (= "/usr/bin/ijdev" (ide/launcher (world :ijdev-home "" :path {"ijdev" "/usr/bin/ijdev"})))))

(deftest the-old-default-last
  (is (= "~/projects/repls/ijdev/bin/ijdev"
         (ide/launcher (world :files ["~/projects/repls/ijdev/bin/ijdev"])))))

(deftest nothing-found
  (is (nil? (ide/launcher (world)))))

(defn -main [& _]
  (let [{:keys [fail error]} (run-tests 'ide-test)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
