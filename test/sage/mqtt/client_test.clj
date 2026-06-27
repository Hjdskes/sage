(ns sage.mqtt.client-test
  "Test Sage's MQTT client.

   This depends on Mosquitto being available on the PATH, which should be the case if
   this test is run from within the provided Nix flake.

   You can run `mosquitto_sub -t \"sage/test/#\"` to observe the published test messages."
  (:require
    [clojure.test :refer [deftest is use-fixtures]]
    [sage.mosquitto.fixture :as mosquitto.fixture]
    [sage.mqtt.client :as mqtt])
  (:import
    [java.util.concurrent CountDownLatch TimeUnit]
    [org.eclipse.paho.client.mqttv3 IMqttAsyncClient]))

(set! *warn-on-reflection* true)

(use-fixtures :once (fn [f] (mosquitto.fixture/ensure-mosquitto) (f)))

(def ^:private wait-timeout-ms 50)

(defn- connect
  ^IMqttAsyncClient []
  (mqtt/connect! mosquitto.fixture/local-uri {:opts {:max-inflight 1000}}))

(defn- subscribe
  [conn topics-and-qos f]
  (.waitForCompletion (mqtt/subscribe! conn topics-and-qos f) 1000))

(deftest connection
  (let [conn (connect)]
    (is (.isConnected conn))
    (mqtt/disconnect-and-close! conn wait-timeout-ms)
    (is (not (.isConnected conn)))))

(deftest connection-with-handlers
  (let [done (CountDownLatch. 3)
        calls-log (atom {})
        log-call (fn [k]
                   (swap! calls-log assoc k true)
                   (.countDown done))
        conn (mqtt/connect! mosquitto.fixture/local-uri
                            {:on-connect-complete (constantly (log-call :on-connect-complete))
                             ; Cannot be tested unless we can simulate connection loss.
                             ;:on-connection-lost (constantly (log-call :on-connection-lost))
                             :on-delivery-complete (constantly (log-call :on-delivery-complete))
                             :on-unhandled-message (constantly (log-call :on-unhandled-message))})]
    ; Subscribe to a topic but do not provide any handler so that the default one will be invoked.
    (.subscribe conn "sage/test" 0)
    (.publish conn "sage/test" (.getBytes "hello" "UTF-8") 1 false)
    (.await done 100 TimeUnit/MILLISECONDS)
    (is (= {:on-connect-complete true
            :on-delivery-complete true
            :on-unhandled-message true}
           @calls-log))
    (mqtt/disconnect-and-close! conn)))

(deftest publish-empty-messages
  (let [conn (connect)]
    (dotimes [_ 50]
      (let [delivery-token (mqtt/publish! conn "sage/test" nil)]
        (.waitForCompletion delivery-token wait-timeout-ms)
        (is (.isComplete delivery-token))))
    (mqtt/disconnect-and-close! conn)))

(deftest publish-messages
  (let [conn (connect)]
    (dotimes [n 50]
      (let [delivery-token (mqtt/publish! conn "sage/test" (str "hello " n))]
        (.waitForCompletion delivery-token wait-timeout-ms)
        (is (.isComplete delivery-token))))
    (mqtt/disconnect-and-close! conn)))

(deftest basic-subscription
  (let [n 100
        conn (connect)
        latch (CountDownLatch. n)]
    (subscribe conn {"sage/test" :at-most-once} (fn [_topic _meta _payload] (.countDown latch)))
    (dotimes [_ n]
      (.publish conn "sage/test" (.getBytes "hello" "UTF-8") 1 false))
    (.await latch 100 TimeUnit/MILLISECONDS)
    (is (= 0 (.getCount latch)))
    (mqtt/disconnect-and-close! conn)))

(deftest subscription-with-multiple-consumers
  (let [n 100
        conn1 (connect)
        conn2 (connect)
        latch (CountDownLatch. (* 2 n))
        f (fn [_topic _meta _payload] (.countDown latch))
        m {"sage/test" :at-most-once}]
    (subscribe conn1 m f)
    (subscribe conn2 m f)
    (dotimes [_ n]
      (.publish conn1 "sage/test" (.getBytes "hello" "UTF-8") 1 false))
    (.await latch 100 TimeUnit/MILLISECONDS)
    (is (= 0 (.getCount latch)))
    (mqtt/disconnect-and-close! conn1)
    (mqtt/disconnect-and-close! conn2)))

(deftest multi-topic-subscription
  (let [n 50
        m 60
        conn (connect)
        latch (CountDownLatch. (+ n m))]
    (subscribe conn {"sage/test/1" :at-most-once
                     "sage/test/2" :at-most-once}
               (fn [_topic _meta _payload] (.countDown latch)))
    (dotimes [_ n]
      (.publish conn "sage/test/1" (.getBytes "hello" "UTF-8") 1 false))
    (dotimes [_ m]
      (.publish conn "sage/test/2" (.getBytes "hello" "UTF-8") 1 false))
    (.await latch 100 TimeUnit/MILLISECONDS)
    (is (= 0 (.getCount latch)))
    (mqtt/disconnect-and-close! conn)))

(deftest different-subscriptions-with-different-handlers
  (let [n 50
        m 60
        conn (connect)
        latch1 (CountDownLatch. n)
        latch2 (CountDownLatch. m)]
    (subscribe conn {"sage/test/1" :at-most-once} (fn [_topic _meta _payload] (.countDown latch1)))
    (subscribe conn {"sage/test/2" :at-most-once} (fn [_topic _meta _payload] (.countDown latch2)))
    (dotimes [_ n]
      (.publish conn "sage/test/1" (.getBytes "hello" "UTF-8") 1 false))
    (dotimes [_ m]
      (.publish conn "sage/test/2" (.getBytes "hello" "UTF-8") 1 false))
    (.await latch1 100 TimeUnit/MILLISECONDS)
    (.await latch2 100 TimeUnit/MILLISECONDS)
    (is (= [0 0] [(.getCount latch1) (.getCount latch2)]))
    (mqtt/disconnect-and-close! conn)))

(deftest publish-from-consumer
  (let [n 100
        conn (connect)
        latch (CountDownLatch. n)]
    (subscribe conn {"sage/test/1" :at-most-once}
               (fn [_topic _meta payload] (mqtt/publish! conn "sage/test/2" payload)))
    (subscribe conn {"sage/test/2" :at-most-once}
               (fn [_topic _meta _payload] (.countDown latch)))
    (dotimes [_ n]
      (.publish conn "sage/test/1" (.getBytes "hello" "UTF-8") 1 false))
    (.await latch 100 TimeUnit/MILLISECONDS)
    (is (= 0 (.getCount latch)))
    (mqtt/disconnect-and-close! conn)))

(comment
  (require '[clj-async-profiler.core :as prof])
  (let [conn (connect)]
    (prof/profile
      (subscribe conn {"sage/test/1" :at-most-once}
                 (fn [_topic _meta payload] (mqtt/publish! conn "sage/test/2" payload)))
      (.publish conn "sage/test/1" (.getBytes "hello" "UTF-8") 1 false))
    (mqtt/disconnect-and-close! conn))
  (prof/serve-ui 8080))
