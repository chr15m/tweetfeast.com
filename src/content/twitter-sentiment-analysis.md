# Twitter Sentiment Analysis

Let's take a quick look at how you can do sentiment analysis on tweets.
Whether you're doing stock prediction or trying to measure brand sentiment or something else entirely, we've got you covered.
We're going to look at the steps and the libraries you'll need to do sentiment analysis in Python or Javascript.

**If you don't want to write code or fiddle with API keys we've got you covered**. You can download a CSV file of tweets with sentiment analysis already included. Just [sign in with Twitter](/login) to get started.

![Twitter Sentiment Analysis](/content/twitter-sentiment-analysis.png)

<!-- <a href="/login" class="ui-component-button ui-component-button-normal ui-component-button-primary">Sign in with Twitter</a> -->

There are two main steps in doing sentiment analysis on tweets. The first step is to get the data, and the second step is to perform the sentiment analysis. To get the data you will need an developer API key from Twitter. Once you have the data you can perform sentiment analysis using a library for your language. Let's go over the details.

## Contents

 * [Get an API key](#get-an-api-key)
 * [Step 1 (Python) Download tweets](#download-tweets-with-python)
 * [Step 1 (JavaScript) Download tweets](#download-tweets-with-javascript)
 * [Step 2 (Python) Twitter sentiment analysis](#twitter-sentiment-analysis-with-python)
 * [Step 2 (JavaScript) Twitter sentiment analysis](#twitter-sentiment-analysis-with-javascript)

## Get an API key

Before you can interact with the Twitter API at all you'll need an API key. To get an API key you have to sign up for developer access and then create a project in the developer portal. Go to [developer.twitter.com/en/apply-for-access](https://developer.twitter.com/en/apply-for-access) to get started. Once you have developer access you can create a "project" through the portal interface.

![Create Twitter API key](/content/twitter-create-api-key.png)

Once you have created a project you will have the opportunity to copy or write down the API keys.
Do this carefully because you will only be shown the keys once.
A good place to store the keys is in a file of environment variables which you can later read from your code. Something like this:

```bash
export TWITTER_API_KEY=...
export TWITTER_API_SECRET=...
export TWITTER_API_BEARER_TOKEN=...
export TWITTER_ENVIRONMENT_NAME=...
```

The important key is the Bearer Token. That's the one we'll use to download tweets in the next step.

## Step 1: Download tweets

Now that you have API keys sorted out you can write the code to download the tweets you want.
There are different API endpoints for you to use depending on what kind of tweets you want to analyze.
For the sake of simplicity we'll assume you want to download one user's timeline of tweets to perform your analysis on.
If you want to get a different set of tweets, for example a search result, then you can consule [the API documentation](https://developer.twitter.com/en/docs/twitter-api/tweets/search/introduction).

Now let's take a look at how to download the tweets from a users timeline in Python and JavaScript.

### Download tweets with Python

We'll use the Tweepy library to download tweets by Elon Musk to perform sentiment analysis on later.
First you will want to install the `tweepy` library:

```bash
pip install tweepy
```

Here's a simple script that fetches all tweets by `@elonmusk`. Save it as `tweets.py`.

```python
import os
import tweepy

username = "elonmusk"

c = tweepy.client.Client(bearer_token=os.environ["TWITTER_API_BEARER_TOKEN"])
u = c.get_user(username=username)
user_id = u.data["id"]
tweets = c.get_users_tweets(user_id, exclude="retweets,replies")
```

To see the output put `print(tweets)` at the end of the file and then run it like this:

```bash
TWITTER_API_BEARER_TOKEN=... python tweets.py
```

Great, now that you have some tweets you can do the next step; sentiment analysis.

### Download tweets with JavaScript

We'll use the `twitter-api-v2` library to download tweets by Elon Musk to perform sentiment analysis on later.
First you will want to install the `twitter-api-v2` library:

```bash
npm install twitter-api-v2
```

Here's a simple script that fetches all tweets by `@elonmusk`. Save it as `tweets.js`.

```javascript
const t = require("twitter-api-v2");

const username = "elonmusk";

(async function x() {
  const client = new t.TwitterApi(process.env["TWITTER_API_BEARER_TOKEN"]);
  const user = await client.v2.get("users/by?usernames=" + username);
  const user_id = user["data"][0]["id"];
  const tweets = await client.v2.get("users/" + user_id + "/tweets?exclude=retweets,replies");
})();
```

To see the output put `console.log(tweets["data"]);` at the end of the file inside the function and run it like this:

```bash
TWITTER_API_BEARER_TOKEN=... node tweets.js
```

Great, now that you have some tweets you can do the next step; sentiment analysis.

## Step 2: Sentiment analysis

Ok now let's get to the actual sentiment analysis part.
We'll look at libraries you can use for JavaScript and Python.

### Twitter sentiment analysis with Python

There are two basic ways to do sentiment analysis in Python.
The simplest is to use the AFINN-165 word lists.
The more complicated way is to use the Naive Bayes Classifier from NLTK.
Let's take a look at the simple way first.

To do the simple analysis we'll install the `afinn` library using pip:

```bash
pip install afinn
```

Now we add some simple code to our `tweets.py` file above to classify every tweet:

```python
from afinn import Afinn

afinn = Afinn()
for t in tweets.data:
    score = afinn.score(t["text"])
    print(score, t["text"])
```

This will calculate a sentiment score based on the number of positive words minus the number of negative words.
It will print out the score next to each tweet.
If the score is zero then the tweet has a neutral sentiment.
If it is greater than zero then the tweet has more positive words.
If it is less than zero it has more negative words.

This is a simple way to get the sentiment of tweets that doesn't rely on any external data or additional steps.
A more sophisticated way is to use a bayes classifier.
This requires training a model with a set of tweets that you know are positive and negative.
You can read more about doing [sentiment analysis using NLTK's bayes classifier on the Digital Ocean blog](https://www.digitalocean.com/community/tutorials/how-to-perform-sentiment-analysis-in-python-3-using-the-natural-language-toolkit-nltk).

### Twitter sentiment analysis with JavaScript

We're going to ue the `wink-sentiment` library to do simple sentiment analysis.
The way it works is to count the number of positive words and subtract the number of negative words.
Start by installing the library:

```bash
npm install wink-sentiment
```

First add the code to load the `wink-sentiment` library at the start of `tweet.js`:

```javascript
const sentiment = require("wink-sentiment");
```

Now add the code for analyzing each tweet to the `tweets.js` file.
Add this at the end of `tweet.js` inside the function.

```javascript
  tweets["data"].forEach(t => {
    const s = sentiment(t["text"]);
    console.log(s["score"], t["text"]);
  });
```

This will calculate a sentiment score based on the number of positive words minus the number of negative words.
It will print out the score next to each tweet.
If the score is zero then the tweet has a neutral sentiment.
If it is greater than zero then the tweet has more positive words.
If it is less than zero it has more negative words.

This is a simple way to get the sentiment of tweets that doesn't rely on any external data or additional steps.
A more sophisticated way is to use a bayes classifier.
This requires training a model with a set of tweets that you know are positive and negative.
You can use the npm package [wink-naive-bayes-text-classifier](https://www.npmjs.com/package/wink-naive-bayes-text-classifier) to do this.

## That's all folks

So that's how you do twitter sentiment analysis in Python and JavaScript.

Just a reminder that **if you don't want to write code or fiddle with API keys you can download a CSV file of tweets with sentiment analysis already included**. Just [sign in with Twitter](/login) to get started.

Thanks for reading.
