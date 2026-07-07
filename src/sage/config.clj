(ns sage.config
  (:refer-clojure :exclude [get key resolve])
  (:require
    [aero.core :as aero]
    [clojure.java.io :as io]))

;; TODO: some kind of spec validation on config?

(def ^:private *config
  (atom nil))

(defn- resolve
  [source]
  (or (io/resource source)
      (throw (ex-info "Config not found on classpath" {:source source}))))

(defn init!
  "Load and store the config for the given profile.

   Must be called once at startup before calling any other function that works
   on the config, such as `get` below.

   Calling this function again replaces the config. This is useful in the REPL to
   reload a changed config file, or in tests to switch profiles.

   Throws if the file `config.edn` is not on the class path."
  [profile]
  (reset! *config (aero/read-config (resolve "config.edn") {:profile profile})))

(defn get
  "Returns the value for the given key from the initialised config."
  [key]
  (assert @*config "sage.config/init! has not been called")
  (clojure.core/get @*config key))
