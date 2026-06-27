(ns sage.mqtt.client
  "Key MQTT client functions: connection, subscription, publishing.

   A note on Paho threading and internals:

   Paho dispatches callbacks (MqttCallback methods, e.g. messageArrived) on a single
   dedicated thread it manages internally, called the \"callback thread\". This means
   callbacks are serialised relative to each other, but it also means a slow or blocking
   callback will delay all subsequent ones.

   Per-subscription listeners (IMqttMessageListener) registered via .subscribe are also
   called on this same callback thread, so they share the serialisation guarantee with
   the global callback. This means a slow handler-fn in your subscribe! or :on-*-fns
   directly stalls all of the above, including delivery acks.

   Async operations (methods like .publish, .subscribe, .connect, etc) return IMqttTokens
   and execute asynchronously on Paho's own internal threads. The method
   .waitForCompletion blocks its calling thread until the operation finishes.

   Uncaught exceptions shut down the connection: the run() loop has a catch (Throwable
   ex) that calls clientComms.shutdownConnection(...). This means that any exception
   thrown from any of the Clojure callback functions will (silently!) kill the
   connection.

   The inbound queue is capped at a maximum of 10 messages. If messages arrive faster
   than your handler processes them, the receiver thread blocks until space opens up.
   This is a backpressure mechanism, but it means a slow handler can stall the receiver
   and eventually cause the broker to drop the connection if keep-alive times out. If
   your handler does any meaningful I/O or blocking work, consider dispatching to a
   separate thread pool inside the handler.

   When a message matches both a wildcard topic and a direct match, both listeners are
   called, due to Paho's matching internals.

   This is a reimplementation of clojurewerkz/machine_head on top of Paho's asynchronous
   MQTT client."
  (:require
    [clojure.set :as set]
    [clojure.string :as str]
    [sage.mqtt.conversion :refer [->byte-array]])
  (:import
    [org.eclipse.paho.client.mqttv3
     IMqttAsyncClient
     IMqttDeliveryToken
     IMqttMessageListener
     IMqttToken
     MqttAsyncClient
     MqttCallbackExtended
     MqttConnectOptions
     MqttMessage]))

(set! *warn-on-reflection* true)

(defn- generate-id
  "Generates a valid client id, per the MQTT spec[1].

   [1]: https://docs.oasis-open.org/mqtt/mqtt/v3.1.1/os/mqtt-v3.1.1-os.html#_Toc385349242"
  []
  (let [id (str "sage-" (System/nanoTime))]
    (subs id 0 (min (count id) 23))))

(def ^:private qos->int
  "Map human-readable MQTT quality of service levels to their integer representation.
   See https://www.hivemq.com/blog/mqtt-essentials-part-6-mqtt-quality-of-service-levels/ or
   https://github.com/eclipse-paho/paho.mqtt.java/blob/master/org.eclipse.paho.client.mqttv3/src/main/java/org/eclipse/paho/client/mqttv3/MqttMessage.java#L139-L173.

   +-----+--------------------------------+------------------------------------------------+
   | QoS | Publisher                      | Subscriber                                     |
   +-----+--------------------------------+------------------------------------------------+
   |   0 | Will send a message only once  | Might receive or might not receive the message |
   +-----+--------------------------------+------------------------------------------------+
   |   1 | Will send a message at least   | It is likely to receive the message at least   |
   |     | once as long as an ack is      | once (it is possible that the message can be   |
   |     | received or the command to end | received more than once)                       |
   |     | the transmission is received   |                                                |
   +-----+--------------------------------+------------------------------------------------+
   |   2 | Will only send a message once  | Will only receive the message once             |
   +-----+--------------------------------+------------------------------------------------+"
  {:at-most-once 0
   :at-least-once 1
   :exactly-once 2})

(def ^:private int->qos
  (set/map-invert qos->int))

(defn- ->connect-options
  "Turn a Clojure map into an instance of `org.eclipse.paho.client.mqttv3.MqttConnectOptions`.

   Defaults are:
   - keep alive internal: 60 seconds
   - connection timeout: 30 seconds
   - clean session: true
   - max in flight: 10
   - automatic reconnect: false
   - will: none

   Unrecognised options will throw.
   See https://eclipse.dev/paho/files/javadoc/org/eclipse/paho/client/mqttv3/MqttConnectOptions.html for more information."
  [m]
  (let [o (MqttConnectOptions.)]
    (doseq [[k v] m]
      (case k
        :username (when-not (str/blank? ^String v) (.setUserName o v))
        :password (when-not (str/blank? ^String v) (.setPassword o (.toCharArray ^String v)))
        :keep-alive-interval-s (.setKeepAliveInterval o v)
        :connection-timeout-s (.setConnectionTimeout o v)
        :clean-session (.setCleanSession o v)
        :max-inflight (.setMaxInflight o v)
        :auto-reconnect (.setAutomaticReconnect o v)
        :will (.setWill
                ^MqttConnectOptions o
                ^String (:topic v)
                ^bytes (:payload v (byte-array 0))
                ^int (qos->int (:qos v :at-most-once))
                ^boolean (:retain v false))
        (throw (ex-info "Unknown MQTT connect option" {:key k}))))
    o))

(defn- message->metadata
  [^MqttMessage message]
  {:retained? (.isRetained message)
   :qos (int->qos (.getQos message))
   :duplicate? (.isDuplicate message)})

