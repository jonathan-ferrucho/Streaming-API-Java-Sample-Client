package streamingapi.client;

import static java.lang.String.format;
import static java.util.Collections.singletonList;
import static java.util.Objects.nonNull;
import static java.util.logging.Level.WARNING;
import static org.apache.http.HttpStatus.SC_CREATED;
import static org.apache.http.HttpStatus.SC_NO_CONTENT;
import static org.apache.http.HttpStatus.SC_OK;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.logging.Logger;

import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;

import com.google.gson.Gson;

import streamingapi.client.exception.CommitCursorException;
import streamingapi.client.exception.SubscriptionException;
import streamingapi.client.http.HttpClient;
import streamingapi.client.http.Response;
import streamingapi.client.model.Batch;
import streamingapi.client.model.Cursor;
import streamingapi.client.model.CursorWrapper;
import streamingapi.client.model.Subscription;
import streamingapi.client.processor.EventsProcessor;

/**
 * Client in charge with streaming api operations.
 * <ul>
 * <li>
 * Create subscriptions: The subscription is needed to be able to consume events from EventTypes.
 * </li>
 * <li>
 * Consume events: Starts a new stream for reading events from this subscription. The data will be
 * automatically rebalanced between streams of one subscription.
 * </li>
 * <li>
 * Commit cursor:  After a batched is processed the cursor is committed. The commit uses the endpoint
 * for committing offsets of the subscription.
 * </li>
 * </ul>
 *
 * @author dancojocaru
 */
public class StreamingApiClient {

	private final static Logger LOGGER = LoggerFactory.getLogger(StreamingApiClient.class.getName());

	private static final int COMMIT_TIMEOUT = 60; // seconds

	private static String MAMBU_ENDPOINT = "http://localHost:8080";
	private static String SUBSCRIPTION_ENDPOINT = MAMBU_ENDPOINT + "/api/v1/subscriptions";
	private static String EVENTS_ENDPOINT = SUBSCRIPTION_ENDPOINT + "/%s/events?batch_flush_timeout=5&batch_limit=1&commit_timeout=" + COMMIT_TIMEOUT;
	private static String CURSORS_ENDPOINT = SUBSCRIPTION_ENDPOINT + "/%s/cursors";
	private static String CONTENT_TYPE = "Content-Type";
	private static String CONTENT_TYPE_VALUE = "application/json";

	private static String API_KEY = "apikey";
	private static String MAMBU_STREAM_ID_HEADER_FIELD = "X-Mambu-StreamId";

	private static final String SLASH = "/";

	private Gson gson;

	private HttpClient client;

	private EventsProcessor processor;

	private StreamMonitor monitor;

	/**
	 * @param client    Http client used to make http requests.
	 * @param gson      Gson instance used to serialize/deserialize request and responses.
	 * @param processor A processor that handles the received events when listening to a stream.
	 * @param monitor   execution monitor
	 */
	public StreamingApiClient(HttpClient client, Gson gson, EventsProcessor processor, StreamMonitor monitor) {

		this.client = client;
		this.gson = gson;
		this.processor = processor;
		this.monitor = monitor;
	}

	/**
	 * Create subscription
	 *
	 * @param subscription    Subscription to create.
	 * @param streamingApiKey Streaming api key used to for authentication.
	 * @return created subscription
	 */
	public Subscription createSubscription(Subscription subscription, String streamingApiKey) throws IOException {

		HttpPost httpPost = buildSubscriptionHttpPost(subscription, streamingApiKey);

		HashMap<Integer,String> histograma= new HashMap<Integer, String>();

		histograma.put(1,"1: ");
		histograma.put(2,"2: ");
		histograma.put(3,"3: ");
		histograma.put(4,"4: ");
		histograma.put(5,"5: ");
	 histograma.forEach((k, v) -> System.out.println(v));

		return getSubscription(response);
	}

