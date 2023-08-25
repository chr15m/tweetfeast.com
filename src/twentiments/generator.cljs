(ns twentiments.generator
  (:require
    [promesa.core :as p]
    [applied-science.js-interop :as j]
    ["process" :refer [env]]
    ["htmlparser2" :refer [parseFeed]]
    ["openai" :refer [Configuration OpenAIApi]]  
    [sitefox.mail :refer [send-email]]
    [twentiments.filecache :refer [fetch-and-cache-url]]
    [twentiments.api :refer [log-event rnd-id set-user-data! get-user-data]]))

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
  "I'd like you to write some tweets for me that are designed to get a lot of engagement.

  I'm going to provide you with a list of tweets first and then I'm going to provide you with a topic or reference text to tweet about.
  I want you to write fifteen (15) tweets in the same voice and style as the list of tweets I'll provide.
  It's important that the tweets you write are stylistically similar to the reference tweets.
  It's important that the tweets are on the topic of the reference text I'll provide.

  Please make the tweets smart, funny, entertaining, interesting, poignant and/or insightful.
  Please use the tips and categories you listed above to write engaging tweets to inform the tweet writing process.

  Here is the list of reference tweets. Tweets are delimited by three dashes:")

(def prompt-2
  "Ok, now here is the topic or reference text I would like you to tweet about.

  Please generate fifteen (15) new tweets based on this topic or reference text.

  Bear in mind the tweet writing strategies you listed above and try to make the tweets follow the tips listed in form and structure.

  Please follow these rules:

  - Please not retweet or use the 'RT' shorthand.
  - Please do not reply or use the 'R' shorthand.
  - Please do not reference other users with the at (@) symbol.
  - Please do not number the tweets.
  - Please minimise the use of emojis unless they are used in the reference tweets.
  - Please avoid inventing new hashtags. Minimise hashtag use.

  Go into depth and write tweets that explain truths.
  Use a calm, cool, and friendly tone without emojis and hashtags.

  Most importantly, please output the tweets in JSON format as an array of strings with one array entry for each tweet.

  Before returning the JSON tweets structure please expand upon and summarize the topic and the points from it that will make engaging tweets.")

;   Please do not add any preamble before or any text after the JSON structure.
;  Only respond with valid JSON data and no other text.

(def prompt-good-tweets
  "Thinking like a smart and thoughtful person with a large following on Twitter and a strong social media presence, what are some tips for writing tweets that get a lot of likes, retweets, and are widely shared? Also what are the main categories of these types of tweets?")

(def prompt-assistant-good-tweets
  "Sure, here are some tips for writing tweets that resonate with your audience and get a lot of engagement:

1. **Be Authentic**: Share your genuine thoughts, experiences, and insights. Authenticity often attracts more engagement.

2. **Provide Value**: Offer useful tips, advice, or solutions related to your niche that your followers can benefit from.

3. **Use Visuals**: Incorporate eye-catching images, GIFs, or videos that complement your tweet's message.

4. **Use Humor**: Incorporate clever humor or witty remarks, but make sure it aligns with your brand's tone and values.

5. **Pose Questions**: Ask thought-provoking questions that encourage discussions and replies.

6. **Share Success Stories**: Highlight success stories, either your own or from your community, to inspire and motivate your followers.

7. **Use Quotes**: Share insightful or motivational quotes that resonate with your audience.

8. **Share Personal Stories**: Open up about your experiences, challenges, and lessons learned. People often connect with personal stories.

9. **Stay Timely**: Leverage current events, trending topics, or relevant hashtags to increase your tweet's visibility.

10. **Short and Snappy**: Keep your tweets concise, using clear language that's easy to understand.

Main categories of engaging tweets:

1. **Educational**: Share tips, tricks, and insights related to your expertise that your followers can learn from.

2. **Motivational**: Share inspirational quotes, personal anecdotes, or success stories that uplift your audience.

3. **Behind-the-Scenes**: Offer a glimpse into your creative process, projects you're working on, or a day in your life.

4. **Controversial/Thought-Provoking**: Share unconventional opinions or thought-provoking statements that spark discussions.

5. **Polls and Questions**: Engage your audience by asking questions or running polls on topics of interest.

6. **Announcements and Updates**: Share news about your projects, achievements, or upcoming events.

7. **Collaborations**: Tease or announce collaborations with other creators or businesses to generate excitement.

8. **Interactive Content**: Share puzzles, challenges, or interactive content that encourages your followers to participate.

Remember, consistency is key. Engaging with your audience, responding to comments, and participating in conversations will help you build a strong and active community on Twitter.")

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
                     :content prompt-good-tweets}
                    {:role "assistant"
                     :content prompt-assistant-good-tweets}
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
                                 :temperature 1}))
          output (j/get-in completion [:data :choices 0 :message :content])]
    (js/console.log "Done generating tweets.")
    (js/console.log output)
    output))

(def re-json-array (js/RegExp. "\\[(.*)\\]" "s"))

(defn generate-tweets-api [req res]
  ; TODO: guard against errors, timeouts etc.
  (let [username (->
                   (j/get-in req [:query :username])
                   .toLowerCase
                   (.replaceAll " " ""))
        topic (j/get-in req [:query :topic])
        user (j/get-in req [:session :user])
        start (js/Date.)]
    (log-event "last/request" (aget user "userId") user)
    (log-event "event/generate" (rnd-id) user {:u username :t topic})
    (if (and username topic)
      (p/let [user-data (get-user-data user)
              tweets (fetch-tweets username)
              generated (if tweets (generate-tweets tweets topic) "")
              extract-array (first (.exec re-json-array generated))
              parsed (try (js/JSON.parse extract-array)
                          (catch :default e
                            #js {:error "Error parsing response."
                                 :e e
                                 :original generated}))
              done (js/Date.)]
        (send-email (j/get env :ADMIN_EMAIL) (j/get env :ADMIN_EMAIL)
                    "TweetFeast generator run"
                    :text (js/JSON.stringify
                            (j/lit {:query (j/get req :query)
                                    :user (j/get user :userName)
                                    :user-data user-data
                                    :result parsed
                                    :time (-> (- done start) (/ 1000) int)})
                            nil 2))
        (js/console.log "user-data:" user-data)
        (when tweets
          (set-user-data! user
                          (j/update-in! user-data
                                        [:generator :uses]
                                        #(inc (or % 0)))))
        (if tweets
          (.json res (j/lit {:tweets parsed
                             :uses (j/get-in user-data [:generator :uses])}))
          (.json res (j/lit {:error "Sorry we couldn't find that user. Please check the username."
                             :target "username"}))))
      (-> res
          (.status 404)
          (.json "Nope. Parameters missing.")))))

#_ (p/let [topic "Brevity is the soul of wit and also good programming."
           ;topic "On investing time and money in your tools and not just working on projects."
           tweets (fetch-tweets "naval")
           result (generate-tweets tweets topic)]
     (js/console.log result))

