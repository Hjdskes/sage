(ns sage.mosquitto.fixture
  "Provides a test fixture that allows interacting with Mosquitto, the MQTT broker.

   The broker runs without any required authentication, so test setup remains light,
   but keep this in mind in case this runs on public or shared infrastructure."
  (:require
    [clojure.java.shell :as sh]
    [sage.mqtt.client :as mqtt]))

(set! *warn-on-reflection* true)

(def local-uri
  "The URI Mosquitto will run on."
  "tcp://127.0.0.1:1883")

(def ^:private *running?
  (atom false))

(defn mosquitto-running?
  "Is Mosquitto running?"
  []
  (swap! *running?
         (fn [running?]
           (or running?
               (-> (sh/sh "mosquitto_pub" "-t" "test" "-n" "--quiet")
                   :exit
                   zero?)))))

(defn start-mosquitto
  "Starts Mosquitto in daemon mode. Prints stderr on failure."
  []
  (println "Starting Mosquitto in daemon mode")
  (let [res (sh/sh "mosquitto" "--daemon")]
    (if (zero? (:exit res))
      (println "Mosquitto started")
      (println (format "Failed to start Mosquitto: %s" (:err res))))))

(defn ensure-mosquitto
  "Ensures Mosquitto is running by starting it if it isn't running."
  []
  (when-not (mosquitto-running?)
    (start-mosquitto)))

(def ^:dynamic *conn*
  "The connection to Mosquitto, binding the result of calling sage.mqtt.client/connect."
  nil)

(defn- connect-complete-handler
  [_client _reconnect server-uri]
  (println (format "Connected to MQTT broker on %s" server-uri)))

(defn- connection-lost-handler
  [^Throwable throwable]
  (println (format "Lost connection to MQTT broker on %s" (.getMessage throwable))))

(defn- unhandled-message-handler
  [topic _metadata _payload]
  (println (format "Received unhandled message on topic %s" topic)))

(defn mosquitto
  "A test fixture that starts Mosquitto, connects to it and binds the connection to *conn*.
  Closes the connection after executing the body. Note that it leaves Mosquitto running."
  [f]
  (ensure-mosquitto)
  (binding [*conn* (mqtt/connect! local-uri {:client-id "test-fixture"
                                             :max-inflight 50000
                                             :on-connect-complete connect-complete-handler
                                             :on-connection-lost connection-lost-handler
                                             :on-unhandled-message unhandled-message-handler})]
    (f)
    (mqtt/disconnect-and-close! *conn*)))

(defn mosquitto-rr
  "A helper function to send a request and receive a response outside of our Clojure code."
  ([request-topic response-topic payload]
   (mosquitto-rr request-topic response-topic payload 0))
  ([request-topic response-topic payload qos]
   (let [res (sh/sh "mosquitto_rr"
                    "-t" request-topic
                    "-e" response-topic
                    "-m" payload
                    "-q" (str qos)
                    "-i" "sage-mosquitto-rr" ; identify to the broker for easier debugging
                    "-F" "%t - %p" ; print <topic - payload> for each message
                    "-N" ; don't append newline characters after each message
                    )]
     (if (zero? (:exit res))
       (:out res)
       (println (format "mosquitto_rr received a non-zero exit code: %s - %s" res (:err res)))))))
