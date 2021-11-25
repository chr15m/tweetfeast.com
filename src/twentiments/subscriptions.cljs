(ns twentiments.subscriptions
  (:require
    ["motionless" :as motionless]
    [shadow.resource :as rc]
    [promesa.core :as p]
    [applied-science.js-interop :as j]
    [sitefox.util :refer [env-required]]
    [sitefox.html :refer [render]]
    [sitefox.web :refer [build-absolute-uri]]
    [twentiments.interface :refer [update-nav]]
    [twentiments.api :refer [twitter get-user-profile]]
    ["stripe" :as Stripe]))

(def price-ids (-> (env-required "PRICE_IDS") (.split ",")))
(def stripe (Stripe (env-required "STRIPE_SK")))

(defn view-subscribe [req res]
  (p/let [template (rc/inline "index.html")
          user (j/get-in req [:session :user])
          dom (motionless/dom template)
          el (j/call-in dom [:h :bind] nil)
          $ (j/call-in dom [:$ :bind] nil)
          $$ (j/call-in dom [:$$ :bind] nil)
          app ($ "main")
          pricing ($ ".ui-section-pricing")
          links ($$ ".ui-section-pricing a[href=\"/login\"]")]
    (when user
      (p/let [user-id (aget user "userId")
              tw (twitter user)
              user-profile (aget user "profile")
              user-profile (if user-profile user-profile (get-user-profile tw user-id))
              nav ($ "nav")
              signout-link (el "a" #js {:href "/logout"
                                        :role "link"
                                        :aria-label "Sign out"
                                        :className "ui-section-header--nav-link"}
                               "Sign out")
              profile-image (el "div" #js {:className "user-profile"}
                                (el "a" (clj->js {:href (str "https://twitter.com/" (aget user-profile "username"))
                                                  :target "_BLANK"})
                                    (el "img" (clj->js {:src (aget user-profile "profile_image_url")}))))]
        (aset user "profile" user-profile)
        (aset nav "innerHTML" "")
        (.appendChild nav signout-link)
        (.appendChild nav profile-image)
        (doseq [l (range (count links))]
          (let [link (aget links l)
                parent (aget link "parentNode")
                form (el "form" (clj->js {:action "/account/begin-subscription"
                                          :method "POST"}))
                tier (js/parseInt (.getAttribute link "data-tier"))]
            (.remove link)
            (aset form "innerHTML"
                  (render [:fieldset
                           [:input {:type "hidden"
                                    :name "tier"
                                    :defaultValue tier}]
                           [:input {:name "_csrf" :type "hidden" :default-value (j/call req :csrfToken)}]
                           [:button {:type :submit
                                     :class (str "ui-component-button ui-component-button-big"
                                                 (if (= l 1)
                                                   " ui-component-button-primary"
                                                   " ui-component-button-secondary"))
                                     ; undo minimal-stylesheet modifications
                                     :style {:box-shadow :none
                                             :text-transform :none
                                             :font-size "var(--ui-typography-p)"}}
                            "Checkout"]]))
            (.appendChild parent form))
          (aset l "textContent" "Choose"))))
    (aset app "innerHTML" "")
    (.appendChild app pricing)
    (aset ($ "h2") "textContent" "Plans & pricing")
    (aset ($ ".ui-text-intro") "textContent" "Choose the plan that suits your usage.")
    (update-nav dom user)
    (doseq [l links]
      (let [href (.getAttribute l "href")]
        (.setAttribute l "href" (str href "?next=/subscribe"))))
    (.send res (j/call dom :render))))

(defn begin-subscription [req res]
  (let [user (j/get-in req [:session :user])
        user-id (when user (aget user "userId"))]
    (if user-id
      (p/let [params (aget req "body")
              tier (aget params "tier")
              price (aget price-ids tier)
              session (j/call-in stripe [:checkout :sessions :create]
                                 (clj->js {:billing_address_collection "auto"
                                           :payment_method_types ["card"]
                                           :line_items [{:price price :quantity 1}]
                                           :metadata {:user-id user-id}
                                           :mode (if (= tier "0") "payment" "subscription")
                                           :success_url (build-absolute-uri req "/account/payment-complete")
                                           :cancel_url (build-absolute-uri req "/subscribe")}))]
        (.redirect res 303 (aget session "url")))
      (.redirect 303 (build-absolute-uri req "/subscribe")))))

(defn payment-complete [req res]
  (p/let [template (rc/inline "index.html")
          ;user (j/get-in req [:session :user])
          dom (motionless/dom template)
          ;el (j/call-in dom [:h :bind] nil)
          $ (j/call-in dom [:$ :bind] nil)
          ;$$ (j/call-in dom [:$$ :bind] nil)
          app ($ "main")]
    (aset app "innerHTML"
          (render [:section {:class "ui-section-articles"}
                   [:div {:class "ui-layout-container"}
                    [:h2 "Thank you"]
                    [:p "Hey, thanks for your payment."]
                    [:p "Your current plan is ..."]
                    [:p "If you need to change your plan visit [CUSTOMER PORTAL LINK]."]]]))
    (.send res (j/call dom :render))))
