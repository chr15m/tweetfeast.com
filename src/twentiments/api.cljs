(ns twentiments.api
  (:require
    ["login-with-twitter" :as login-with-twitter]
    ["twitter-api-v2/dist" :refer [TwitterApi]]
    [applied-science.js-interop :as j]
    [sitefox.db :as db]
    [sitefox.web :as web]
    [sitefox.util :refer [env-required error-to-json]]))

(def twitter-key (env-required "TWITTER_API_KEY"))
(def twitter-secret (env-required "TWITTER_API_SECRET"))
(def twitter-environment (env-required "TWITTER_ENVIRONMENT_NAME"
                                       "TWITTER_ENVIRONMENT_NAME (full archive) not set.
                                       <https://developer.twitter.com/en/account/environments>"))

; *** useful functions *** ;

(defn rnd-id [] (.substr (str (random-uuid)) 0 8))

(defn log-event [event-type id user & [event-data]]
  (let [kv (db/kv event-type)
        now (.toISOString (js/Date.))]
    (.set kv id
          (clj->js (merge (or event-data {})
                          {:id (aget user "userId")
                           :username (aget user "userName")
                           :t now})))))

(defn return-json-error [res err code]
  (js/console.error err)
  (-> res
      (.status code)
      (.json (error-to-json err))))

(defn twitter [user]
  (when user
    (aget
      (TwitterApi. #js {:appKey twitter-key
                        :appSecret twitter-secret
                        :accessToken (aget user "userToken")
                        :accessSecret (aget user "userTokenSecret")})
      "readOnly")))

; *** twitter API interaction *** ;

(defn twitter-sign-in [req]
  (login-with-twitter.
    #js {:consumerKey twitter-key
         :consumerSecret twitter-secret
         :callbackUrl (web/build-absolute-uri req "/twitter-callback")}))

(defn twitter-login-done [req res]
  (let [tw (twitter-sign-in req)]
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
                     (j/assoc-in! req [:session :user] user)
                     (log-event "last/login" (aget user "userId") user)
                     (log-event "event/login" (rnd-id) user)
                     (.redirect res "/")))))))

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

