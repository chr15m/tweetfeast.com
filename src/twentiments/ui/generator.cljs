(ns twentiments.ui.generator
  (:require
    [shadow.resource :as rc]
    [promesa.core :as p]
    [applied-science.js-interop :as j]
    [reagent.dom :as rd]
    [reagent.core :as r]
    ["twemoji" :as twemoji]
    ["twitter-text" :as twitter-text]
    [twentiments.common :refer [auth component-progress component-twicon component-icon]]))

(defonce state (r/atom nil))

(defn initiate-generate-tweets! [state *username *topic]
  (swap! state assoc :fetching true)
  (p/let [res (js/fetch (str "/api/generate"
                          "?username=" *username
                          "&topic=" (js/encodeURIComponent *topic)))
          result (.json res)]
    (swap! state assoc :result result :fetching nil)))

(defn component-tweet [tweet]
  [:div.twitter-tweet {:key (aget tweet "id")}
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

(defn component-generated-tweet-list [state user]
  ;[:pre (js/JSON.stringify (:result @state) nil 2)]
  (let [username (:username user)
        fullname (:name user)
        tweets (:result @state)]
    [:<>
     (for [t (range (count tweets))]
       (let [tweet (nth tweets t)
             tweet-this-link (str "https://twitter.com/intent/tweet?text="
                                  (js/encodeURIComponent tweet)
                                  "&related=GetTweetFeast")]
         [:div.generated-tweet {:key tweet}
          [:div.twitter-tweet
           [:div.profile
            [:img {:src (:profile_image_url user)}]
            [:div
             [:a.name
              {:href (str "https://twitter.com/" username)} fullname] [:br]
             [:a.username
              {:href (str "https://twitter.com/" username)} "@" username]
             #_ [:span.timesince "1h"]]]
           [:div.tweet
            {:ref (fn [el]
                    (when el
                      ; TODO: sanitize this? security issue if ChatGPT generates a script tag.
                      (aset el "innerHTML"
                            (-> tweet (.replace "\n" "<br/>") (twitter-text/autoLink)))
                      (twemoji/parse el)))}]
           #_ [:div.date
               [:a {:href tweet-this-link}
                "2023-01-01"]]
           #_ [:div
               [:span "likes: " (-> (js/Math.random) (* 100) (.toFixed 1)) "k"]
               [:span "replies: " (-> (js/Math.random) (* 100) (.toFixed 1)) "k"]
               [:span "quotes: " (-> (js/Math.random) (* 100) (.toFixed 1)) "k"]
               [:span "retweets: " (-> (js/Math.random) (* 100) (.toFixed 1)) "k"]]]
          [:div.actions
           [:a {:href tweet-this-link :target "_BLANK"}
            [:button.primary "Edit on Twitter "
             [component-twicon "👉️"]]]]]))]))

(defn component-back-button [state]
  [:p [:a {:href "/ai-tweet-generator"
             :on-click (fn [ev]
                         ;(.pushState js/history nil "" "/exporter")
                         ;(swap! state assoc :history "")
                         (swap! state dissoc :result)
                         (.preventDefault ev))}
         [component-icon (rc/inline "fa/arrow-circle-left.svg")]
         "Change user or topic"]])

(defn component-compact-ui [username topic]
  [:<>
   [:p "Tweet like: @" [:strong username]]
   [:p "Topic:"
    [:blockquote "\"" topic "\""]]])

(defn component-home [state user]
  (let [_subscription (:subscription user)
        un (or (:username @state) (:username user))]
    ;[:div (pr-str subscription)]
    ;[:pre (pr-str user)]
    ;[:pre (pr-str @state)]
    [:div
     [:h1 "AI Tweet Generator"]
     (cond
       (j/get (:result @state) :error)
       [:<>
        [component-back-button state]
        [:p "Whoops, there was an error generating. Please try again."]]
       (:result @state)
       [:<>
        [component-back-button state]
        [component-compact-ui (:username @state) (:topic @state)]
        [:h3 "Generated Tweets" [component-twicon "👇️"]]
        [component-generated-tweet-list state user]]
       :else
       [:<>
        [:p "Use the AI tweet generator to generate tweets on any topic of your choice in the voice of any twitter user.
            Choose which user you want to tweet like, and then enter a topic you'd like to tweet about.
            Once the app is done generating you can choose which tweets you want to post.
            No tweets will be posted without your permission."]
        (if user
          [:<>
           [:p "Enter the username of someone you'd like to tweet as (case sensitive)."]
           [:p "Tweet like: "
            [:input
             {:on-change #(swap! state assoc :username (-> % .-target .-value))
              :placeholder "Twitter username"
              :value un
              #_#_ :on-key-down #(when (= (aget % "keyCode") 13)
                                   (initiate-user-tweet-fetch state un @search-type @limit))}]]
           [:p "Enter the topic you'd like to generate tweets about.
               Write whatever you want in here.
               You can write a couple of words or a whole paragraph, it's up to you."]
           [:details
            [:summary "Click for topic ideas."]
            [:ul
             [:li "Something that happened to you today."]
             [:li "Copy and paste part of an article you read."]
             [:li "A good idea you heard recently."]
             [:li "Paste in a quote from somebody famous. "
              [:a {:href "https://www.goodreads.com/quotes/"
                   :target "_BLANK"}
               "Find quotes on any topic here"] "."]
             [:li "A movie, book, or song you heard and why you liked it."]
             [:li "Something that helped you recently."]]]
           [:textarea
            {:placeholder "Topic you'd like to generate tweets about."
             :on-change #(swap! state assoc :topic (-> % .-target .-value))
             :value (:topic @state)}]
           (if (:fetching @state)
             [component-progress "Hang tight, this can take up to two minutes."]
             [:p [:button.primary
                  {:on-click #(initiate-generate-tweets! state un (:topic @state))}
                  "Generate tweets"] [:br]
              [:small "Note: no tweets will be posted. You will be shown tweets and you can choose what to post."]])]
          [:a.button.primary {:href "/login?next=/ai-tweet-generator"} "Sign in to start"])])]))

(defn ^:dev/after-load reload! []
  (js/console.log "reload!")
  (p/let [user (auth)]
    (js/console.log "user" user)
    (js/console.log "state" (clj->js @state))
    (rd/render
      [:span.generator
       [:section#app
        [:section.ui-layout-container
         [component-home state user]]]]
      (js/document.querySelector "main"))))

(defn main! []
  (js/console.log "main!")
  (reload!))
