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
  [^java.lang.AutoCloseable closeable]
  (t/log! "Shutting down Sage")
  (try
    (.close closeable)
    (catch Exception e (t/log! {:level :error :data (Throwable->map e)} "Exception while shutting down"))
    (finally (t/log! "Shutdown complete"))))

(defn start!
  "Starts Sage, returning a closeable that closes all connections and frees all resources."
  ([]
   (start! :default))
  ([profile]
   (t/log! "Starting Sage")
   (mqtt/start-system! profile odoyle.session/mqtt-handler)))

(defn -main
  "Main entrypoint into Sage."
  [& _args]
  (set-uncaught-exception-handler!)
  (let [latch (java.util.concurrent.CountDownLatch. 1)
        sage (start!)]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. ^Runnable (fn []
                                           (shutdown! sage)
                                           (.countDown latch))))
    (.await latch)))

(comment
  (declare sage)

  (defn stop
    []
    (when (bound? #'sage) (.close ^java.lang.AutoCloseable sage)))

  (defn start
    []
    (stop)
    #_{:clj-kondo/ignore [:inline-def]}
    (def sage (start! :test)))

  (stop)
  (start))
