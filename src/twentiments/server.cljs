(ns twentiments.server
  (:require
    [clast.web :as web]
    [clast.util :as util]
    [clast.db :as db]
    ["login-with-twitter" :as login-with-twitter]
    ["twitter-api-v2/dist" :refer [TwitterApi]]
    ["motionless" :as motionless]
    ["wink-sentiment" :as sentiment]
    [shadow.resource :as rc]
    [applied-science.js-interop :as j]
    [cljs.core.async :refer (go <!) :as async]
    [cljs.core.async.interop :refer-macros [<p!]]))

(util/bind-console-log-to-file)

(def twitter-key (or (util/env "TWITTER_API_KEY") (util/bail "TWITTER_API_KEY not set.")))
(def twitter-secret (or (util/env "TWITTER_API_SECRET") (util/bail "TWITTER_API_SECRET not set.")))
(def twitter-environment (or (util/env "TWITTER_ENVIRONMENT_NAME")
                             (util/bail "TWITTER_ENVIRONMENT_NAME (full archive) not set.
                                        <https://developer.twitter.com/en/account/environments>")))

(defn return-json-error [res err code]
  (do
    (js/console.error err)
    (-> res
        (.status code)
        (.json res (util/error-to-json err)))))

(defn twitter-sign-in [req]
  (login-with-twitter.
    #js {:consumerKey twitter-key
         :consumerSecret twitter-secret
         :callbackUrl (util/build-absolute-uri req "/twitter-callback")}))

(defn twitter [user]
  (when user
    (aget
      (TwitterApi. #js {:appKey twitter-key
                        :appSecret twitter-secret
                        :accessToken (aget user "userToken")
                        :accessSecret (aget user "userTokenSecret")})
      "readOnly")))

(defn twitter-login-done [req res]
  (let [tw (twitter-sign-in req)
        kv (db/kv "login/last")
        now (.toISOString (js/Date.))]
    (.callback tw 
               #js {:oauth_token (aget req "query" "oauth_token")
                    :oauth_verifier (aget req "query" "oauth_verifier")}
               (j/get-in req [:session :tokenSecret])
               (fn [err user]
                 (if err
                   (return-json-error res err 404)
                   (do
                     (when-let [session (j/get req "session")]
                       (js-delete session "tokenSecret"))
                     (go
                       (j/assoc-in! req [:session :user] user)
                       (<p! (.set kv (aget user "userId") (clj->js {:id (aget user "userId") :username (aget user "userName") :t now})))
                       (.redirect res "/"))))))))

(defn twitter-login [req res]
  (if (j/get-in req [:session :user])
    (.redirect res "/")
    (let [tw (twitter-sign-in req)]
      (.login tw
              (fn [err token-secret url]
                (if err
                  (return-json-error res err 404)
                  (do
                    (j/assoc-in! req [:session :tokenSecret] token-secret)
                    (.redirect res url))))))))

(defn twitter-logout [req res]
  (when-let [session (j/get req "session")]
    (js-delete session "user"))
  (.redirect res "/"))

(defn get-user-profile [tw user-id]
  (->
    (.get (aget tw "v2") "users" (clj->js {:ids user-id :user.fields "id,name,username,url,profile_image_url"}))
    (.then (fn [data] (-> data (aget "data") first)))
    (.catch (fn [err]
              (js/console.error err)
              (util/error-to-json err)))))

(defn strip-slash [req res n]
  (let [path (aget req "path")
        url (aget req "url")]
    (if (and
          (= (last path) "/")
          (> (aget path "length") 1))
      (.redirect res 301 (str (.slice path 0 -1) (.slice url (aget path "length"))))
      (n))))

(defn serve-homepage [req res]
  (let [template (rc/inline "index.html")
        user (j/get-in req [:session :user])]
    (if user
      (go
        (let [user-id (aget user "userId")
              tw (twitter user)
              user-profile (aget user "profile")
              user-profile (if user-profile user-profile (<p! (get-user-profile tw user-id)))
              dom (motionless/dom template)
              app (.$ dom "main")
              nav (.$ dom "nav")
              signout-link (.h dom "a" #js {:href "/logout"
                                            :role "link"
                                            :aria-label "Sign out"
                                            :className "ui-section-header--nav-link"}
                               "Sign out")
              profile-image (.h dom "div" #js {:className "user-profile"}
                                (.h dom "a" (clj->js {:href (str "https://twitter.com/" (aget user-profile "username"))
                                                      :target "_BLANK"})
                                    (.h dom "img" (clj->js {:src (aget user-profile "profile_image_url")}))))]
          (aset user "profile" user-profile)
          (aset app "innerHTML" "")
          (.appendChild app (.h dom "div" #js {:id "loading"} (.h dom "div" #js {:className "spinner spin"})))
          (.after app (.h dom "script" #js {:src "js/main.js"}))
          (aset nav "innerHTML" "")
          (.appendChild nav signout-link)
          (.appendChild nav profile-image)
          ; (test-search tw)
          (.setAttribute app "data-user" (-> user-profile js/JSON.stringify util/btoa))
          (.send res (.render dom))))
      (.send res template))))

(defn search [req res]
  (let [user (j/get-in req [:session :user])
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
      (.catch 
        (fn [err] (return-json-error res (aget err "data") 403))))))

(defn search-old [req res]
  (let [user (j/get-in req [:session :user])
        tw (twitter user)]
    ; https://developer.twitter.com/en/docs/twitter-api/premium/search-api/overview
    (-> (.get (aget tw "v1") (str "tweets/search/fullarchive/" twitter-environment ".json")
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
          (fn [err] (return-json-error res (aget err "data") 403))))))
(defn soon [req res]
  (let [template (rc/inline "index.html")
        dom (motionless/dom template)
        app (.$ dom "main")]
    (aset app "innerHTML" "")
    (.appendChild app (.h dom "section" #js {:className "ui-section-pricing"} (.h dom "div" #js {:className "ui-layout-container"} (.h dom "h2" "Soon."))))
    (.send res (.render dom))))

(defn setup-routes [app]
  (web/reset-routes app)
  (.get app "/" serve-homepage)
  (web/static-folder app "/" (if (util/env "NGINX_SERVER_NAME") "build" "public"))
  (.use app strip-slash)
  ;(.get app "/login" soon)
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
