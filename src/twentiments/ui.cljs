(ns twentiments.ui
  (:require [reagent.core :as r]
            [reagent.dom :as rd]
            [applied-science.js-interop :as j]
            [clast.ui :refer [simple-date-time slug]]
            [cljs.core.async :refer (go <!) :as async]
            [cljs.core.async.interop :refer-macros [<p!]]
            ["twitter-text" :as twitter-text]
            ["wink-sentiment" :as sentiment]
            ["twemoji" :as twemoji]
            ["json2csv" :as json2csv]))

(def initial-state {:results-view-table true})

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

(defn auth []
  (let [user-data (->
                    (js/document.querySelector "main")
                    (.getAttribute "data-user"))]
    (when user-data
      (-> user-data
          js/atob
          js/JSON.parse
          (js->clj :keywordize-keys true)))))

(defn initiate-search [state]
  (swap! state assoc-in [:progress :search] :loading)
  (go
    (let [json (<p! (-> (js/fetch "/search" #js {:method "POST"
                                                 :body (js/JSON.stringify #js {:q (@state :q)})
                                                 :headers #js {:content-type "application/json"}})
                        (.then (fn [response] (.json response)))
                        (.catch (fn [err]
                                  (clj->js {"error" {"error" err}})))))]
      (js/console.log "Results:" json)
      (swap! state #(-> %
                        (assoc :results json)
                        (assoc :results-q (@state :q))
                        (assoc :results-format :v1)
                        (update-in [:progress] dissoc :search))))))

(defn get-user-by-username [username]
  (-> (js/fetch (str "/api/users/by/username/" username
                     "?" user-fields))
      (.then #(.json %))
      (.catch (fn [err]
                (clj->js {"error" {"error" err}})))))

(defn get-user-followers [user-id]
  (-> (js/fetch (str "/api/users/" user-id "/followers"
                     "?" user-fields))
      (.then #(.json %))
      (.catch (fn [err] (clj->js {"error" {"error" err}})))))

(defn initiate-follower-download [state username]
  (swap! state assoc-in [:progress :search] :loading)
  (go
    (let [user (<p! (get-user-by-username username))
          user-data (aget user "data")
          followers (when user-data (<p! (get-user-followers (aget user-data "id"))))]
      (swap! state #(-> %
                        (assoc :results (or followers user))
                        (assoc :results-q (str username "-followers"))
                        (assoc :results-format :users)
                        (update-in [:progress] dissoc :search))))))

(defn get-user-tweets [user-id search-type]
  (let [search-api (get {"timeline" "tweets"
                         "likes" "liked_tweets"
                         "mentions" "mentions"}
                        search-type)]
    (-> (js/fetch (str "/api/users/" user-id "/" search-api
                       "?" tweet-fields "&" user-fields
                       "&" "max_results=100"))
        (.then #(.json %))
        (.catch (fn [err] (clj->js {"error" {"error" err}}))))))

(defn initiate-user-tweet-fetch [state username search-type]
  (swap! state assoc-in [:progress :search] :loading)
  (go
    (let [user (<p! (get-user-by-username username))
          user-data (aget user "data")
          results (when user-data (<p! (get-user-tweets (aget user-data "id") search-type)))]
      (swap! state #(-> %
                        (assoc :results (or results user))
                        (assoc :results-q (str username "-" search-type))
                        (assoc :results-format :v2)
                        (update-in [:progress] dissoc :search))))))

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
          (fn [user i]
            (let [metrics (or (aget user "public_metrics") #js {})
                  values [:id (aget user "id")
                          :username (aget user "username")
                          :name (aget user "name")
                          :date-time-created (simple-date-time (aget user "created_at"))
                          :verified (aget user "verified")

                          :metric-followers (aget metrics "followers_count")
                          :metric-following (aget metrics "following_count")
                          :metric-tweets (aget metrics "tweet_count")
                          :metric-listed (aget metrics "listed_count")

                          :location (aget user "location")
                          :link (str "https://twitter.com/i/web/status/" (aget user "id"))
                          :description (aget user "description")
                          :image-url (aget user "profile_image_url")]
                  js-struct #js {}]
              (doseq [[k v] (partition 2 values)]
                (aset js-struct (name k) v))
              js-struct)))))

; for the new v2 search API
(defn make-flat-json-v2 [results]
  (let [users (get-users results)
        tweets (aget results "data")]
    (.map tweets
          (fn [tweet i]
            (let [user (get-user users (aget tweet "author_id"))
                  m (aget tweet "public_metrics")
                  id (or (aget tweet "id_str") (aget tweet "id"))
                  values [:id (str "id:" id)
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
                          :link (str "https://twitter.com/i/web/status/" id)
                          :text (aget tweet "text")]
                  js-struct #js {}]
              ; this is to preserve CSV column order as js
              ; key order is stable in practice
              (doseq [[k v] (partition 2 values)]
                (aset js-struct (name k) v))
              js-struct)))))

; for the old v1.1 search API
(defn make-flat-json-v1 [results]
  (let [tweets (aget results "results")]
    (.map tweets
          (fn [tweet i]
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

(def json-format-fns {:v1 make-flat-json-v1
                      :v2 make-flat-json-v2
                      :users make-flat-json-users})

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
   (let [e (aget results "error" "error")]
     (when e
       [:p.error (aget e "message")]))])

(defn component-search [state user]
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
        make-flat-json (json-format-fns (@state :results-format))]
    [:table
     [:thead
      [:tr
       (for [[k n] tweet-table-keys]
         [:th {:key k :class (str "column-" (name k))} n])]]
     [:tbody
      (let [data (make-flat-json (@state :results))]
        (for [row data]
          [:tr {:key (aget row "id")}
           (for [[k n] tweet-table-keys]
             [:td {:key k :class (str "column-" (name k))}
              (case k
                :id nil
                :user-id nil
                :ai-sentiment (aget row "ai-sentiment")
                :user-image-url [:img.user-image {:src (aget row "user-image-url")}]
                :text [:a {:href (aget row "link")
                           :target "_blank"}
                       (decode-html (aget row "text"))]
                (aget row (name k)))])]))]]))

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

         [:location "Location"]
         [:description "Description"]]
        make-flat-json (json-format-fns (@state :results-format))]
    [:table
     [:thead
      [:tr
       (for [[k n] user-table-keys]
         [:th {:key k :class (str "column-" (name k))} n])]]
     [:tbody
      (let [data (make-flat-json (@state :results))]
        (for [row data]
          [:tr {:key (aget row "id")}
           (for [[k n] user-table-keys]
             [:td {:key k :class (str "column-" (name k))}
              (case k
                :id nil
                :image-url [:img.user-image {:src (aget row "image-url")}]
                :description (decode-html (aget row "text"))
                (aget row (name k)))])]))]]))

