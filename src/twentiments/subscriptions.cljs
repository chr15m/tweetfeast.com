(ns twentiments.subscriptions
  (:require
    ["motionless" :as motionless]
    [shadow.resource :as rc]
    [promesa.core :as p]
    [applied-science.js-interop :as j]
    [sitefox.db :as db]
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
                                           :metadata {:user-id user-id
                                                      :tier tier
                                                      :price (aget price-ids tier)}
                                           :mode (if (= tier "0") "payment" "subscription")
                                           :success_url (build-absolute-uri req "/account")
                                           :cancel_url (build-absolute-uri req "/subscribe")}))]
        (.redirect res 303 (aget session "url")))
      (.redirect 303 (build-absolute-uri req "/subscribe")))))

(defn list-one-time-charges []
  (let [charges (atom [])]
    (js/Promise.
      (fn [res _err]
        (->
          (j/call-in stripe [:events :list] (clj->js {:created {:gte (-> (js/Date.) (.getTime) (/ 1000) int (- (* 60 60 24 2)))}
                                                      :type "checkout.session.completed"
                                                      ;:type "invoice.payment_succeeded"
                                                      ;:type "payment_intent.succeeded"
                                                      ;:expand ["payment_intent"]
                                                      }))
          (j/call :autoPagingEach (fn [charge]
                                    (swap! charges conj charge)))
          (j/call :then (fn [] (res (clj->js @charges)))))))))

(defn is-user-one-time-charge [charge user-id]
  (let [metadata (j/get-in charge ["data" "object" "metadata"])
        mode (j/get-in charge ["data" "object" "mode"])]
    (js/console.log "checking charge" mode metadata user-id)
    (and (aget metadata "tier")
         (= (aget metadata "user-id") user-id)
         (= mode "payment"))))

(defn get-one-time-sub [user-id]
  (p/let [one-time-charges (list-one-time-charges)
          charge (last (filter #(is-user-one-time-charge % user-id) one-time-charges))]
    (js/console.log "charge" charge)
    charge))

(defn is-valid [_sub])

(defn get-sub [_user-id _tier]
  ; TODO: implement this
  )

(defn get-user-subscription [user-id]
  (p/let [kv (db/kv "subscriptions")
          sub (.get kv user-id)]
    (if (is-valid sub)
      sub
      (or
        (get-sub user-id 2)
        (get-sub user-id 1)
        (get-one-time-sub user-id)))))

(defn get-and-set-subscription [user-id]
  (p/let [sub (get-user-subscription user-id)
          kv (db/kv "subscriptions")
          _set (.set kv user-id sub)]
    sub))

(defn account [req res]
  (p/let [user (j/get-in req [:session :user])
          template (rc/inline "index.html")
          dom (motionless/dom template)
          ;el (j/call-in dom [:h :bind] nil)
          $ (j/call-in dom [:$ :bind] nil)
          ;$$ (j/call-in dom [:$$ :bind] nil)
          app ($ "main")
          subscription (get-and-set-subscription (aget user "userId"))
          tier (js/parseInt (j/get-in subscription ["data" "object" "metadata" "tier"]))
          tier-description (get {0 "24hr single-payment"
                                 1 "Monthly subscription"
                                 2 "Annual subscription"} tier)]
    ; (js/console.log (clj->js subscription))
    ;(js/console.log (get-user-subscription ))
    (aset app "innerHTML"
          (render [:section {:class "ui-section-articles"}
                   [:div {:class "ui-layout-container"}
                    [:h2 "Thank you"]
                    [:p "Hey, thanks for your subscription."]
                    [:p "Your current plan is " tier-description "."]
                    [:p "If you need to change your plan: " [:a.button {:href "/account/portal"} "visit the customer portal"]]]]))
    (.send res (j/call dom :render))))

(defn customer-portal [req res]
  (p/let [user (j/get-in req [:session :user])
          subscription (get-and-set-subscription (aget user "userId"))
          customer-id (j/get-in subscription ["data" "object" "customer"])
          session (j/call-in stripe
                             [:billingPortal :sessions :create]
                             (clj->js {:customer customer-id
                                       :return_url (build-absolute-uri req "/account")}))]
    (.redirect res (aget session "url"))))
