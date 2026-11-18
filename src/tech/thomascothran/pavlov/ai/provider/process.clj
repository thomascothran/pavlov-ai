(ns tech.thomascothran.pavlov.ai.provider.process
  "Process transport shared by the CLI providers (:claude-cli, :pi-cli).

  A CLI provider's :run! is a fn of {:command [...] :stdin string :dir string?
  :timeout-ms int?} returning {:exit :out :err}. It throws
  java.util.concurrent.TimeoutException on timeout and java.io.IOException
  when the process cannot start. `run-process` is the default implementation."
  (:require [clojure.java.io :as io])
  (:import (java.io IOException)
           (java.lang ProcessHandle)
           (java.util.concurrent TimeUnit TimeoutException)))

(defn- destroy-tree!
  [^Process process]
  (doseq [^ProcessHandle child (iterator-seq (.iterator (.descendants process)))]
    (.destroyForcibly child))
  (.destroyForcibly process))

(defn run-process
  "Default :run! for the CLI providers, built on ProcessBuilder.

  Starts COMMAND in DIR (or the JVM's working directory when DIR is nil),
  writes STDIN as UTF-8 and waits up to TIMEOUT-MS (forever when nil).
  Returns {:exit :out :err}. Throws TimeoutException after killing the process
  tree on timeout, and IOException when the process cannot be started."
  [{:keys [command stdin dir timeout-ms]}]
  (let [builder (ProcessBuilder. ^java.util.List (vec command))
        _ (when dir (.directory builder (io/file dir)))
        process (.start builder)
        out (future (slurp (.getInputStream process) :encoding "UTF-8"))
        err (future (slurp (.getErrorStream process) :encoding "UTF-8"))
        completed? (volatile! false)]
    (try
      (try
        (with-open [writer (io/writer (.getOutputStream process) :encoding "UTF-8")]
          (.write writer (str stdin)))
        (catch IOException _
          ;; The process exited without reading stdin; its exit status and
          ;; output describe the failure.
          nil))
      (when-not (if timeout-ms
                  (.waitFor process (long timeout-ms) TimeUnit/MILLISECONDS)
                  (do (.waitFor process) true))
        (throw (TimeoutException. "Process timed out")))
      (vreset! completed? true)
      {:exit (.exitValue process) :out @out :err @err}
      (finally
        (when-not @completed?
          (destroy-tree! process))))))
