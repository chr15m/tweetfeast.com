(ns twentiments.server
  (:require
    ["fs" :as fs]
    [sitefox.db :as db]
    [sitefox.web :as web]
    [sitefox.util :refer [env btoa]]
    [sitefox.logging :refer [bind-console-to-file]]
    [sitefox.tracebacks :refer [install-traceback-emailer]]
    [reagent.dom.server :refer [render-to-static-markup]]
    ["motionless" :as motionless]
    ["wink-sentiment" :as sentiment]
    ["marked" :as marked]
    ["marked-images" :as mi]
    [shadow.resource :as rc]
    [promesa.core :as p]
    [applied-science.js-interop :as j]
    [twentiments.interface :refer [update-nav]]
    [twentiments.api :refer [return-json-error log-event rnd-id twitter twitter-environment
                             twitter-login twitter-logout twitter-login-done]]
    [twentiments.subscriptions :refer [view-subscribe begin-subscription account
                                       customer-portal get-and-set-subscription]]))

(defonce server (atom nil))

(def r render-to-static-markup)

(bind-console-to-file)

(install-traceback-emailer (env "ADMIN_EMAIL"))

(marked/use (mi))

; *** function calls *** ;

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

(defn make-simple-page [req content]
  (let [user (j/get-in req [:session :user])
        template (rc/inline "index.html")
        dom (motionless/dom template)
        app (.$ dom "main")]
    (aset app "innerHTML" "")
    (.appendChild app
                  (.h dom "section" #js {:className "ui-section-pricing"}
                      (.h dom "div" #js {:className "ui-layout-container"}
                          (.h dom "h2" content))))
    (update-nav dom user)
    (.render dom)))

(defn authenticate-admin [req res n]
  (let [user (j/get-in req [:session :user])]
    (if (and user
             (or
               (= (aget user "userName") "GetTweetFeast")
               (= (aget user "userId") (env "ADMIN_TWITTER_ID"))))
      (n)
      (if req.xhr
        (return-json-error res {:message "Unauthorized."} 403)
        (-> res
            (.status 403)
            (.send (make-simple-page req "Unauthorized.")))))))

(defn authenticate-user [req res n]
  (let [user (j/get-in req [:session :user])]
    (if user
      (n)
      (if (aget req "xhr")
        (return-json-error res {:message "Unauthorized."} 403)
        (.redirect res (str "/login?next=" (aget req "path")))))))

; *** pages *** ;

