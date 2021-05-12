(ns twentiments.server
  (:require
    [clast.web :as web]
    [clast.util :as util]
    ["login-with-twitter" :as login-with-twitter]
    ["twitter-api-v2/dist" :refer [TwitterApi]]
    ["motionless" :as motionless]
    ["wink-sentiment" :as sentiment]
    [shadow.resource :as rc]
    [applied-science.js-interop :as j]
    [cljs.core.async :refer (go <!) :as async]
    [cljs.core.async.interop :refer-macros [<p!]]))

(defn twitter-sign-in [req]
  (login-with-twitter.
    #js {:consumerKey (util/env "TWITTER_API_KEY")
         :consumerSecret (util/env "TWITTER_API_SECRET")
         :callbackUrl (util/build-absolute-uri req "/twitter-callback")}))

(defn twitter [user]
  (when user
    (aget
      (TwitterApi. #js {:appKey (util/env "TWITTER_API_KEY")
                        :appSecret (util/env "TWITTER_API_SECRET")
                        :accessToken (aget user "userToken")
                        :accessSecret (aget user "userTokenSecret")})
      "readOnly")))

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
    (.get (aget tw "v2") "users" (clj->js {:ids user-id :user.fields "id,name,username,url,profile_image_url"}))
    (.then (fn [data] (-> data (aget "data") first)))
    (.catch (fn [err]
              (js/console.error err)
              (util/error-to-json err)))))

(defn serve-homepage [req res]
  (let [template (rc/inline "index.html")
        user (aget req.session "user")]
    (if user
      (go
        (let [user-id (aget user "userId")
              tw (twitter user)
              user-profile (aget user "profile")
              user-profile (if user-profile user-profile (<p! (get-user-profile tw user-id)))
              dom (motionless/dom template)
              app (.$ dom "#app")]
          (aset user "profile" user-profile)
          ; (test-search tw)
          (.setAttribute app "data-user" (-> user-profile js/JSON.stringify util/btoa))
          (.send res (.render dom))))
      (.send res template))))

(defn search [req res]
  (let [user (aget req.session "user")
        tw (twitter user)]
    (js/console.log (aget req.body "q"))
    ; https://developer.twitter.com/en/docs/twitter-api/tweets/search/quick-start/recent-search
    (->
      (.get (aget tw "v2") "tweets/search/recent"
            (clj->js {:query (aget req.body "q") ; (str "\"" (aget req.body "q") "\"")
                      :max_results 100
                      :expansions "referenced_tweets.id,author_id"
                      :tweet.fields "created_at,public_metrics,author_id"
                      :user.fields "username,name,profile_image_url,url,public_metrics"}))
      (.then
        (fn [data]
          (js/console.log "data" data)
          (let [tweets (aget data "data")]
            (when tweets
              (.map tweets
                    (fn [d]
                      (aset d "sentiment"
                            (aget (sentiment (aget d "text")) "score")))))
            (.json res data))))
      (.catch (fn [err]
                (js/console.error err)
                (.json res (util/error-to-json (aget err "data"))))))))

(defn search-old [req res]
  (let [user (aget req.session "user")
        tw (twitter user)]
    ; https://developer.twitter.com/en/docs/twitter-api/premium/search-api/overview
    (-> (.get (aget tw "v1") "tweets/search/fullarchive/devfullarchive.json"
              #js {:query (aget req.body "q")
                   :maxResults 100})
        (.then
          (fn [data]
            (let [tweets (aget data "results")]
              (when tweets
                (.map tweets
                      (fn [d]
                        (aset d "sentiment"
                              (aget (sentiment (aget d "text")) "score"))))))
            (.json res data)))
        (.catch
          (fn [err]
            (js/console.error err)
            (.json res (util/error-to-json (aget err "data"))))))))

(defn setup-routes [app]
  (web/reset-routes app)
  (.get app "/" serve-homepage)
  (web/static-folder app "/" "public")
  (.get app "/login" twitter-login)
  (.get app "/logout" twitter-logout)
  (.get app "/twitter-callback" twitter-login-done)
  (j/call app :post "/search" search-old)
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


; api calls i didn't use

    #_ (-> (.get (aget tw "v1") "statuses/user_timeline.json" #js {:user_id (aget user "id")})
           (.then
             (fn [data]
               (js/console.log data))))

    #_ (-> (.get (aget tw "v1") "search/tweets.json" #js {:q (aget req.body "q")
                                                       :count 100
                                                       :result_type "recent"
                                                       :include_entities true})
        (.then
          (fn [data]
            (js/console.log data))))



    #_ (->
         (.get (aget tw "v1") "tweets/search"
               (clj->js {:q (aget req.body "q")
                         :result_type "recent"}))
         (.then
           (fn [data]
             (js/console.log "data" data)

             (.json res data)

             #_ (let [tweets (aget data "data")]
                  (when tweets
                    (.map tweets
                          (fn [d]
                            (aset d "sentiment"
                                  (aget (sentiment (aget d "text")) "score")))))
                  )))
         (.catch (fn [err]
                   (js/console.error err)
                   (.json res (util/error-to-json (aget err "data"))))))