	/**
	 * Consume events of based on a subscription.
	 * When the connection to streaming api is lost, the connection is reinitialized if the monitor approves.
	 * Info: The connection to streaming api can be lost when the streaming api events store closes the stream because the stream timeout is reached or due to unpredictable network events.
	 *
	 * @param subscriptionId  subscription id
	 * @param streamingApiKey Streaming api key used to for authentication.
	 */
	public void consumeEvents(String subscriptionId, String streamingApiKey) throws Exception {

		while (monitor.shouldContinue()) {
			try {
				URLConnection connection = createConnection(subscriptionId, streamingApiKey);

				LOGGER.info("Successfully initialized connection to streaming api.");

				String streamId = connection.getHeaderField(MAMBU_STREAM_ID_HEADER_FIELD);

				try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {

					String inputLine;

					while ((inputLine = reader.readLine()) != null) {

						LOGGER.info(inputLine);

						Batch batch = gson.fromJson(inputLine, Batch.class);

						if (nonNull(batch) && nonNull(batch.getEvents())) {

							processor.process(batch.getEvents());
							Response response = commitCursor(batch.getCursor(), streamId, subscriptionId, streamingApiKey);

							if (!isCommitCursorSuccessful(response)) {
								String msg = format("Error while committing cursor. Status code: %d. Error: %s", response.getStatusCode(), response.getResponse());
								throw new CommitCursorException(msg);
							}
						}
					}
				}
			} catch (Exception e) {

				LOGGER.log(WARNING, "Error while processing events ", e);

				int retryInterval = COMMIT_TIMEOUT + 5; // seconds
				LOGGER.log(WARNING, format("Sleeping %d seconds before retrying", retryInterval));
				Thread.sleep(retryInterval * 1000);
			}

			LOGGER.warning("Trying to reinitialize connection to streaming api.");
		}
	}

	/**
	 * Deletes existing subscription
	 *
	 * @param subscriptionId the id of the subscription to be deleted
	 * @return the response of the operation
	 */
	public Response deleteSubscription(String subscriptionId, String streamingApiKey) {

		HttpDelete httpDelete = buildSubscriptionHttpDelete(subscriptionId, streamingApiKey);

		return client.executeRequest(httpDelete);
	}

	private URLConnection createConnection(String subscriptionId, String streamingApiKey) throws IOException {

		URL url = new URL(format(EVENTS_ENDPOINT, subscriptionId));
		URLConnection connection = url.openConnection();
		connection.setRequestProperty(API_KEY, streamingApiKey);
		return connection;
	}

	private Response commitCursor(Cursor cursor, String streamId, String subscriptionId, String streamingApiKey)
			throws Exception {

		HttpPost httpPost = createCursorsHttpPost(streamId, subscriptionId, streamingApiKey);
		String jsonBody = gson.toJson(new CursorWrapper(singletonList(cursor)));
		httpPost.setEntity(new StringEntity(jsonBody));
		return client.executeRequest(httpPost);
	}

	private HttpPost createCursorsHttpPost(String streamId, String subscriptionId, String streamingApiKey) {

		HttpPost httpPost = new HttpPost(format(CURSORS_ENDPOINT, subscriptionId));
		httpPost.setHeader(CONTENT_TYPE, CONTENT_TYPE_VALUE);
		httpPost.setHeader(MAMBU_STREAM_ID_HEADER_FIELD, streamId);
		httpPost.setHeader(API_KEY, streamingApiKey);
		return httpPost;
	}

	private HttpPost buildSubscriptionHttpPost(Subscription subscription, String streamingApiKey) throws UnsupportedEncodingException {

		StringEntity subscriptionStringEntity = toStringEntity(subscription);
		HttpPost httpPost = getHttpPost(streamingApiKey);
		httpPost.setEntity(subscriptionStringEntity);
		return httpPost;
	}

	private StringEntity toStringEntity(Subscription subscription) throws UnsupportedEncodingException {

		String jsonBody = gson.toJson(subscription);
		return new StringEntity(jsonBody);
	}

	private HttpPost getHttpPost(String streamingApiKey) {

		HttpPost httpPost = new HttpPost(SUBSCRIPTION_ENDPOINT);
		httpPost.setHeader(CONTENT_TYPE, CONTENT_TYPE_VALUE);
		httpPost.setHeader(API_KEY, streamingApiKey);
		return httpPost;
	}

	private Subscription getSubscription(Response response) throws SubscriptionException {

		if (!isCreateSubscriptionSuccessful(response)) {
			throw new SubscriptionException(response.getResponse());
		}
		return gson.fromJson(response.getResponse(), Subscription.class);
	}

	private boolean isCommitCursorSuccessful(Response response) {

		return response.getStatusCode() == SC_NO_CONTENT || response.getStatusCode() == SC_OK;
	}

	private boolean isCreateSubscriptionSuccessful(Response response) {

		return response.getStatusCode() == SC_OK || response.getStatusCode() == SC_CREATED;
	}

	private HttpDelete buildSubscriptionHttpDelete(String subscriptionId, String streamingApiKey) {

		HttpDelete httpDelete = new HttpDelete(buildDeleteSubscriptionEndpoint(subscriptionId));
		httpDelete.setHeader(API_KEY, streamingApiKey);
		return httpDelete;
	}

	private String buildDeleteSubscriptionEndpoint(String subscriptionId) {

		return SUBSCRIPTION_ENDPOINT + SLASH + subscriptionId;
	}
}
