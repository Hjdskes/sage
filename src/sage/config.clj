(ns sage.config
  (:refer-clojure :exclude [key])
  (:require
    [aero.core :as aero]
    [clojure.java.io :as io]))

;; TODO: some kind of spec validation on config?

(def ^:private load-config
  (memoize
    (fn [profile]
      (aero/read-config (io/resource "config.edn") {:profile profile}))))

;; TODO: get rid of having to pass `profile`.
(defn get-config-key
  "Read the key from the config, which is memoized and lazily loaded."
  ([key]
   (get-config-key key :default))
  ([key profile]
   (get (load-config profile) key)))
