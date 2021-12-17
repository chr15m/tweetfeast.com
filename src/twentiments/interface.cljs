(ns twentiments.interface
  (:require
    [applied-science.js-interop :as j]))

(defn update-nav [dom user]
  (when user
    (let [el (j/call-in dom [:h :bind] nil)
          user-profile (aget user "profile")
          nav (j/call dom :$ "nav")
          articles-link (el "a" #js {:href "/articles"
                                     :role "link"
                                     :aria-label "Articles"
                                     :className "ui-section-header--nav-link"}
                            "Articles")
          signout-link (el "a" #js {:href "/logout"
                                    :role "link"
                                    :aria-label "Sign out"
                                    :className "ui-section-header--nav-link"}
                           "Sign out")
          app-link (el "a" #js {:href "/exporter"
                                :role "link"
                                :aria-label "Exporter"
                                :className "ui-section-header--nav-link"}
                       "Exporter")
          profile-image (el "div" #js {:className "user-profile"}
                            (el "a" (clj->js {:href "/account"})
                                (el "img" (clj->js {:src (aget user-profile "profile_image_url")}))))]
      (aset nav "innerHTML" "")
      ;(.remove ($ "#sign-in-link"))
      (.appendChild nav articles-link)
      (.appendChild nav app-link)
      (.appendChild nav signout-link)
      (.appendChild nav profile-image))))