(defn component-download-results [state]
  (let [tweets (@state :results)
        date (js/Date.)
        day (-> (str "0" (.getDate date)) (.slice -2))
        month (-> (str "0" (inc (.getMonth date))) (.slice -2))
        year (.getFullYear date)
        file-name (str "tweets-" year "-" month "-" day "-" (slug (@state :results-q)))
        make-flat-json (json-format-fns (@state :results-format))]
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

(defn component-tweet-results [state empty-component]
  (let [searching (-> @state :progress :search)
        results (@state :results)]
    (if searching
      [:div.spinner.spin]
      (if results
        (cond
          (aget results "error") [component-errors results]
          (or (aget results "data")
              (aget results "results")) [:span
                                         [component-download-results state]
                                         (if (@state :results-view-table)
                                           [component-tweets-table state]
                                           [component-tweets state])]
          :else "No tweets found.")
        (when empty-component
          [empty-component])))))

(defn component-user-results [state]
  (let [searching (-> @state :progress :search)
        results (@state :results)]
    (if searching
      [:div.spinner.spin]
      (if results
        (cond
          (aget results "error") [component-errors results]
          (aget results "data") [:span
                                 [component-download-results state]
                                 [component-users-table state]]
          :else "Users not found.")))))

(defn component-search-interface [state user]
  (let []
    [:section#app
     [:div#trial "Free trial"]
     [:p "Search for the tweets you want to export and download."]
     [component-search state]
     #_ [:section.options
         [:input {:name "search-state-check"
                  :id "search-state-check"
                  :type "checkbox"
                  :checked (@state :results-view-table)
                  :on-change #(swap! state assoc :results-view-table (-> % .-target .-checked))}]
         [:label {:for "search-state-check"} "table view"]]
     [component-tweet-results state component-help-text]
     [:div#feedback [:a {:href "mailto:chris@mccormickit.com?subject=TweetFeast+feedback"} "Send feedback"]]]))

(defn component-user-tweets [state user]
  (let [username (r/atom nil)
        search-type (r/atom "timeline")]
    (fn []
      (let [un (or @username (:username user))
            searching (-> @state :progress :search)
            results (@state :results)]
        [:section#app
         [:section.ui-layout-container
          [:h3 "User tweets / likes / mentions"]
          [:p "Tweets from a user timeline, liked by a user, or mentioning a user."]
          [:div
           "Tweets "
           [:select {:on-change #(reset! search-type (-> % .-target .-value))
                     :value @search-type}
            [:option {:value "timeline"} "in the timeline of"]
            [:option {:value "likes"} "liked by"]
            [:option {:value "mentions"} "mentioning"]]
           [:input {:on-change #(reset! username (-> % .-target .-value))
                    :placeholder "Twitter username"
                    :value un
                    :on-key-down #(when (= (aget % "keyCode") 13) (initiate-user-tweet-fetch state un @search-type))}]
           [:button.primary {:on-click #(initiate-user-tweet-fetch state un @search-type)} "go"]]]
         [component-tweet-results state]]))))

(defn component-followers [state user]
  (let [username (r/atom nil)]
    (fn []
      (let [un (or @username (:username user))]
        [:section#app
         [:section.ui-layout-container
          [:h3 "User follow lists"]
          [:p "Download follower/following user lists."]
          [:div
           "Users who "
           [:select
            [:option "follow"]
            [:option "are following"]]
           [:input {:on-change #(reset! username (-> % .-target .-value))
                    :placeholder "Twitter username"
                    :value un
                    :on-key-down #(when (= (aget % "keyCode") 13) (initiate-follower-download state un))}]
           [:button.primary {:on-click #(initiate-follower-download state un)} "go"]]]
         [component-user-results state]]))))

(defn component-choose-activity [state user]
  (let [h (aget js/document "location" "hash")
        history (@state :history)]
    (case h
      "#follow" [component-followers state user]
      "#user-tweets" [component-user-tweets state user]
      "#search-tweets" [component-search-interface state user]
      [:section#app
       [:section.ui-layout-container
        [:h3 "What kind of Twitter data do you need?"]
        [:ul#data-menu
         [:li [:a {:class "button" :href "#follow"} "Users who follow a user"]]
         [:li [:a {:class "button" :href "#follow"} "Users who are following a user"]]
         [:li [:a {:class "button" :href "#user-tweets"} "Tweets by a user"]]
         [:li [:a {:class "button" :href "#user-tweets"} "Tweets liked by a user"]]
         [:li [:a {:class "button" :href "#user-tweets"} "Tweets a user is mentioned in"]]
         [:li [:a {:class "button" :href "#search-tweets"} "Tweets from a search result"]]]]])))

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
                     (fn [ev]
                       (swap! state
                              #(-> %
                                  (assoc :history (aget js/document "location" "hash"))
                                  (dissoc :results :results-q :results-format))))
                     false)
  (reload!))
