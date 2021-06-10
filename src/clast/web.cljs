(ns clast.web
  (:require
    [clast.util :refer [env]]
    [applied-science.js-interop :as j]
    ["path" :as path]
    ["keyv" :as Keyv]
    ["process" :as process]
    ["express" :as express]
    ["events" :as EventEmitter]
    ["cookie-parser" :as cookies]
    ["body-parser" :as body-parser]
    ["serve-static" :as serve-static]
    ["express-session" :as session]
    ["morgan" :as morgan]
    ["rotating-file-stream" :as rfs]
    [cljs.core.async :refer (go <!) :as async]
    [cljs.core.async.interop :refer-macros [<p!]]))

(def database-url (env "DATABASE" "sqlite://./database.sqlite"))

(defn create-store [kv]
  (let [e (session/Store.)]
    (aset e "destroy" (fn [sid callback]
                        (go (<p! (j/call kv :destroy sid))
                            (callback))))
    (aset e "get" (fn [sid callback]
                    (go (callback
                          nil
                          (<p! (j/call kv :get sid))))))
    (aset e "set" (fn [sid session callback]
                    (go (<p! (j/call kv :set sid session))
                        (callback))))
    (aset e "clear" (fn [callback]
                      (go (<p! (js/call kv :clear))
                          (callback))))
    e))

(defn add-default-middleware [app]
  ; set up logging
  (let [logs (str js/__dirname "/logs")
        access-log (.createStream rfs "access.log" #js {:interval "7d" :path logs})
        kv-session (Keyv. database-url #js {:namespace "session"})
        store (create-store kv-session)]
    (.use app (morgan "combined" #js {:stream access-log}))
    ; set up sessions table
    (.use app (session #js {:secret (env "SECRET" "DEVMODE")
                            :saveUninitialized false
                            :resave true
                            :cookie #js {:secure "auto"
                                         :httpOnly true
                                         ; 10 years
                                         :maxAge (* 10 365 24 60 60 1000)}
                            :store store})))
  ; configure sane server defaults
  (.set app "trust proxy" "loopback")
  (.use app (cookies))
  ; json body parser
  (.use app (.json body-parser #js {:limit "10mb" :extended true :parameterLimit 1000}))
  app)

(defn static-folder [app route folder]
  (.use app route (serve-static (path/join js/__dirname folder)))
  app)

(defn reset-routes [app]
  (let [router app._router]
    (when router
      (js/console.error (str "Deleting " (aget app "_router" "stack" "length") " routes"))
      (set! app._router nil))
    (add-default-middleware app)))

(defn create []
  (let [kv (Keyv. database-url)]
    (-> (express)
        (j/assoc! :kv kv)
        (j/assoc! :db (aget kv "opts" "store"))
        (add-default-middleware))))

(defn serve [app]
  (let [host (env "BIND_ADDRESS" "127.0.0.1")
        port (env "PORT" "8000")
        srv (.bind (aget app "listen") app port host)]
    (js/Promise.
      (fn [res err]
        (srv (fn []
               (js/console.log "Web server started: " (str "http://" host ":" port))
               (res #js [host port])))))))

