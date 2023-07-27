(ns twentiments.ui.generator
  (:require
    [promesa.core :as p]
    ;[applied-science.js-interop :as j]
    [reagent.dom :as rd]
    [reagent.core :as r]
    [twentiments.common :refer [auth]]))

(defonce state (r/atom nil))

(defn initiate-generate-tweets! [state _*username _*topic]
  (swap! state assoc :fetching true)
  )

(defn component-home [_state user username topic]
  (let [_subscription (:subscription user)
        un (or @username (:username user))]
    ;[:div (pr-str subscription)]
    ;[:pre (pr-str user)]
    ;[:pre (pr-str @state)]
    [:div
     [:h1 "AI Tweet Generator"]
     [:p "Use the AI tweet generator to generate tweets on any topic of your choice in the voice of any twitter user.
         Choose which user you want to tweet like, and then enter a topic you'd like to tweet about.
         Once the app is done generating you can choose which tweets you want to post.
         No tweets will be posted without your permission."]
     (if user
       [:<>
        [:p "Enter the username of someone you'd like to tweet as (case sensitive)."]
        [:p "Tweet like: "
         [:input
          {:on-change #(reset! username (-> % .-target .-value))
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
          :on-change #(reset! topic (-> % .-target .-value))
          :value @topic}]
        [:p [:button.primary
             ;{:on-click #(initiate-generate-tweets! @username @topic)}
             "Generate tweets"] [:br]
         [:small "Note: no tweets will be posted. You will be shown tweets and you can choose what to post."]]]
       [:a.button.primary {:href "/login?next=/ai-tweet-generator"} "Sign in to start"])]))

(defn ^:dev/after-load reload! []
  (js/console.log "reload!")
  (p/let [user (auth)]
    (rd/render
      [:span.generator
       [:section#app
        [:section.ui-layout-container
         [component-home state user (r/atom nil) (r/atom nil)]]]]
      (js/document.querySelector "main"))))

(defn main! []
  (js/console.log "main!")
  (reload!))
