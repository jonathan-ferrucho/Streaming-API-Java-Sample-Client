package streamingapi.client.processor;

import static java.lang.System.out;

import java.util.List;

import com.google.gson.Gson;
import streamingapi.client.model.Event;

/**
 * Processor that can print events.
 *
 * @author dancojocaru
 */
public class PrintEventsProcessor implements EventsProcessor {

	@Override
	public void process(List<Event> events) {

		Gson gson = new Gson();
		events.forEach(event -> {
			out.println("--------------EVENT RECEIVED-------------");
			out.println("Topic : " + event.getMetadata().getEventType());
			out.println("Template name :" + event.getTemplateName());
			out.println("Template body :" + gson.toJson(event.getBody()));
		});

	}
}
