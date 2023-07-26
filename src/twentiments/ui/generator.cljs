(ns twentiments.ui.generator
  (:require
    [promesa.core :as p]
    ;[applied-science.js-interop :as j]
    [reagent.dom :as rd]
    [reagent.core :as r]
    [twentiments.common :refer [auth]]))

(defonce state (r/atom nil))

(defn component-home [user-data state]
  [:div
      [:h1 "Hello"]
      [:pre (pr-str user-data)]
      [:pre (pr-str @state)]])

(defn ^:dev/after-load reload! []
  (js/console.log "reload!")
  (p/let [user-data (auth)]
    (rd/render
      [:span
       [:section#app
        [:section.ui-layout-container
         [component-home user-data state]]]]
      (js/document.querySelector "main"))))

(defn main! []
  (js/console.log "main!")
  (reload!))
