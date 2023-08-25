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
  (swap! state assoc :fetching {:start (js/Date.) :estimate 120})
  (p/let [res (js/fetch (str "/api/generate"
                          "?username=" *username
                          "&topic=" (js/encodeURIComponent *topic)))
          result (.json res)]
    (swap! state assoc :result result :fetching nil)))

; *** view *** ;

(defn component-subscribe-modal [state]
  (when (:subscribe-modal @state)
    [:div.modal
     [:div
      [:h3 "Your generated tweets"]
      [:p "Subscribe to generate 1000s of tweets."]
      [:p.cta
       [:button {:on-click #(swap! state dissoc :subscribe-modal)} "No thanks"]
       [:a {:href "/pricing"} [:button.primary "Subscribe"]]]]]))

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
        subscription (:subscription user)
        uses (j/get (:result @state) :uses)
        tweets (j/get (:result @state) :tweets)
        limit (if subscription 100
                (js/Math.max 2 (- (dec (count tweets)) uses)))]
    [:<>
     (for [t (range (count tweets))]
       (let [tweet (nth tweets t)
             tweet (if (and (not subscription) (>= t limit))
                     "Subscribe to TweetFeast to generate more tweets. Find out more at tweetfeast.com!"
                     tweet)
             tweet-this-link (str "https://twitter.com/intent/tweet?text="
                                  (js/encodeURIComponent tweet)
                                  "&related=GetTweetFeast")]
         ^{:key t}
         [:<>
          (when (and (not subscription) (= t (inc limit)))
            [:div.subscribe-notice
             [:h3 "Get more generated tweets"]
             [:p "Subscribe to generate 1000s of tweets every month!"]
             [:p.cta
              [:a {:href "/pricing"} [:button.primary "Subscribe"]]]])
          [:div.generated-tweet
           [:div.twitter-tweet {:class (when (and (not subscription) (>= t limit)) "blur")}
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
              [component-twicon "👉️"]]]]]]))]))

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
   [:p "Tweet like: " [:a {:href (str "https://twitter.com/" username)
                           :target "_BLANK"}
                       "@" [:strong username]]]
   [:p "Topic:"]
   [:blockquote "\"" topic "\""]])

(defn auto-resize-textarea [el]
  (j/assoc-in! el [:style :height] "5px")
  (j/assoc-in! el [:style :height] (str (j/get el :scrollHeight) "px")))

(defn component-countdown [fetching]
  (let [t (r/atom (:estimate fetching))]
    (js/setInterval #(swap! t dec) 1000)
    (fn []
      [component-progress [:span "Hang tight, this can take up to two minutes." [:span " (" @t "s)"]]])))

(defn component-home [state user]
  (let [un (or (:username @state) (:username user))
        result (:result @state)
        error (j/get (:result @state) :error)]
    ;[:div (pr-str subscription)]
    ;[:pre (pr-str user)]
    ;[:pre (pr-str @state)]
    [:div
     [:h1 "AI Tweet Generator"]
     (cond
       (and result (not error))
       [:<>
        [component-back-button state]
        [component-compact-ui (:username @state) (:topic @state)]
        [:p "Hint: use Twitter's schedule function to queue up multiple tweets."]
        [:h3 "Generated Tweets" [component-twicon "👇️"]]
        [component-generated-tweet-list state user]]
       :else
       [:<>
        [:p "Use the AI tweet generator to generate tweets on any topic of your choice in your own voice (or the voice of any user).
            Tweet as yourself or choose which user you want to tweet like, and then enter a topic you'd like to tweet about.
            Once the app is done generating you can choose which tweets you want to post.
            No tweets will be posted without your permission."]
        (if user
          [:<>
           [:p "Change the username if you'd like to tweet like somebody else (case sensitive)."]
           [:p "Tweet like: "
            [:input
             {:on-change #(swap! state assoc :username (-> % .-target .-value))
              :placeholder "Twitter username"
              :value un
              #_#_ :on-key-down #(when (= (aget % "keyCode") 13)
                                   (initiate-user-tweet-fetch state un @search-type @limit))}]
            (when (and error (= (j/get result :target) "username"))
              [:p.errors error])]
           [:p "Enter the topic you'd like to generate tweets about.
               Write whatever you want in here.
               You can write a couple of words or a whole paragraph, it's up to you."]
           [:details
            [:summary "Click for topic ideas."]
            [:ul
             [:li "Something that happened to you today."]
             [:li "Copy and paste part of an article you read."]
             [:li "A good idea you heard recently."]
             [:li "Something useful you learned."]
             [:li "An existing tweet you'd like to re-word."]
             [:li "Paste in a quote from somebody famous. "
              [:a {:href "https://www.goodreads.com/quotes/"
                   :target "_BLANK"}
               "Find quotes on any topic here"] "."]
             [:li "A movie, book, or song you heard and why you liked it."]
             [:li "Something that helped you recently."]]]
           [:textarea
            {:placeholder "Topic you'd like to generate tweets about."
             :on-change (fn [ev]
                          (swap! state assoc :topic (-> ev .-target .-value))
                          (auto-resize-textarea (-> ev .-target)))
             :value (:topic @state)}]
           (cond
             (:fetching @state)
             [component-countdown (:fetching @state)]
             :else
             [:p [:button.primary
                  {:on-click #(initiate-generate-tweets! state un (:topic @state))}
                  "Generate tweets"] [:br]
              (when error [:span.errors error])
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
