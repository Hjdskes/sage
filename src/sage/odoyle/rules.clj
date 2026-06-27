(ns sage.odoyle.rules
  (:require
    [odoyle.rules :as o]
    [sage.odoyle.facts :as facts]))

;; TODO: structure instead per type of rule, e.g. lighting & climate rules?
;; TODO: query current hardware/sensor state on startup?

(def rules
  "Generic rules that don't fit a specific scenario."
  (o/ruleset
    {::get-facts ; For debugging: query all [e a v] tuples in the session.
     [:what [entity attribute value]]

     ::get-commands ; Query all commands [device-id ::facts/command command] in the session.
     [:what [device-id ::facts/command command]]}))

(def all-rules
  "A collection of all rules, to be added into the O'Doyle session."
  (into
    rules))
