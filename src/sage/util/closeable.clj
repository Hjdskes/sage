(ns sage.util.closeable
  "A lightweight try-with-resources pattern implementation.

   Implements the solution described in https://medium.com/@maciekszajna/reloaded-workflow-out-of-the-box-be6b5f38ea98."
  (:import [clojure.lang IDeref]
           [java.lang AutoCloseable]))

(set! *warn-on-reflection* true)

(defn closeable
  "Takes an already constructed value and a destructor function to which the value will be applied.
   Implements java.lang.AutoCloseable and clojure.lang.IDeref to retrieve the value.

   Use the 1-arity version for immutable data that doesn't need destruction that you want to include
   in the with-open binding."
  ^AutoCloseable
  ([value]
   (closeable value identity))
  ([value close-fn]
   (reify
     IDeref
     (deref [_] value)
     AutoCloseable
     (close [_this]
       (close-fn value)))))
