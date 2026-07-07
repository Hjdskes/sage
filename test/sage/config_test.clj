(ns sage.config-test
  (:refer-clojure :exclude [get])
  (:require
    [clojure.java.io :as io]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [sage.config :as sut])
  (:import
    [clojure.lang ExceptionInfo]))

(defn- reset-config!
  "Resets the (private) *config atom to nil, so each test starts uninitialised."
  []
  (reset! @#'sut/*config nil))

(use-fixtures :each (fn [f] (reset-config!) (f)))

(deftest init!
  (testing "loads the :default profile from config.edn"
    (is (= {:mqtt-config {:uri "tcp://:1883", :username "sage", :password nil, :auto-reconnect true}}
           (sut/init! :default))))
  (testing "loads the :test profile from config.edn, overriding the previously loaded :default profile"
    (is (= {:mqtt-config {:uri "tcp://127.0.0.1:1883" :username nil :password nil :auto-reconnect true}}
           (sut/init! :test))))
  (testing "throws when the resource isn't on the classpath"
    (with-redefs [io/resource (constantly nil)]
      (let [e (try (sut/init! :test) (catch ExceptionInfo e e))]
        (is (= "Config not found on classpath" (ex-message e)))
        (is (= {:source "config.edn"} (ex-data e)))))))

(deftest get
  (testing "asserts that init! has been called"
    (is (thrown-with-msg? AssertionError #"init! has not been called" (sut/get :mqtt-config))))
  (testing "returns nil for a key not present in the config"
    (sut/init! :test)
    (is (nil? (sut/get :not-a-real-key))))
  (testing "returns the right value for a key present in the config"
    (sut/init! :test)
    (is (= {:uri "tcp://127.0.0.1:1883" :username nil :password nil :auto-reconnect true}
           (sut/get :mqtt-config)))))
