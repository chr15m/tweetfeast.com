(ns twentiments.ui
  (:require [reagent.core :as r]
            [reagent.dom :as rd]
            [applied-science.js-interop :as j]
            [promesa.core :as p]
            [shadow.resource :as rc]
            [sitefox.ui :refer [simple-date-time slug log]]
            ["twitter-text" :as twitter-text]
            ["wink-sentiment" :as sentiment]
            ["twemoji" :as twemoji]
            ["json2csv" :as json2csv]
            [twentiments.common :refer [auth make-tweet-link]]))

(def initial-state {:results-view-table true})

(def feedback-link "mailto:chris@mccormickit.com?subject=TweetFeast+feedback")

(def dm-link "https://twitter.com/messages/compose?recipient_id=1402839069046951938")

(def table-display-limit 50)

(def max-records 10000)

(defonce state (r/atom initial-state))

(def tweet-data-keys
  [:id
   :text
   :date-time-created

   :metric-likes
   :metric-replies
   :metric-quotes
   :metric-retweets

   :ai-sentiment

   :user-id
   :user-name
   :user-full-name
   :user-image-url])

(def user-fields "user.fields=created_at,username,name,description,location,profile_image_url,public_metrics,url,verified")

(def tweet-fields "expansions=referenced_tweets.id,author_id&tweet.fields=created_at,public_metrics,author_id")

; *** functions *** ;

