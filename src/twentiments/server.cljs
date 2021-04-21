(ns twentiments.server
  (:require
    [clast.web :as web]
    [clast.util :as util]
    [cljs.core.async :refer (go <!) :as async]
    [cljs.core.async.interop :refer-macros [<p!]]))

(defn setup-routes [app]
  (web/reset-routes app)
  ;(.use app authenticate)
  ;(.get app "/data" get-data)
  ;(.post app "/save" set-data)
  )

(defn main! []
  (go
    (let [app (web/create)
          [host port] (<p! (web/serve app))]
      (util/reloader (partial setup-routes app))
      (println "Server started on http://" host ":" port))))
