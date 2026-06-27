(ns sage.config
  (:refer-clojure :exclude [key])
  (:require
    [aero.core :as aero]
    [clojure.java.io :as io]))

;; TODO: some kind of spec validation on config?

(def ^:private *config
  (atom nil))

(defn init!
  "Load and store the config for the given profile.

   Must be called once at startup before calling any other function that works
   on the config, such as get-key.

   Calling this function again replaces the config (useful in tests to switch profiles)."
  [profile]
  (reset! *config (aero/read-config (io/resource "config.edn") {:profile profile})))

(defn get-key
  "Returns the value for the given key from the initialised config."
  [key]
  (clojure.core/get @*config key))
