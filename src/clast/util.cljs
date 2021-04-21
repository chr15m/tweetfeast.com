(ns clast.util)

(defn bail [msg]
  (js/console.error msg)
  (js/console.error "Server exit.")
  (js/process.exit 1))

(defn env [k & [default]]
  (or (aget js/process.env k) default))

(defn reloader [app]
  ; not yet
  )
