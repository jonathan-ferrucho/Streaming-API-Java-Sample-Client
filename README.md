# Mambu Streaming API Java Sample Client

This is a java sample project that implements a Streaming API Client. 

## Dependencies
The implementation uses the following dependencies:
  * [Apache Http Components library](https://hc.apache.org/httpcomponents-client-4.5.x/index.html)
  * [Google Gson library](https://github.com/google/gson)

dependency injection should be done based on the gradle version exp:

Before version 7 (testCompile group: 'junit', name: 'junit', version: '4.12')

After version 7 (testImplementation 'junit:junit:4.12')


## Runt test

run test method consume_events() in class StreamingApiClientShould

Add configuration in StreamingApiClient class:

MAMBU_ENDPOINT

Add configuration in StreamingApiClientShould class:

STREAMING_API_KEY,

in buildSubscription add the necessary topics 