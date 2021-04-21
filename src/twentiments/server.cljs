(ns twentiments.server
  (:require
    [clast.web :as web]
    [clast.util :as util]
    ["login-with-twitter" :as login-with-twitter]
    ["twitter-v2" :as twitter-v2]
    [shadow.resource :as rc]
    [cljs.core.async :refer (go <!) :as async]
    [cljs.core.async.interop :refer-macros [<p!]]))

(defn twitter-sign-in [req]
  (login-with-twitter.
    #js {:consumerKey (util/env "TWITTER_API_KEY")
         :consumerSecret (util/env "TWITTER_API_SECRET")
         :callbackUrl (util/build-absolute-uri req "/twitter-callback")}))

(defn twitter [user]
  (when user
    (twitter-v2. #js {:consumer_key (util/env "TWITTER_API_KEY")
                   :consumer_secret (util/env "TWITTER_API_SECRET")
                   :access_token_key (aget user "userToken")
                   :access_token_secret (aget user "userTokenSecret")})))

(defn twitter-login-done [req res]
  (let [tw (twitter-sign-in req)]
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
                     (.redirect res "/")))))))

(defn twitter-login [req res]
  (if (aget req.session "user")
    (.redirect res "/")
    (let [tw (twitter-sign-in req)]
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

(defn get-user-profile [tw user-id]
  (->
    (.get tw "users" (clj->js {:ids user-id :user.fields "id,name,username,url,profile_image_url"}))
    (.then (fn [data] (-> data (aget "data") first)))
    (.catch (fn [err] (js/console.error err) nil))))

(defn serve-homepage [req res]
  (js/console.log "homepage")
  (go
    (let [template (rc/inline "index.html")
          user (aget req.session "user")]
      (if user
        (let [user-id (when user (aget user "userId"))
              tw (twitter user)
              user-data (when tw (<p! (get-user-profile tw user-id)))]
          (js/console.log "user:" user user-id user-data)
          (.send res template))
        (.send res template)))))

(defn setup-routes [app]
  (web/reset-routes app)
  (.get app "/" serve-homepage)
  (web/static-folder app "/" "public")
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