(defn merge-search-json [parent-json json]
  (log "merge-search-json" parent-json json)
  (let [results (j/get json :results)
        error (j/get json :error)
        rate-limit (j/get json :rateLimit)
        parent-json (if results (j/update-in! parent-json [:results] #(.concat (or % #js []) results)) parent-json)
        parent-json (if error (j/assoc! parent-json :error (clj->js {:error error})) parent-json)
        parent-json (j/assoc! parent-json :rateLimit rate-limit)]
    parent-json))

(defn get-search [query progress & [parent-json pagination-token]]
  (p/catch
    (p/let [res (js/fetch (str "/search"
                               "?query=" (js/encodeURIComponent query)
                               "&maxResults=100"
                               (when pagination-token (str "&next=" pagination-token))))
            json (.json res)
            next-token (j/get json :next)
            merged-json (merge-search-json (or parent-json #js {}) json)
            fetched-count (count (j/get parent-json :results))]
      (log "get-search-tweets" fetched-count next-token (j/get parent-json :rateLimit))
      (reset! progress (str "downloaded " fetched-count " tweets..."))
      (if next-token
        (get-search query progress merged-json next-token)
        (do
          (reset! progress "Done. Generating downloads.")
          merged-json)))
    (fn [err]
      (js/console.error "throw" err)
      (j/assoc! parent-json :error (clj->js {:error {:error err}})))))

(defn initiate-search [state]
  (swap! state assoc-in [:progress :search] :loading)
  (p/let [search-results (get-search (@state :q) (r/cursor state [:progress :search]))]
    (log "search-results:" search-results)
    (swap! state #(-> %
                      (assoc :results search-results)
                      (assoc :results-q (@state :q))
                      (assoc :results-format :v1)
                      (update-in [:progress] dissoc :search)))))

(defn get-user-by-username [username]
  (p/catch
    (p/let [username (.replace username "@" "")
            username (.replace username "https://twitter.com/" "")
            req (js/fetch (str "/api/users/by/username/" username
                               "?" user-fields))
            json (.json req)]
      (log "user" json)
      (if (j/get json :error)
        ; if twtter token has expired log out
        (if (not= (.indexOf (j/get-in json [:error :message]) "token") -1)
          (j/assoc-in! js/document [:location :href] "/logout?next=/login")
          #js {:error json})
        json))
    (fn [err]
      (clj->js {"error" {"error" err}}))))

(defn merge-user-json [parent-json json limit]
  (let [data (j/get json :data)
        error (j/get json :error)
        rate-limit (j/get json :rateLimit)
        parent-json (if data (j/update-in! parent-json [:data] #(.concat (or % #js []) data)) parent-json)
        parent-json (j/update-in! parent-json [:data] #(when % (.slice % 0 limit)))
        parent-json (if error (j/assoc! parent-json :error (clj->js {:error error})) parent-json)
        parent-json (j/assoc! parent-json :rateLimit rate-limit)]
    parent-json))

(defn get-user-follow [user-id search-type limit progress & [parent-json pagination-token]]
  (p/catch
    (p/let [url (str "/api/users/" user-id "/" search-type
                     "?" user-fields
                     (when pagination-token (str "&pagination_token=" pagination-token)))
            res (js/fetch url)
            json (.json res)
            next-token (j/get-in json [:meta :next_token])
            merged-json (merge-user-json (or parent-json #js {}) json limit)
            fetched-count (count (j/get parent-json :data))]
      (log "get-user-tweets" fetched-count next-token (j/get parent-json :rateLimit))
      (reset! progress (str "downloaded " fetched-count " users..."))
      (if (and next-token (< fetched-count (or limit max-records)))
        (get-user-follow user-id search-type limit progress merged-json next-token)
        (do
          (reset! progress "Done. Generating downloads.")
          merged-json)))
    (fn [err]
      (js/console.error err)
      (j/assoc! parent-json :error (clj->js {:error {:error err}})))))

(defn initiate-follower-download [state username search-type limit]
  (swap! state assoc-in [:progress :search] :loading)
  (p/let [user (get-user-by-username username)
          user-data (aget user "data")
          followers (when user-data (get-user-follow (aget user-data "id") search-type limit (r/cursor state [:progress :search])))]
    (log "followers" followers)
    (swap! state #(-> %
                      (assoc :results (or followers user))
                      (assoc :results-q (str username "-" search-type))
                      (assoc :results-format :users)
                      (update-in [:progress] dissoc :search)))))

(defn merge-tweet-json [parent-json json limit]
  (let [users (j/get-in json [:includes :users])
        data (j/get json :data)
        error (j/get json :error)
        rate-limit (j/get json :rateLimit)
        parent-json (if users (j/update-in! parent-json [:includes :users] #(.concat (or % #js []) users)) parent-json)
        parent-json (if data (j/update-in! parent-json [:data] #(.concat (or % #js []) data)) parent-json)
        parent-json (j/update-in! parent-json [:data] #(when % (.slice % 0 limit)))
        parent-json (if error (j/assoc! parent-json :error (clj->js {:error error})) parent-json)
        parent-json (j/assoc! parent-json :rateLimit rate-limit)]
    parent-json))

(defn get-user-tweets [user-id search-type limit progress & [parent-json pagination-token]]
  (p/catch
    (p/let [search-api (get {"timeline" "tweets"
                             "likes" "liked_tweets"
                             "mentions" "mentions"}
                            search-type)
            url (str "/api/users/" user-id "/" search-api
                     "?" tweet-fields "&" user-fields
                     "&" "max_results=100"
                     (when pagination-token (str "&pagination_token=" pagination-token)))
            res (js/fetch url)
            json (.json res)
            next-token (j/get-in json [:meta :next_token])
            merged-json (merge-tweet-json (or parent-json #js {}) json limit)
            fetched-count (count (j/get parent-json :data))]
      (log "get-user-tweets" fetched-count next-token (j/get parent-json :rateLimit))
      (reset! progress (str "downloaded " fetched-count " tweets..."))
      (if (and next-token (< fetched-count (or limit max-records)))
        (get-user-tweets user-id search-type limit progress merged-json next-token)
        (do
          (reset! progress "Done. Generating downloads.")
          merged-json)))
    (fn [err]
      (js/console.error err)
      (j/assoc! parent-json :error (clj->js {:error {:error err}})))))

(defn initiate-user-tweet-fetch [state username search-type limit]
  (swap! state assoc-in [:progress :search] :loading)
  (p/let [user (get-user-by-username username)
          user-data (aget user "data")
          results (when user-data (get-user-tweets (aget user-data "id") search-type limit (r/cursor state [:progress :search])))]
    (swap! state #(-> %
                      (assoc :results (or results user))
                      (assoc :results-q (str username "-" search-type))
                      (assoc :results-format :v2)
                      (update-in [:progress] dissoc :search)))))

(defn get-users [results]
  (aget results "includes" "users"))

(defn get-user [users author_id]
  (first (filter #(= (aget % "id") author_id) users)))

(defn make-file-url [content filename content-type]
  (let [file (js/File.
               #js [content]
               (clj->js {:content-type content-type
                         :name filename}))]
    (js/URL.createObjectURL file)))

(defn make-full-json-string [results]
  (let [results (js/JSON.parse (js/JSON.stringify results))
        results-meta (aget results "meta")]
    (when results-meta (js-delete results-meta "next_token"))
    (js-delete results "next")
    (js-delete results "requestParameters")
    (js/JSON.stringify results nil 2)))

(defn tweet-sentiment [tweet]
  (aget (sentiment (aget tweet "text")) "score"))

(defn make-flat-json-users [results]
  (let [users (aget results "data")]
    (.map users
          (fn [user]
            (let [metrics (or (aget user "public_metrics") #js {})
                  js-struct #js {}]
              (j/assoc! js-struct
                        :id (aget user "id")
                        :username (aget user "username")
                        :name (aget user "name")
                        :date-time-created (simple-date-time (aget user "created_at"))
                        :verified (aget user "verified")

                        :metric-followers (aget metrics "followers_count")
                        :metric-following (aget metrics "following_count")
                        :metric-tweets (aget metrics "tweet_count")
                        :metric-listed (aget metrics "listed_count")

                        :location (aget user "location")
                        :link (str "https://twitter.com/" (aget user "username"))
                        :description (aget user "description")
                        :image-url (aget user "profile_image_url")))))))

; for the new v2 search API
(defn make-flat-json-v2 [results]
  (let [users (get-users results)
        tweets (aget results "data")
        get-user-mem (memoize get-user)]
    (.map tweets
          (fn [tweet]
            (let [user (get-user-mem users (aget tweet "author_id"))
                  m (aget tweet "public_metrics")
                  id (or (aget tweet "id_str") (aget tweet "id"))
                  js-struct #js {}]
              (j/assoc! js-struct :id (str "id:" id)
                        :date-time-created (simple-date-time (aget tweet "created_at"))

                        :metric-likes (aget m "like_count")
                        :metric-replies (aget m "reply_count")
                        :metric-quotes (aget m "quote_count")
                        :metric-retweets (aget m "retweet_count")

                        :ai-sentiment (tweet-sentiment tweet)

                        :user-id (aget tweet "author_id")
                        :user-name (aget user "username")
                        :user-full-name (aget user "name")
                        :user-image-url (aget user "profile_image_url")
                        :link (make-tweet-link id)
                        :text (aget tweet "text")))))))

; for the old v1.1 search API
(defn make-flat-json-v1 [results]
  (let [tweets (aget results "results")]
    (.map tweets
          (fn [tweet]
            (let [extended (aget tweet "extended_tweet")
                  id (or (aget tweet "id_str") (aget tweet "id"))
                  values [:id (str "id:" id)
                          :date-time-created (-> (aget tweet "created_at") js/Date. .toISOString simple-date-time)

                          :metric-likes (aget tweet "favorite_count")
                          :metric-replies (aget tweet "reply_count")
                          :metric-quotes (aget tweet "quote_count")
                          :metric-retweets (aget tweet "retweet_count")

                          :ai-sentiment (tweet-sentiment tweet)

                          :user-id (aget tweet "author_id")
                          :user-name (aget tweet "user" "screen_name")
                          :user-full-name (aget tweet "user" "name")
                          :user-image-url (aget tweet "user" "profile_image_url_https")
                          :link (str "https://twitter.com/i/web/status/" id)
                          :text (if extended (aget extended "full_text") (aget tweet "text"))]
                  js-struct #js {}]
              ; this is to preserve CSV column order as js
              ; key order is stable in practice
              (doseq [[k v] (partition 2 values)]
                (aset js-struct (name k) v))
              js-struct)))))

(def json-format-fns {:v1 (memoize make-flat-json-v1)
                      :v2 (memoize make-flat-json-v2)
                      :users (memoize make-flat-json-users)})

(defn decode-html [html]
  (let [txt (.createElement js/document "textarea")]
    (aset txt "innerHTML" html)
    (aget txt "value")))

; *** components *** ;

(defn component-errors [results]
  [:div.errors
   (for [e (aget results "error" "errors")]
     (when e
       [:p.error (aget e "message")]))
   (let [message (j/get-in results [:error :error :message])
         data (j/get-in results [:error :error :data])
         data-string? (= (type data) js/String)
         message-v1 (when (not data-string?) (j/get-in data [:error :message]))]
     (when message
       [:p.error message " "
        (when (and data-string? (< (count data) 280)) [:span [:br] data]) " "
        (when message-v1 (< (count message-v1) 280) [:span [:br] message-v1])]))])

(defn component-search [state _user]
  [:fieldset.horizontal
   [:input.fit {:auto-focus true
                :placeholder "Search for tweets..."
                :value (@state :q)
                :on-change #(swap! state assoc :q (-> % .-target .-value))
                :on-key-down #(when (= (aget % "keyCode") 13) (initiate-search state))}]
   (when (@state :results)
     [:button {:on-click #(swap! state dissoc :results :q)} "clear"])
   [:button
    {:on-click (partial initiate-search state)}
    "search"]])

(defn component-tweet [tweet]
  [:div.twitter-tweet {:key (aget tweet "id")
                       :class (cond (>= (tweet-sentiment tweet) 2) "sentiment-positive"
                                    (<= (tweet-sentiment tweet) -2) "sentiment-negative")}
   [:div.profile
    [:img {:src (aget tweet "user-image-url")}]
    [:div
     [:a.name
      {:href (str "https://twitter.com/" (aget tweet "user-name"))} (aget tweet "user-full-name")] [:br]
     [:a.username
      {:href (str "https://twitter.com/" (aget tweet "user-name"))} "@" (aget tweet "user-name")]]]
   [:div.tweet
    {:ref (fn [el]
            (when el
              ; TODO: sanitize this? security issue if somebody tweets a script tag.
              (aset el "innerHTML"
                    (-> (aget tweet "text") (.replace "\n" "<br/>") (twitter-text/autoLink)))
              (twemoji/parse el)))}]
   [:div.date
    [:a {:href (aget tweet "link")}
     (aget tweet "date-time-created")]]
   [:div
    [:span "likes: " (aget tweet "metric-likes") " "]
    [:span "replies: " (aget tweet "metric-replies") " "]
    [:span "quotes: " (aget tweet "metric-quotes") " "]
    [:span "retweets: " (aget tweet "metric-retweets")]]
   [:div [:a {:on-click
              (fn [ev]
                (let [el (-> ev .-target)
                      id (or (aget tweet "id_str") (aget tweet "id"))]
                  (j/call-in js/twttr [:widgets :createTweet] id el)))}
          "see tweet"]]])

(defn component-tweets [state]
  (let [results (:results @state)
        make-flat-json (json-format-fns (@state :results-format))]
    [:div
     (for [tweet (make-flat-json results)]
       (with-meta [component-tweet tweet] {:key (aget tweet "id")}))]))

(defn component-tweets-table [state]
  (let [tweet-table-keys
        [[:user-image-url "Pic"]
         [:user-name "Username"]
         [:date-time-created "Tweeted"]
         [:metric-likes "Likes"]
         [:metric-retweets "RTs"]
         [:metric-quotes "Quotes"]
         [:metric-replies "Replies"]
         [:ai-sentiment "Sentiment"]
         [:text "Tweet"]]
        make-flat-json (json-format-fns (@state :results-format))
        flat-data (make-flat-json (@state :results))
        data (take table-display-limit flat-data)]
    [:div.results
     [:table
      [:thead
       [:tr
        (for [[k n] tweet-table-keys]
          [:th {:key k :class (str "column-" (name k))} n])]]
      [:tbody
       (for [row data]
         [:tr {:key (aget row "id")}
          (for [[k _n] tweet-table-keys]
            [:td {:key k :class (str "column-" (name k))}
             (case k
               :id nil
               :user-id nil
               :ai-sentiment (aget row "ai-sentiment")
               :user-image-url [:img.user-image {:src (aget row "user-image-url")}]
               :text [:a {:href (aget row "link")
                          :target "_blank"}
                      (decode-html (aget row "text"))]
               (aget row (name k)))])])]]
     (when (> (count flat-data) table-display-limit)
       [:p [:strong "And " (- (count flat-data) table-display-limit) " more results..."]])]))

(defn component-users-table [state]
  (let [user-table-keys
        [[:image-url "Pic"]
         [:username "Username"]
         [:name "Full name"]
         [:date-time-created "Created"]
         [:verified "Verified"]

         [:metric-followers "Followers"]
         [:metric-following "Following"]
         [:metric-tweets "Tweets"]
         [:metric-listed "Lists"]

         [:location "Location"]]
        make-flat-json (json-format-fns (@state :results-format))
        flat-data (make-flat-json (@state :results))
        data (take table-display-limit flat-data)]
    [:div.results
     [:table
      [:thead
       [:tr
        (for [[k n] user-table-keys]
          [:th {:key k :class (str "column-" (name k))} n])]]
      [:tbody
       (for [row data]
         [:tr {:key (aget row "id")}
          (for [[k _n] user-table-keys]
            [:td {:key k :class (str "column-" (name k))}
             (case k
               :id nil
               :image-url [:a {:href (str "https://twitter.com/" (aget row "username")) :target "_BLANK"}
                           [:img.user-image {:src (aget row "image-url")}]]
               :username [:a {:href (str "https://twitter.com/" (aget row "username")) :target "_BLANK"}
                          (aget row "username")]
               ;:description (decode-html (aget row "description"))
               (aget row (name k)))])])]]
     (when (> (count flat-data) table-display-limit)
       [:p [:strong "And " (- (count flat-data) table-display-limit) " more results..."]])]))

(defn component-download-results [state user]
  (let [tweets (@state :results)
        date (js/Date.)
        day (-> (str "0" (.getDate date)) (.slice -2))
        month (-> (str "0" (inc (.getMonth date))) (.slice -2))
        year (.getFullYear date)
        file-name (str "tweets-" year "-" month "-" day "-" (slug (@state :results-q)))
        make-flat-json (json-format-fns (@state :results-format))
        subscription (:subscription user)]
    (when tweets
      [:div.downloads
       [:a.button.primary {:href (make-file-url
                                   (-> (json2csv/Parser.) (.parse (make-flat-json (@state :results))))
                                   (str file-name ".csv")
                                   "application/json")
                           :download (str file-name ".csv")} "download csv"]
       [:a.button.primary {:href (make-file-url
                                   (js/JSON.stringify (make-flat-json (@state :results)) nil 2)
                                   (str file-name ".json")
                                   "application/json")
                           :download (str file-name ".json")} "download json"]
       [:a.button.primary {:href (make-file-url
                                   (make-full-json-string (@state :results))
                                   (str file-name ".json")
                                   "application/json")
                           :download (str file-name "-full.json")}
        "download API json"]])))

(defn component-help-text []
  [:section
   [:h3 "Help"]
   [:p "Search for tweets from the past 30 days and export them as CSV or JSON."]
   [:table#help
    [:thead
     [:tr
      [:th "Search parameter"]
      [:th "Description"]]]
    [:tbody
     [:tr
      [:td "keyword"]
      [:td "Matches a keyword within the body of a Tweet."]]
     [:tr
      [:td "\"exact phrase match\""]
      [:td "Matches an exact phrase within the body of a Tweet."]]
     [:tr
      [:td "@username"]
      [:td "Matches any Tweet that mentions the given username."]]
     [:tr
      [:td "#hashtag"]
      [:td "Matches any Tweet with the given hashtag."]]

     [:tr
      [:td "<emoji>"]
      [:td "Matches an emoji within the body of a Tweet e.g. 🍕"]]

     [:tr 
      [:td "from:username"]
      [:td "Matches any Tweet from a specific user."]]

     [:tr
      [:td "to:username"]
      [:td "Matches any Tweet to the specific user."]]

     [:tr
      [:td "retweets_of:username"]
      [:td "Matches any Tweet that are Retweets of the given user."]]

     [:tr
      [:td "url:..."]
      [:td "Performs a tokenized match on the expanded URLs of a Tweet e.g. url:/developer or url:\"https://developer.twitter.com\"."]]

     [:tr
      [:td "has:images/links/media/mentions/videos"]
      [:td "Match any Tweet that has images, links, media, mentions, or video."]]

     [:tr
      [:td "lang:..."]
      [:td "Matches Tweets that have been classified by Twitter as being of a particular language e.g. en/es/zh etc. "
       "See " [:a {:href "https://t.co/operators"} "t.co/operators"] " for details of valid language codes."]]

     [:tr
      [:td "place_country:..."]
      [:td "Matches Tweets tagged with the alpha-1 country code."
       "See " [:a {:href "https://t.co/operators"} "t.co/operators"] " for details."]]

     [:tr
      [:td "place:\"Place name\""]
      [:td "Matches Tweets tagged with specified location e.g. \"San Francisco\"."]]

     [:tr
      [:td "point_radius:..."]
      [:td "See " [:a {:href "https://t.co/operators"} "t.co/operators"] " for details."]]

     [:tr
      [:td "bounding_box:..."]
      [:td "Matches against the exact location (long, lat) of the Tweet (when present), and against a geo polygon
           (where the Place is fully contained within the defined region). "
       "See " [:a {:href "https://t.co/operators"} "t.co/operators"] " for details."]]]]])

(defn component-data-count [results kind errors]
  [:p
   [:strong (count (or (aget results "data") (aget results "results"))) " " kind " found."]
   (when errors [:span [:br] "Some " kind " may be missing due to Twitter API errors."])])

(defn component-rate-limit [results]
  (when (aget results "rateLimit")
    [:p (j/get-in results [:rateLimit :remaining]) " requests left."]))

(defn component-progress [searching]
  [:div.progress
   [:div.spinner.spin]
   [:p searching]])

(defn component-tweet-results [state user empty-component]
  (let [searching (-> @state :progress :search)
        results (@state :results)]
    (if searching
      [component-progress searching]
      (if results
        [:div
         (when (aget results "error") [component-errors results])
         (cond
           (or (aget results "data")
               (aget results "results")) [:span
                                          [component-data-count results "tweets" (aget results "error")]
                                          ; [component-rate-limit results]
                                          [component-download-results state user]
                                          (if (@state :results-view-table)
                                            [component-tweets-table state]
                                            [component-tweets state])]
           :else "No tweets found.")]
        (when empty-component
          [empty-component])))))

(defn component-user-results [state user]
  (let [searching (-> @state :progress :search)
        results (@state :results)]
    (if searching
      [component-progress searching]
      (when results
        [:div
         (when (aget results "error") [component-errors results])
         (cond
           (aget results "data") [:span
                                  [component-data-count results "users" (aget results "error")]
                                  [component-download-results state user]
                                  [component-users-table state]]
           :else "Users not found.")]))))

(defn component-icon [icon]
  (let [icon (-> icon (.split "\n") rest)]
    [:span.icon {:dangerouslySetInnerHTML {:__html icon}}]))

(defn component-back-button [state]
  [:div [:a {:href "/exporter"
             :on-click (fn [ev]
                         (.pushState js/history nil "" "/exporter")
                         (swap! state assoc :history "")
                         (.preventDefault ev))}
         [component-icon (rc/inline "fa/arrow-circle-left.svg")]
         "Go back to the menu"]])

(defn component-search-interface [state user]
  [:section#app
   [component-back-button state]
   [:p "Search for the tweets you want to export and download."]
   [component-search state]
   #_ [:section.options
       [:input {:name "search-state-check"
                :id "search-state-check"
                :type "checkbox"
                :checked (@state :results-view-table)
                :on-change #(swap! state assoc :results-view-table (-> % .-target .-checked))}]
       [:label {:for "search-state-check"} "table view"]]
   [component-tweet-results state user component-help-text]])

(defn component-user-tweets [state user default]
  (let [username (r/atom nil)
        search-type (r/atom default)
        limit (r/atom max-records)]
    (fn []
      (let [un (or @username (:username user))]
        [:section#app
         [component-back-button state]
         [:section.ui-layout-container
          [:h3 "User tweets / likes / mentions"]
          [:p "Tweets from a user timeline, liked by a user, or mentioning a user."]
          [:div
           "Tweets "
           [:select {:on-change #(reset! search-type (-> % .-target .-value))
                     :value @search-type}
            [:option {:value "timeline"} "by"]
            [:option {:value "likes"} "liked by"]
            [:option {:value "mentions"} "mentioning"]]
           [:input {:on-change #(reset! username (-> % .-target .-value))
                    :placeholder "Twitter username"
                    :value un
                    :on-key-down #(when (= (aget % "keyCode") 13) (initiate-user-tweet-fetch state un @search-type @limit))}]
           [:button.primary {:on-click #(initiate-user-tweet-fetch state un @search-type @limit)} "go"]]
          [:div "Max results: "
           [:input {:on-change #(reset! limit (-> % .-target .-value))
                    :on-blur #(reset! limit (-> @limit int (js/Math.min max-records) (js/Math.max 10)))
                    :type "number"
                    :value @limit}]]]
         [component-tweet-results state user]]))))

(defn component-followers [state user default]
  (let [username (r/atom nil)
        search-type (r/atom default)
        limit (r/atom max-records)]
    (fn []
      (let [un (or @username (:username user))]
        [:section#app
         [component-back-button state]
         [:section.ui-layout-container
          [:h3 "User follow lists"]
          [:p "Download follower/following user lists."]
          [:div
           [:select {:on-change #(reset! search-type (-> % .-target .-value))
                     :value @search-type}
            [:option {:value "following"} "Followed by"]
            [:option {:value "followers"} "Followers of"]]
           [:input {:on-change #(reset! username (-> % .-target .-value))
                    :placeholder "Twitter username"
                    :value un
                    :on-key-down #(when (= (aget % "keyCode") 13) (initiate-follower-download state un @search-type @limit))}]
           [:button.primary {:on-click #(initiate-follower-download state un @search-type @limit)} "go"]]
          [:div "Max results: "
           [:input {:on-change #(reset! limit (-> % .-target .-value))
                    :on-blur #(reset! limit (-> @limit int (js/Math.min max-records) (js/Math.max 10)))
                    :type "number"
                    :value @limit}]]]
         [component-user-results state user]]))))

#_ (defn component-likers [state user default]
  (let [tweet-url (r/atom nil)
        search-type (r/atom default)]
    (fn []
      (let [tweet-id ()])
      )
    )
  )

(defn component-choose-activity [state user]
  (let [h (aget js/document "location" "hash")
        _history (@state :history)]
    [:span
     (case h
       "#following" [component-followers state user "following"]
       "#followers" [component-followers state user "followers"]
       ;"#tweet-likers" [component-likers state user "liking_users"]
       ;"#tweet-retweeters" [component-likers state user "retweeted_by"]
       "#user-timeline" [component-user-tweets state user "timeline"]
       "#user-likes" [component-user-tweets state user "likes"]
       "#user-mentions" [component-user-tweets state user "mentions"]
       "#search-tweets" [component-search-interface state user]
       [:section#app
        [:section.ui-layout-container
         [:h3 "What kind of Twitter data do you need?"]
         [:h4 "Follower / Following"]
         [:ul.data-menu
          [:li [:a {:href "#following"} "List of users followed by a user"]]
          [:li [:a {:href "#followers"} "List of the followers of a user"]]
          ;[:li [:a {:href "#tweet-likers"} "Users who liked a particular tweet"]]
          ;[:li [:a {:href "#tweet-retweeters"} "Users who retweeted a particular tweet"]]
          ]
         [:h4 "Tweets"]
         [:ul.data-menu
          [:li [:a {:href "#user-timeline"} "Tweets by a user"]]
          [:li [:a {:href "#user-likes"} "Tweets liked by a user"]]
          [:li [:a {:href "#user-mentions"} "Tweets a user is mentioned in"]]
          [:li [:a {:href "#search-tweets"} "Tweets from a search result"]]]
         [:h4 "Something else"]
         [:p "If you don't see the type of data you're looking for, "
          [:a {:href feedback-link} "click here to shoot me an email"]
          " and help me improve this tool."]]])
     [:div#feedback [:a {:href dm-link :target "_BLANK"}
                     [component-icon (rc/inline "fa/comment.svg")]]]]))

(defn component-main [state]
  (let [user (auth)]
    (if user
      [component-choose-activity state user]
      [:div "Whoops, something went wrong."])))

; TODO:

  ; Users
  ; [x] followers of a user id /2/users/:id/followers
  ; [x] users a user id is following /2/users/:id/following
  ; [ ] Users who have liked a Tweet /2/tweets/:id/liking_users
  ; [ ] Users who have retweeted a tweet /2/tweets/:id/retweeted_by

  ; Tweets
  ; [x] user timeline /2/users/:id/tweets
  ; [x] tweets mentioning a user /2/users/:id/mentions
  ; [x] tweets liked by a user /2/users/:id/liked_tweets
  ; [x] tweet search
  ; list of tweets by pasting URLs?


; *** startup *** ;

(defn ^:dev/after-load reload! []
  (js/console.log "reload!")
  (rd/render [component-main state] (js/document.querySelector "main")))

(defn main! []
  (js/console.log "main!")
  (.addEventListener js/window "hashchange"
                     (fn [_ev]
                       (swap! state
                              #(-> %
                                  (assoc :history (aget js/document "location" "hash"))
                                  (dissoc :results :results-q :results-format))))
                     false)
  (reload!))