(defn connect!
  "Instantiates a new client and connects to the MQTT broker.

   This blocks the calling thread until a connection is made, or the timeout is reached.
   Control the timeout with :connect-timeout-ms, see below. If the connection attempt times out,
   this throws Paho's MqttException with reason code WAIT_FOR_COMPLETION_TIMEOUT.

   Options (all keys are optional):

    * :client-id: a client identifier that is unique on the MQTT broker being connected to
    * :connect-timeout-ms: timeout in milliseconds when connecting
    * :opts: see MQTT connect options below
    * :on-connect-complete-fn: function called after connecting to the MQTT broker
    * :on-connection-lost-fn: function called when the connection to the MQTT broker is lost
    * :on-delivery-complete-fn: function called when sending and delivery for a message has
       been completed (depending on its QoS), and all acknowledgments have been received
    * :on-unhandled-message-fn: function called when a message has arrived and hasn't been handled
       by a subscription handler; invoked with 3 arguments:
       * the topic that the message was received on,
       * an immutable map of message metadata, and
       * the byte array of message payload.

   MQTT connect options: a map with any of the keys below:

    * :username (string)
    * :password (string)
    * :keep-alive-interval-s (int, seconds)
    * :connection-timeout-s (int, seconds)
    * :clean-session (boolean)
    * :max-inflight (int)
    * :auto-reconnect (boolean)
    * :will (map with keys :topic (string), :payload (bytes, optional), :qos (enum, optional) and
             :retain (boolean, optional))

    See https://eclipse.dev/paho/files/javadoc/org/eclipse/paho/client/mqttv3/MqttConnectOptions.html for more information."
  (^IMqttAsyncClient [uri]
   (connect! uri {}))
  (^IMqttAsyncClient [uri {:keys [client-id
                                  connect-timeout-ms
                                  opts
                                  on-delivery-complete-fn
                                  on-connection-lost-fn
                                  on-connect-complete-fn
                                  on-unhandled-message-fn]
                           :or {connect-timeout-ms 1000
                                opts {}}}]
   (assert (seq uri) "Need MQTT broker URI")
   (let [client (MqttAsyncClient. uri (or client-id (generate-id)) nil) ; nil means in-memory persistence.
         ;; TODO: consider wrapping these in try/catch to keep the connection alive.
         callback (reify MqttCallbackExtended
                    (messageArrived [_this topic message]
                      (when on-unhandled-message-fn
                        (on-unhandled-message-fn topic (message->metadata message) (.getPayload message))))
                    (connectionLost [_this reason]
                      (when on-connection-lost-fn
                        (on-connection-lost-fn reason)))
                    (connectComplete [_this reconnect serverURI]
                      (when on-connect-complete-fn
                        (on-connect-complete-fn client reconnect serverURI)))
                    (deliveryComplete [_this token]
                      (when on-delivery-complete-fn
                        (on-delivery-complete-fn token))))]
     (.setCallback client callback)
     (.waitForCompletion (.connect client (->connect-options opts)) connect-timeout-ms)
     client)))

(defn disconnect-and-close!
  "Disconnects from MQTT broker and releases all resources.

   This blocks the calling thread until the client is disconnected, or the timeout is reached.
   If the disconnection attempt times out, this throws Paho's MqttException with reason code
   WAIT_FOR_COMPLETION_TIMEOUT.

   Note that, by default, Paho has a \"quiesce timeout\" allowing outstanding work to complete
   before disconnecting. This timeout is 30 seconds."
  ([^IMqttAsyncClient client]
   (.waitForCompletion (.disconnect client))
   (.close client))
  ([^IMqttAsyncClient client timeout-ms]
   (.waitForCompletion (.disconnect client) timeout-ms)
   (.close client)))

(defn publish!
  "Publishes a message to a topic. Returns the `org.eclipse.paho.client.mqttv3.IMqttDeliveryToken`."
  (^IMqttDeliveryToken [client topic payload]
   (publish! client topic payload :at-least-once))
  (^IMqttDeliveryToken [client topic payload qos]
   (publish! client topic payload qos false))
  (^IMqttDeliveryToken [^IMqttAsyncClient client topic payload qos retained?]
   (.publish client ^String topic ^bytes (->byte-array payload) ^int (qos->int qos) ^boolean retained?)))

(defn subscribe!
  "Subscribes to one or multiple topics. Returns the `org.eclipse.paho.client.mqttv3.IMqttToken`.

   The provided handler function will be invoked with 3 arguments:

    * The topic that the message was received on,
    * An immutable map of message metadata, and
    * The byte array of message payload

   BEWARE: Don't use dots in topic names with RabbitMQ - see rabbitmq-mqtt/issues/58"
  (^IMqttToken [^IMqttAsyncClient client topic qos handler-fn]
   (subscribe! client {topic qos} handler-fn))
  (^IMqttToken [^IMqttAsyncClient client topics-and-qos handler-fn]
   ;; Both `keys` and `vals` return their respective sequences in the
   ;; same order as (seq topics-and-qos), so order should be preserved.
   (let [topics (keys topics-and-qos)
         qoss (map qos->int (vals topics-and-qos))
         message-listener (reify IMqttMessageListener
                            (messageArrived [_this topic message]
                              (handler-fn topic (message->metadata message) (.getPayload message))))
         message-listeners (into-array IMqttMessageListener (repeat (count topics) message-listener))]
     (.subscribe
       ^IMqttAsyncClient client
       ^"[Ljava.lang.String;" (into-array String topics)
       ^ints (int-array qoss)
       ^"[Lorg.eclipse.paho.client.mqttv3.IMqttMessageListener;" message-listeners))))
