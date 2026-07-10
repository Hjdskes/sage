(ns sage.mqtt-test
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [sage.config :as config]
    [sage.mosquitto.fixture :as mosquitto.fixture]
    [sage.mqtt :as sut]
    [sage.mqtt.client :as mqtt.client])
  (:import
    [java.util.concurrent CountDownLatch TimeUnit]
    [org.eclipse.paho.client.mqttv3 IMqttAsyncClient]))

(set! *warn-on-reflection* true)

(use-fixtures :once (fn [f] (mosquitto.fixture/mosquitto f) (config/init! :test)))

(def ^:private wait-timeout-ms 1000)

(deftest ^:integration connects-and-closes
  (testing "connects to the configured broker"
    (with-open [conn (sut/start-system! (constantly nil))]
      (is (.isConnected ^IMqttAsyncClient @conn))))
  (testing "closing the returned closeable disconnects"
    (let [conn (sut/start-system! (constantly nil))]
      (.close conn)
      (is (not (.isConnected ^IMqttAsyncClient @conn))))))

(defn- instrument!
  "Instrument the MQTT handler and observe its behavior via handler-fn.

   Starts (and closes) the MQTT handler in the background, connected to the Mosquitto fixture.
   Publishes the given messages to the fixture's *conn*.

   This allows us to make assertions on the messages received and produced by the system under test."
  [handler-fn expected-count & topic+payloads]
  (let [done (CountDownLatch. expected-count)]
    (with-open [conn (sut/start-system! (partial handler-fn done))]
      (doseq [[topic payload] topic+payloads]
        (.waitForCompletion
          (mqtt.client/publish! mosquitto.fixture/*conn* topic payload) wait-timeout-ms))
      (.await done wait-timeout-ms TimeUnit/MILLISECONDS)
      {:conn @conn :connected? (.isConnected ^IMqttAsyncClient @conn)})))

(deftest ^:integration message-processing
  (testing "handler-fn is called with the live conn, topic, and parsed payload"
    (let [calls (atom [])
          {:keys [conn]} (instrument!
                           (fn [^CountDownLatch done c topic data]
                             (swap! calls conj [c topic data])
                             (.countDown done))
                           1
                           ["sage/test/mqtt" "{\"temperature\":18,\"ikea\":\"Trådfri\"}"])]
      (is (= [[conn "sage/test/mqtt" {:temperature 18 :ikea "Trådfri"}]] @calls))))
  (testing "doesn't call handler-fn for a legitimately-parsed but falsy payload"
    (let [calls (atom [])]
      (instrument!
        (fn [^CountDownLatch done c topic data]
          (swap! calls conj [c topic data])
          (.countDown done))
        0
        ["sage/test/mqtt" "false"]
        ["sage/test/mqtt" "null"])
      (is (empty? @calls))))
  (testing "receives messages on multiple topics"
    (let [topics (atom #{})]
      (instrument!
        (fn [^CountDownLatch done _c topic _data] (swap! topics conj topic) (.countDown done))
        2
        ["sage/test/a" "{}"]
        ["sage/test/b" "{}"])
      (is (= #{"sage/test/a" "sage/test/b"} @topics))))
  (testing "a malformed JSON message doesn't kill the connection; later valid messages still arrive"
    (let [calls (atom [])
          {:keys [connected?]} (instrument!
                                 (fn [^CountDownLatch done _c topic data]
                                   (swap! calls conj [topic data])
                                   (.countDown done))
                                 1
                                 ["sage/test/bad" "not json"]
                                 ["sage/test/bad" ""]
                                 ["sage/test/bad" nil] ; empty byte array
                                 ["sage/test/good" "{\"temperature\":18}"])]
      (is connected?)
      (is (= [["sage/test/good" {:temperature 18}]] @calls))))
  (testing "an exception in handler-fn doesn't kill the connection; later messages still arrive"
    (let [calls (atom [])
          {:keys [connected?]} (instrument!
                                 (fn [^CountDownLatch done _c topic data]
                                   (if (= "sage/test/throws" topic)
                                     (throw (ex-info "boom" {}))
                                     (do
                                       (swap! calls conj [topic data])
                                       (.countDown done))))
                                 1
                                 ["sage/test/throws" "{}"]
                                 ["sage/test/ok" "{}"])]
      (is connected?)
      (is (= [["sage/test/ok" {}]] @calls)))))
