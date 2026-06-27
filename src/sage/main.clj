(ns sage.main
  (:require
    [sage.mqtt :as mqtt]
    [sage.odoyle.session :as odoyle.session]
    [taoensso.telemere :as t])
  (:gen-class))

(set! *warn-on-reflection* true)

(defn set-uncaught-exception-handler!
  "Log uncaught exceptions, see https://stuartsierra.com/2015/05/27/clojure-uncaught-exceptions."
  []
  (Thread/setDefaultUncaughtExceptionHandler
    (reify Thread$UncaughtExceptionHandler
      (uncaughtException [_this thread ex]
        (let [data (assoc (Throwable->map ex) :thread (.getName thread))]
          (t/log! {:level :error :data data} "Uncaught exception"))))))

(defn shutdown!
  "Shuts down Sage, expects the closeable from start!."
  [closeable]
  (t/log! "Shutting down Sage")
  (.close closeable)
  (t/log! "Shutdown complete"))

(defn start!
  "Starts Sage, returning a closeable that closes all connections and frees all resources."
  ([]
   (start! {}))
  ([profile]
   (t/log! "Starting Sage")
   (mqtt/start-system! profile odoyle.session/mqtt-handler)))

(defn -main
  "Main entrypoint into Sage."
  [& _args]
  (set-uncaught-exception-handler!)
  (let [latch (java.util.concurrent.CountDownLatch. 1)
        sage (start! :default)]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. ^Runnable (fn []
                                           (shutdown! sage)
                                           (.countDown latch))))
    (.await latch)
    (.join (Thread/currentThread))))

(comment
  (declare sage)

  (defn stop
    []
    (when (bound? #'sage) (.close sage)))

  (defn start
    []
    (stop)
    #_{:clj-kondo/ignore [:inline-def]}
    (def sage (start! :test)))

  (stop)
  (start))
