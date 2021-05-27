(ns twentiments.ui
  (:require [reagent.core :as r]
            [reagent.dom :as rd]
            [applied-science.js-interop :as j]
            [cljs.core.async :refer (go <!) :as async]
            [cljs.core.async.interop :refer-macros [<p!]]
            ["twitter-text" :as twitter-text]
            ["twemoji" :as twemoji]
            ["json2csv" :as json2csv]))

(def initial-state {:results-view-table true})

(defonce state (r/atom initial-state))

(def tweet-data-keys
  [:id
   :text
   :date-time

   :metric-likes
   :metric-replies
   :metric-quotes
   :metric-retweets

   :ai-sentiment

   :user-id
   :user-name
   :user-full-name
   :user-image-url])

; *** functions *** ;

(defn auth []
  (let [user-data (->
                    (js/document.querySelector "#app")
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
                        (update-in [:progress] dissoc :search))))))

(defn split-date-time [dt]
  (-> dt (.split "T") (.join " ") (.split ".") first))

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

(def slug-regex (js/RegExp. "\\W+" "g"))

(defn slug [text]
  (-> text
      .toString
      .toLowerCase
      (.replace slug-regex "-")))

(defn make-full-json-string [results]
  (let [results (js/JSON.parse (js/JSON.stringify results))
        results-meta (aget results "meta")]
    (when results-meta (js-delete results-meta "next_token"))
    (js-delete results "next")
    (js-delete results "requestParameters")
    (js/JSON.stringify results nil 2)))

; for the new v2 search API
(defn make-flat-json-v2 [results]
  (let [users (get-users results)
        tweets (aget results "data")]
    (.map tweets
          (fn [tweet i]
            (let [user (get-user users (aget tweet "author_id"))
                  m (aget tweet "public_metrics")
                  values [:id (aget tweet "id_str")
                          :date-time (split-date-time (aget tweet "created_at"))

                          :metric-likes (aget m "like_count")
                          :metric-replies (aget m "reply_count")
                          :metric-quotes (aget m "quote_count")
                          :metric-retweets (aget m "retweet_count")

                          :ai-sentiment (aget tweet "sentiment")

                          :user-id (aget tweet "author_id")
                          :user-name (aget user "username")
                          :user-full-name (aget user "name")
                          :user-image-url (aget user "profile_image_url")
                          :link (str "https://twitter.com/i/web/status/" (aget tweet "id_str"))
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
                  values [:id (str "id:" (aget tweet "id_str"))
                          :date-time (-> (aget tweet "created_at") js/Date. .toISOString split-date-time)

                          :metric-likes (aget tweet "favorite_count")
                          :metric-replies (aget tweet "reply_count")
                          :metric-quotes (aget tweet "quote_count")
                          :metric-retweets (aget tweet "retweet_count")

                          :ai-sentiment (aget tweet "sentiment")

                          :user-id (aget tweet "author_id")
                          :user-name (aget tweet "user" "screen_name")
                          :user-full-name (aget tweet "user" "name")
                          :user-image-url (aget tweet "user" "profile_image_url_https")
                          :link (str "https://twitter.com/i/web/status/" (aget tweet "id_str"))
                          :text (if extended (aget extended "full_text") (aget tweet "text"))]
                  js-struct #js {}]
              ; this is to preserve CSV column order as js
              ; key order is stable in practice
              (doseq [[k v] (partition 2 values)]
                (aset js-struct (name k) v))
              js-struct)))))

(def make-flat-json make-flat-json-v1)

(defn decode-html [html]
  (let [txt (.createElement js/document "textarea")]
    (aset txt "innerHTML" html)
    (aget txt "value")))

; *** components *** ;

(defn component-search [state user]
  [:fieldset.horizontal
    [:input.fit {:placeholder "Search for tweets..."
                 :value (@state :q)
                 :on-change #(swap! state assoc :q (-> % .-target .-value))
                 :on-key-down #(when (= (aget % "keyCode") 13) (initiate-search state))}]
    [:button
     {:on-click (partial initiate-search state)}
     "search"]])

(defn component-tweet [tweet]
  [:div.twitter-tweet {:key (aget tweet "id")
                       :class (cond (>= (aget tweet "sentiment") 2) "sentiment-positive"
                                    (<= (aget tweet "sentiment") -2) "sentiment-negative")}
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
     (aget tweet "date-time")]]
   [:div
    [:span "likes: " (aget tweet "metric-likes") " "]
    [:span "replies: " (aget tweet "metric-replies") " "]
    [:span "quotes: " (aget tweet "metric-quotes") " "]
    [:span "retweets: " (aget tweet "metric-retweets")]]
   [:div [:a {:on-click
              (fn [ev]
                (let [el (-> ev .-target)]
                  (j/call-in js/twttr [:widgets :createTweet] (aget tweet "id_str") el)))}
          "see tweet"]]])

(defn component-tweets [state]
  [:div
   (for [tweet (make-flat-json (@state :results))]
     (with-meta [component-tweet tweet] {:key (aget tweet "id")}))])

(defn component-tweets-table [state]
  (let [tweet-table-keys
        [[:user-image-url "Pic"]
         [:user-name "Username"]
         [:date-time "Datetime"]
         [:metric-likes "Likes"]
         [:metric-retweets "RTs"]
         [:metric-quotes "Quotes"]
         [:metric-replies "Replies"]
         [:ai-sentiment "Sentiment"]
         [:text "Tweet"]]]
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

(defn component-download-results [state]
  (let [tweets (@state :results)
        date (js/Date.)
        day (-> (str "0" (.getDate date)) (.slice -2))
        month (-> (str "0" (inc (.getMonth date))) (.slice -2))
        year (.getFullYear date)
        file-name (str "tweets-" year "-" month "-" day "-" (slug (@state :results-q)))]
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

(defn component-main-interface [state user]
  (let [searching (-> @state :progress :search)
        results (@state :results)]
    [:main
     [:nav
      [:a {:href "/logout"} "Sign out"]
      [:div.user-profile
       [:img {:src (:profile_image_url user)}]]]
     [component-search state]
     [:section.options
      [:input {:name "search-state-check"
               :id "search-state-check"
               :type "checkbox"
               :checked (@state :results-view-table)
               :on-change #(swap! state assoc :results-view-table (-> % .-target .-checked))}]
      [:label {:for "search-state-check"} "table view"]
      (when results
        [:button {:on-click #(swap! state dissoc :results :q)} "clear"])]
     (if searching
       [:div.spinner.spin]
       (if results
         (cond
           (aget results "error") [:div.errors
                                   (for [e (js->clj (aget results "error" "errors"))]
                                     [:p.error (get e "message")])
                                   (let [e (aget results "error" "error")]
                                     [:p.error (aget e "message")])]
           (or (aget results "data")
               (aget results "results")) [:span
                                          [component-download-results state]
                                          (if (@state :results-view-table)
                                            [component-tweets-table state]
                                            [component-tweets state])]
           :else "No tweets found.")
         [component-help-text]))]))

(defn component-front-page []
  [:a {:href "/login"} "Sign in with Twitter"])

(defn component-main [state]
  (let [user (auth)]
    (if user
      [component-main-interface state user]
      [component-front-page])))

; *** startup *** ;

(defn reload! []
  (js/console.log "reload!")
  (rd/render [component-main state] (js/document.querySelector "#app")))

(defn main! []
  (go
      (reload!))
  (js/console.log "main!"))

