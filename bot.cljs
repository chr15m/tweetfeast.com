(ns bot
  (:require
    [nbb.core :refer [*file*]]
    ["twitter-api-v2/dist" :refer [TwitterApi]]
    [clast.util :refer [env bail log]]
    [promesa.core :refer [let]]))

(def tw-keys {:appKey (env "TWITTER_API_APP_KEY")
              :appSecret (env "TWITTER_API_APP_SECRET")
              :accessToken (env "TWITTER_API_ACCESS_TOKEN")
              :accessSecret (env "TWITTER_API_ACCESS_SECRET")})

(def n *file*)

(doseq [[k v] tw-keys]
  (when (nil? v)
    (let [n (-> k name (.replace ":" "") (.replace #"([A-Z])" "_$1") .toUpperCase)]
    (bail (str "TWITTER_API_" n " is not set")))))

(let [tw (TwitterApi. (clj->js tw-keys))
      api (aget tw "v2")
      ;user (.currentUser tw)
      ;res (.get api "tweets/search/recent?query=sentiment analysis")
      ]
     ;(print res)
          ;(log n "User:" user)
          
          )
