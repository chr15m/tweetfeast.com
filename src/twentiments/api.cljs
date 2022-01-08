(ns twentiments.api
  (:require
    ["login-with-twitter" :as login-with-twitter]
    ["twitter-api-v2/dist" :refer [TwitterApi]]
    [applied-science.js-interop :as j]
    [promesa.core :as p]
    [sitefox.db :as db]
    [sitefox.web :as web]
    [sitefox.util :refer [env-required error-to-json]]))

(def twitter-key (env-required "TWITTER_API_KEY"))
(def twitter-secret (env-required "TWITTER_API_SECRET"))
(def twitter-environment (env-required "TWITTER_ENVIRONMENT_NAME"
                                       "TWITTER_ENVIRONMENT_NAME (full archive) not set.
                                       <https://developer.twitter.com/en/account/environments>"))

(def default-app-url "/exporter")

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

(defn get-user-profile [tw user-id]
  (->
    (.get (aget tw "v2") "users" (clj->js {:ids user-id :user.fields "id,name,username,url,profile_image_url"}))
    (.then (fn [data] (-> data (aget "data") first)))
    (.catch (fn [err]
              (js/console.error err)
              (error-to-json err)))))

(defn twitter-sign-in [req]
  (let [next-url (aget req "query" "next")
        callback-url (web/build-absolute-uri req (str "/twitter-callback" (when next-url (str "?next=" next-url))))]
    (login-with-twitter.
      #js {:consumerKey twitter-key
           :consumerSecret twitter-secret
           :callbackUrl callback-url})))

(defn twitter-login-done [req res]
  (let [tw (twitter-sign-in req)
        next-url (j/get-in req [:query :next])]
    (.callback tw 
               #js {:oauth_token (aget req "query" "oauth_token")
                    :oauth_verifier (aget req "query" "oauth_verifier")}
               (j/get-in req [:session :tokenSecret])
               (fn [err user]
                 (if err
                   (return-json-error res err 404)
                   (p/let [twitter-instance (twitter user)
                           user-id (aget user "userId")
                           profile (get-user-profile twitter-instance user-id)]
                     (when-let [session (j/get req "session")]
                       (js-delete session "tokenSecret"))
                     (aset user "profile" profile)
                     (j/assoc-in! req [:session :user] user)
                     (log-event "last/login" user-id user)
                     (log-event "event/login" (rnd-id) user)
                     (.redirect res (or next-url default-app-url))))))))

(defn twitter-login [req res]
  (let [next-url (j/get-in req [:query :next])]
    (if (j/get-in req [:session :user])
      (.redirect res (or next-url default-app-url))
      (let [tw (twitter-sign-in req)]
        (.login tw
                (fn [err token-secret url]
                  (if err
                    (return-json-error res err 404)
                    (do
                      (j/assoc-in! req [:session :tokenSecret] token-secret)
                      (.redirect res url)))))))))

(defn twitter-logout [req res]
  (when-let [session (j/get req "session")]
    (js-delete session "user"))
  (.redirect res "/"))
