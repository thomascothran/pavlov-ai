(ns dev
  "Local nREPL lifecycle for devenv and interactive development."
  (:require [clojure.java.io :as io]
            [nrepl.server :as nrepl]))

(defonce !nrepl-server (atom nil))

(defn start-nrepl!
  "Start a loopback-only nREPL and write its actual port to .nrepl-port.
  Defaults to an available port. Repeated calls return the running server."
  ([] (start-nrepl! {}))
  ([{:keys [port] :or {port 0}}]
   (locking !nrepl-server
     (or @!nrepl-server
         (let [server (nrepl/start-server :bind "127.0.0.1" :port port)]
           (reset! !nrepl-server server)
           (spit ".nrepl-port" (:port server))
           (println "nREPL started on port" (:port server))
           server)))))

(defn stop-nrepl!
  "Stop this namespace's server and remove its port file. Safe to call twice."
  []
  (locking !nrepl-server
    (when-let [server @!nrepl-server]
      (nrepl/stop-server server)
      (reset! !nrepl-server nil)
      (let [file (io/file ".nrepl-port")]
        (when (and (.exists file)
                   (= (str (:port server)) (slurp file)))
          (io/delete-file file))))))

(defn go!
  "Start nREPL and keep the devenv process alive until shutdown.
  Optional :port selects a fixed port instead of an available one."
  [options]
  (start-nrepl! options)
  (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable stop-nrepl!))
  @(promise))