(defn articles [req res]
  (let [user (j/get-in req [:session :user])
        template (rc/inline "index.html")
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
                 [:div#article {:dangerouslySetInnerHTML {:__html (marked content)}}]
                 [:div#articles-list
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
    (update-nav dom user)
    (.send res (.render dom))))

(defn serve-homepage [mainfile req res]
  (let [template (rc/inline "index.html")
        user (j/get-in req [:session :user])]
    (if user
      (p/let [user-id (aget user "userId")
              user-profile (aget user "profile")
              subscription (get-and-set-subscription user-id)
              dom (motionless/dom template)
              el (j/call-in dom [:h :bind] nil)
              app (j/call dom :$ "main")]
        (aset user-profile "subscription" subscription)
        (aset app "innerHTML" "")
        (.appendChild app (el "div" #js {:id "loading"} (el "div" #js {:className "spinner spin"})))
        (.after app (el "script" #js {:src mainfile}))
        (.after app (el "script" #js {:src "/js/common.js"}))
        (update-nav dom user)
        (.setAttribute app "data-user" (-> user-profile js/JSON.stringify btoa))
        (.send res (j/call dom :render)))
      (.send res template))))

(defn soon [req res]
  (.send res (make-simple-page req "Soon.")))

; *** API *** ;

(defn search [req res]
  (let [user (j/get-in req [:session :user])
        tw (twitter user)
        query (aget req "query" "q")]
    ; https://developer.twitter.com/en/docs/twitter-api/tweets/search/quick-start/recent-search
    (->
      (.get (aget tw "v2") "tweets/search/recent"
            (clj->js {:query query ; (str "\"" (aget req.body "q") "\"")
                      :max_results 100
                      :expansions "referenced_tweets.id,author_id"
                      :tweet.fields "created_at,public_metrics,author_id"
                      :user.fields "username,name,profile_image_url,url,public_metrics"}))
      (.then
        (fn [data]
          (let [tweets (aget data "data")]
            (when tweets
              (.map tweets
                    (fn [d]
                      (aset d "sentiment"
                            (aget (sentiment (aget d "text")) "score")))))
            (log-event "last/request" (aget user "userId") user)
            (log-event "event/search-v2" (rnd-id) user {:q query})
            (.json res data))))
      (.catch 
        (fn [err] (return-json-error res err 403))))))

(defn search-v1 [req res]
  (let [user (j/get-in req [:session :user])
        tw (twitter user)
        query (aget req "query")]
    ; https://developer.twitter.com/en/docs/twitter-api/premium/search-api/overview
    (-> (.get (aget tw "v1") (str "tweets/search/fullarchive/" twitter-environment ".json")
              query
              #js {:fullResponse true})
        (.then
          (fn [data]
            (let [tweets (j/get-in data [:data :results])]
              (when tweets
                (.map tweets
                      (fn [d]
                        (aset d "sentiment"
                              (aget (sentiment (aget d "text")) "score"))))))
            (when (not (aget query "next"))
              (log-event "last/request" (aget user "userId") user)
              (log-event "event/search-v1" (rnd-id) user (js->clj query)))
            (.json res (-> (j/get-in data [:data])
                           (j/assoc! :rateLimit (j/get-in data [:rateLimit]))))))
        (.catch
          (fn [err] (return-json-error res err 403))))))

(defn raw-api [req res]
  (let [user (j/get-in req [:session :user])
        tw (twitter user)
        url (aget req "url")
        v (if (>= (.indexOf url "v1") 0)
            "v1"
            "v2")]
    (-> (.get (aget tw v) (-> (aget req "url") (.replace "/api/" "") (.replace "v1/" "")) #js {} #js {:fullResponse true})
        (.then
          (fn [data]
            (.json res
                   (-> (j/get-in data [:data])
                       (j/assoc! :rateLimit (j/get-in data [:rateLimit]))))))
        (.catch
          (fn [err] (return-json-error res err 403))))
    (log-event "last/request" (aget user "userId") user)
    (log-event "event/api" (rnd-id) user {:url (aget req "url")})))

(defn admin-data [_req res]
  (p/catch
    (p/let [db (db/client)
            login-event-rows (.query db "select * from keyv where key like 'login/last:%' or key like 'last/%'")
            login-events (.map login-event-rows
                               (fn [row]
                                 (let [k (aget row "key")
                                       v (aget (js/JSON.parse (aget row "value")) "value")]
                                   (aset v "kind" k)
                                   v)))
            event-rows (.query db (str "select * from keyv where key like 'event/%'"))
            events-user-days (map (fn [row]
                                    (let [value (js/JSON.parse (aget row "value"))
                                          user-id (j/get-in value [:value :id])
                                          t (j/get-in value [:value :t])
                                          day (-> t (.split "T") first)]
                                      [user-id day])) event-rows)
            events-user-days (set events-user-days)
            events-by-user (reduce
                             (fn [events [user-id day]]
                               (update-in events [user-id] conj day))
                             {}
                             events-user-days)]
      ;(js/console.log (clj->js events-by-user))
      ;(js/console.log (clj->js login-events))
      (.json res (clj->js {:login-events login-events :user-dates events-by-user})))
    (fn [err] (return-json-error res err 404))))

(defn trigger-error [_req _res]
  (js/Promise. (fn [_res err] (err "oh no"))))

; *** routes *** ;

(defn setup-routes [app]
  (web/reset-routes app)
  (.get app "/$" (fn [req res] (serve-homepage "/js/main.js" req res)))
  (web/static-folder app "/" (if (env "NGINX_SERVER_NAME") "build" "public"))
  (.use app web/strip-slash-redirect)
  (.get app "/articles" articles)
  (.get app "/articles/:article" articles)
  (.get app "/exporter" (fn [req res] (serve-homepage "/js/main.js" req res)))
  (.get app "/reader*" (fn [req res] (serve-homepage "/js/read.js" req res)))
  (.get app "/pricing" view-subscribe)
  ;(.get app "/login" soon)
  (.get app "/login" twitter-login)
  (.get app "/logout" twitter-logout)
  (j/call app :post "/account/begin-subscription" begin-subscription)
  (j/call app :get "/account/portal" customer-portal)
  (j/call app :get "/twitter-callback" twitter-login-done)
  (j/call app :get "/trigger-error" trigger-error)
  (.use app authenticate-user)
  (j/call app :get "/account" account)
  (j/call app :get "/search" search-v1)
  (j/call app :get "/api/*" raw-api)
  (.use app authenticate-admin)
  (.get app "/admin" (fn [req res] (serve-homepage "/js/admin.js" req res)))
  (.get app "/admin/data" admin-data)
  ;(.use app authenticate)
  ;(.get app "/data" get-data)
  ;(.post app "/save" set-data)
  )

(defn main! []
  (p/let [[app host port] (web/start)]
    (reset! server app)
    (setup-routes app)
    (println "Serving on " (str "http://" host ":" port))))

(defn ^:dev/after-load reload []
  (js/console.log "Reloading.")
  (setup-routes @server))
