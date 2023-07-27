(ns twentiments.generator
  (:require
    [promesa.core :as p]
    [applied-science.js-interop :as j]
    ["process" :refer [env]]
    ["htmlparser2" :refer [parseFeed]]
    ["openai" :refer [Configuration OpenAIApi]]  
    [twentiments.filecache :refer [fetch-and-cache-url]]))

(defn process-feed [url]
  (p/let [body (fetch-and-cache-url url)
          feed (parseFeed body)
          items (j/get feed :items)]
    items))

(defn fetch-tweets [user]
  (js/console.log "Fetching tweets for" user)
  (let [;url (str "https://twiiit.com/search/rss?q=(from%3A" user ")+-filter%3Anativeretweets+-filter%3Areplies&src=typed_query")
        ;url (str "https://twiiit.com/" user "/rss")]
        url (str "https://twiiit.com/" user "/with_replies/rss")]
    (js/console.log url)
    (process-feed url)))

(def role
  "You are a helpful assistant.")

(def prompt-1
  "I'd like you to write some tweets for me.
  I'm going to provide you with a list of tweets first and then I'm going to provide you with a topic or reference text to tweet about.
  I want you to write ten (10) tweets in the same voice as the list of tweets I'll provide.
  It's important that the tweets you write are stylistically similar to the reference tweets.
  Do not retweet using RT or reference other users with the at symbol. Do not number the tweets.
  It's important that the tweets are on the topic of the reference text I'll provide.

  Please make the tweets smart, funny, entertaining, interesting, poignant and/or insightful.

  Here is the list of reference tweets. Tweets are delimited by three dashes.")

(def prompt-2
  "Ok, now here is the topic or reference text I would like you to tweet about.
  Please generate new tweets based on this topic or reference text.
  Please output the tweets in JSON format as an array of strings with one array entry for each tweet.")

;Tweet like one of the a greatest minds in the modern world.

; Please make the tweets smart, funny, insightful, entertaining and interesting.
;  Once you have generated the tweets please set them by calling the `set_tweets` function provided.
; , and then set them with the `set_tweets` function.

(defn generate-tweets [tweets topic]
  (js/console.log "Generating tweets")
  (p/let [;schema (-> (fs/readFileSync "schema-tweets.json") js/JSON.parse)
          config (Configuration. #js {:apiKey (j/get env :OPENAI_API_KEY)})
          openai (OpenAIApi. config)
          tweet-structure (.join (.map tweets #(j/get % :title)) "\n\n---\n\n")
          tweets-prompt (str prompt-1 "\n\n---\n\n" tweet-structure "\n\n---\n\n")
          topic-prompt (str prompt-2 "\n\n###\n" topic "\n###\n\n")
          messages [{:role "system"
                     :content role}
                    {:role "user"
                     :content (str tweets-prompt "\n\n" topic-prompt)}
                    {:role "assistant"
                     :content "Ok, I'm ready for the topic that I will use to generate new tweets in the style of the tweets you have given me as a reference."}
                    {:role "user"
                     :content (str prompt-2 "\n\n###\n" topic "\n###\n\n")}]
          completion (j/call
                       openai
                       :createChatCompletion
                       (clj->js {;:model "gpt-3.5-turbo-16k-0613"
                                 ;:model "gpt-3.5-turbo-0613"
                                 ;:model "gpt-3.5"
                                 :model "gpt-4"
                                 :messages messages
                                 ;:functions [{:name "set_tweets" :parameters schema}]
                                 ;:function_call {:name "set_tweets"}
                                 :temperature 1}))]
    (j/get-in completion [:data :choices 0 :message :content])))

(defn generate-tweets-api [req res]
  ; TODO: guard against errors, timeouts etc.
  (let [username (j/get-in req [:query :username])
        topic (j/get-in req [:query :topic])]
    (if (and username topic)
      (p/let [tweets (fetch-tweets username)
              generated (generate-tweets tweets topic)]
        (.json res generated))
      (-> res
          (.status 404)
          (.json res "Nope. Parameters missing.")))))

#_ (p/let [topic "Brevity is the soul of wit and also good programming."
           ;topic "On investing time and money in your tools and not just working on projects."
           tweets (fetch-tweets "naval")
           result (generate-tweets tweets topic)]
     (js/console.log result))

