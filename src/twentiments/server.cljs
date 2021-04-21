(ns twentiments.server
  (:require
    [clast.web :as web]
    [clast.util :as util]
    ["login-with-twitter" :as login-with-twitter]
    [cljs.core.async :refer (go <!) :as async]
    [cljs.core.async.interop :refer-macros [<p!]]))

(defn twitter-login-done [req res]
  (let [tw (login-with-twitter.
             #js {:consumerKey (util/env "TWITTER_API_KEY")
                  :consumerSecret (util/env "TWITTER_API_SECRET")
                  :callbackUrl (util/build-absolute-uri req "/twitter-callback")})]
    (.callback tw 
               #js {:oauth_token (aget req "query" "oauth_token")
                    :oauth_verifier (aget req "query" "oauth_verifier")}
               (aget req.session "tokenSecret")
               (fn [err user]
                 (if err
                   (do
                     (js/console.error err)
                     (.json res (util/error-to-json err)))
                   (do
                     (js-delete req.session "tokenSecret")
                     (aset req.session "user" user)
                     (js/console.log "user:" user)
                     (.redirect res "/")))))))

(defn twitter-login [req res]
  (if (aget req.session "user")
    (.redirect res "/")
    (let [tw (login-with-twitter.
               #js {:consumerKey (util/env "TWITTER_API_KEY")
                    :consumerSecret (util/env "TWITTER_API_SECRET")
                    :callbackUrl (util/build-absolute-uri req "/twitter-callback")})]
      (.login tw
              (fn [err token-secret url]
                (if err
                  (do
                    (js/console.error err)
                    (.json res (util/error-to-json err)))
                  (do
                    (aset req.session "tokenSecret" token-secret)
                    (.redirect res url))))))))

(defn twitter-logout [req res]
  (js-delete req.session "user")
  (.redirect res "/"))

(defn setup-routes [app]
  (web/reset-routes app)
  (.get app "/login" twitter-login)
  (.get app "/logout" twitter-logout)
  (.get app "/twitter-callback" twitter-login-done)
  ;(.use app authenticate)
  ;(.get app "/data" get-data)
  ;(.post app "/save" set-data)
  )

(defn main! []
  (go
    (let [app (web/create)
          [host port] (<p! (web/serve app))]
      (util/reloader (partial #'setup-routes app))
      (setup-routes app)
      (println "main!"))))
