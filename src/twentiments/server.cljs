(ns twentiments.server
  (:require
    ["fs" :as fs]
    [clast.util :as util]
    [sitefox.db :as db]
    [sitefox.web :as web]
    [sitefox.util :refer [error-to-json]]
    [reagent.dom.server :refer [render-to-static-markup]]
    ["login-with-twitter" :as login-with-twitter]
    ["twitter-api-v2/dist" :refer [TwitterApi]]
    ["motionless" :as motionless]
    ["wink-sentiment" :as sentiment]
    ["marked" :as marked]
    [shadow.resource :as rc]
    [promesa.core :as p]
    [applied-science.js-interop :as j]
    [cljs.core.async :refer (go) :as async]
    [cljs.core.async.interop :refer-macros [<p!]]))

(def r render-to-static-markup)

(util/bind-console-log-to-file)

(def twitter-key (or (util/env "TWITTER_API_KEY") (util/bail "TWITTER_API_KEY not set.")))
(def twitter-secret (or (util/env "TWITTER_API_SECRET") (util/bail "TWITTER_API_SECRET not set.")))
(def twitter-environment (or (util/env "TWITTER_ENVIRONMENT_NAME")
                             (util/bail "TWITTER_ENVIRONMENT_NAME (full archive) not set.
                                        <https://developer.twitter.com/en/account/environments>")))

(def article-list
  (into {}
        (-> (fs/readdirSync "src/content")
            (.filter #(.endsWith % ".md"))
            (.map (fn [f]
                    (let [f (.replace f ".md" "")
                          content (-> (str "src/content/" f ".md")
                                      (fs/readFileSync)
                                      (.toString))
                          description (-> content
                                          (.split "\n")
                                          (.filter (fn [l] (not (or (.startsWith l "# ") (= l "")))))
                                          first)
                          title (-> f
                                    (.replace (js/RegExp. "-" "g") " ")
                                    (.replace (js/RegExp. "\\b\\w" "g") #(.toUpperCase %)))]
                      {f [title description content]})))
            vec)))

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

(defn get-user-profile [tw user-id]
  (->
    (.get (aget tw "v2") "users" (clj->js {:ids user-id :user.fields "id,name,username,url,profile_image_url"}))
    (.then (fn [data] (-> data (aget "data") first)))
    (.catch (fn [err]
              (js/console.error err)
              (error-to-json err)))))

(defn make-simple-page [content]
  (let [template (rc/inline "index.html")
        dom (motionless/dom template)
        app (.$ dom "main")]
    (aset app "innerHTML" "")
    (.appendChild app
                  (.h dom "section" #js {:className "ui-section-pricing"}
                      (.h dom "div" #js {:className "ui-layout-container"}
                          (.h dom "h2" content))))
    (.render dom)))

(defn articles [req res]
  (let [template (rc/inline "index.html")
        dom (motionless/dom template)
        app (.$ dom "main")
        body (.$ dom "body")
        title-element (.$ dom "title")
        social-titles [(.$ dom "[name=\"twitter:title\"]") (.$ dom "[property=\"og:title\"]")]
        social-descriptions [(.$ dom "[name=\"twitter:description\"]") (.$ dom "[property=\"og:description\"]")]
        [title description content] (get article-list (aget req "params" "article"))]
    (aset app "innerHTML"
          (r [:section {:class "ui-section-articles"}
              [:div {:class "ui-layout-container"}
               (if title
                 [:div {:dangerouslySetInnerHTML {:__html (marked content)}}]
                 [:div
                  [:h2 "Articles"]
                  [:ul
                   (for [[f [title description]] article-list]
                     [:li {:key f}
                      [:h3 [:a {:href (str "/articles/" f)} title]]
                      [:p description]])]])]]))
    (when title (aset title-element "textContent" (str title " - TweetFeast")))
    (doseq [t social-titles]
      (when t
        (.setAttribute t "content" (str title " by TweetFeast"))))
    (doseq [d social-descriptions]
      (when d
        (.setAttribute d "content" description)))
    (.appendChild body
                  (.h dom "link"
                      #js {:rel "stylesheet"
                           :href "//cdnjs.cloudflare.com/ajax/libs/highlight.js/11.1.0/styles/default.min.css"}))
    (.appendChild body
                  (.h dom "script"
                      #js {:src "//cdnjs.cloudflare.com/ajax/libs/highlight.js/11.1.0/highlight.min.js"}))
    (.appendChild body
                  (.h dom "script" "hljs.highlightAll();"))
    (.send res (.render dom))))

(defn authenticate-admin [req res n]
  (let [user (j/get-in req [:session :user])]
    (if (and user
             (= (aget user "userName") "tweetfeastapp"))
      (n)
      (if req.xhr
        (return-json-error res {:message "Unauthorized."}  403)
        (-> res
            (.status 403)
            (.send (make-simple-page "Unauthorized.")))))))

(defn serve-homepage [mainfile req res]
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
          (.after app (.h dom "script" #js {:src mainfile}))
          (.after app (.h dom "script" #js {:src "/js/common.js"}))
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
            (log-event "last/request" (aget user "userId") user)
            (log-event "event/search-v2" (rnd-id) user {:q (aget req.body "q")})
            (.json res data))))
      (.catch 
        (fn [err] (return-json-error res err 403))))))

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
            (log-event "last/request" (aget user "userId") user)
            (log-event "event/search-v1" (rnd-id) user {:q (aget req.body "q")})
            (.json res data)))
        (.catch
          (fn [err] (return-json-error res err 403))))))

(defn raw-api [req res]
  (let [user (j/get-in req [:session :user])
        tw (twitter user)
        url (aget req "url")
        v (if (>= (.indexOf url "v1") 0)
            "v1"
            "v2")]
    (-> (.get (aget tw v) (-> (aget req "url") (.replace "/api/" "") (.replace "v1/" "")))
        (.then
          (fn [data]
            (.json res data)))
        (.catch
          (fn [err] (return-json-error res err 403))))
    (log-event "last/request" (aget user "userId") user)
    (log-event "event/api" (rnd-id) user {:url (aget req "url")})))

(defn soon [_req res]
  (.send res (make-simple-page "Soon.")))

(defn admin-data [_req res]
  (let [db (db/client)]
    (->
      (.query db "select * from keyv where key like 'login/last:%' or key like 'last/%'")
      (.then #(.json res (.map % (fn [row]
                                   (let [k (aget row "key")
                                         v (aget (js/JSON.parse (aget row "value")) "value")]
                                     (aset v "kind" k)
                                     v)))))
      (.catch (fn [err] (return-json-error res err 404))))))

(defn setup-routes [app]
  (web/reset-routes app)
  (.get app "/" (fn [req res] (serve-homepage "/js/main.js" req res)))
  (web/static-folder app "/" (if (util/env "NGINX_SERVER_NAME") "build" "public"))
  (.use app util/strip-slash-redirect)
  (.get app "/articles" articles)
  (.get app "/articles/:article" articles)
  ;(.get app "/login" soon)
  (.get app "/login" twitter-login)
  (.get app "/logout" twitter-logout)
  (.get app "/twitter-callback" twitter-login-done)
  (j/call app :post "/search" search-old)
  (j/call app :get "/api/*" raw-api)
  (.get app "/reader*" (fn [req res] (serve-homepage "/js/read.js" req res)))
  (.use app authenticate-admin)
  (.get app "/admin" (fn [req res] (serve-homepage "/js/admin.js" req res)))
  (.get app "/admin/data" admin-data)
  ;(.use app authenticate)
  ;(.get app "/data" get-data)
  ;(.post app "/save" set-data)
  )

(defn main! []
  (p/let [[app host port] (web/start)]
    (util/reloader (partial #'setup-routes app))
    (setup-routes app)
    (println "Serving on " (str "http://" host ":" port))))
