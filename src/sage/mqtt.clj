(ns sage.mqtt
  (:require
    [clojure.data.json :as json]
    [sage.config :as config]
    [sage.mqtt.client :as mqtt.client]
    [sage.util.closeable :refer [closeable]]
    [taoensso.telemere :as t]))

(set! *warn-on-reflection* true)

(defn- connect-complete-handler
  [_client _reconnect uri]
  (t/log! {:data {:mqtt/broker uri}} "MQTT connection complete"))

(defn- connection-lost-handler
  [throwable]
  (t/log! {:level :error :data (Throwable->map throwable)} "MQTT connection lost"))

(defn- unhandled-message-handler
  [^String topic _metadata ^bytes _payload]
  (t/log! {:level :warn :data {:mqtt/topic topic}} "Received unhandled MQTT message"))

(defn- message-handler
  [handler-fn]
  (fn sage-mqtt-consumer [topic metadata payload]
    (if-let [payload (try (-> payload
                              java.io.ByteArrayInputStream.
                              (java.io.InputStreamReader. "UTF-8")
                              (json/read {:key-fn keyword}))
                          (catch Exception _e nil))]
      (t/trace! {:id :mqtt/handler :data {:mqtt/topic topic :mqtt/metadata metadata :mqtt/payload payload}}
                (try
                  (handler-fn topic payload)
                  (catch Exception ex
                    ; Don't rethrow so as to not blow up the thread/subscription.
                    (t/log! {:level :error :data (assoc (Throwable->map ex) :mqtt/payload payload)}
                            "Exception while handling MQTT message"))))
      (t/log! {:level :warn :data {:mqtt/topic topic :mqtt/payload payload}}
              "MQTT message is empty or not JSON"))))

(defn start-system!
  "Applies the given handler-fn to each message received over MQTT.

   Returns a closeable that contains the connection to MQTT and disconnects on close."
  [handler-fn]
  (let [{:keys [uri] :as mqtt-config} (config/get-key :mqtt-config)
        _ (t/log! {:data {:mqtt/broker uri}} "Connecting to MQTT")
        conn (mqtt.client/connect! uri {:client-id "sage"
                                        :on-connect-complete connect-complete-handler
                                        :on-connection-lost connection-lost-handler
                                        :on-unhandled-message unhandled-message-handler
                                        :opts (dissoc mqtt-config :uri)})]
      ;; A note on publishing from within a message listener:
      ;; > An acknowledgment is not sent back to the server until this method returns cleanly.
      ;; > It is possible to send a new message within an implementation of this callback (for
      ;; > example, a response to this message), but the implementation must not disconnect
      ;; > the client, as it will be impossible to send an acknowledgment for the message being
      ;; > processed, and a deadlock will occur.
      ;; See https://eclipse.dev/paho/files/javadoc/org/eclipse/paho/client/mqttv3/IMqttMessageListener.html.
    (.waitForCompletion
      ;; TODO: be more precise with the topics we subscribe to, so we e.g. don't see our own commands
      ;; or messages that aren't meant for us, such as /set or /get.
      (mqtt.client/subscribe! conn "#" :at-most-once (message-handler (partial handler-fn conn)))
      5000)
    (closeable conn (fn [conn]
                      (t/log! {:data {:mqtt/broker uri}} "Disconnecting from MQTT")
                      (mqtt.client/disconnect-and-close! conn 5000)))))
