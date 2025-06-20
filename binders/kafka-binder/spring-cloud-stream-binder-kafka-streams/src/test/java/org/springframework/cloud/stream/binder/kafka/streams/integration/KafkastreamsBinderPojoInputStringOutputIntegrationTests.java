/*
 * Copyright 2017-2024 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.stream.binder.kafka.streams.integration;

import java.time.Duration;
import java.util.Map;
import java.util.function.Function;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.kstream.Grouped;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.Materialized;
import org.apache.kafka.streams.kstream.TimeWindows;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.integration.test.util.TestUtils;
import org.springframework.kafka.config.StreamsBuilderFactoryBean;
import org.springframework.kafka.core.CleanupConfig;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.JsonSerde;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.condition.EmbeddedKafkaCondition;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Marius Bogoevici
 * @author Soby Chacko
 * @author Gary Russell
 */
@EmbeddedKafka(topics = "counts-id")
class KafkastreamsBinderPojoInputStringOutputIntegrationTests {

	private static EmbeddedKafkaBroker embeddedKafka;

	private static Consumer<String, String> consumer;

	@BeforeAll
	public static void setUp() throws Exception {
		embeddedKafka = EmbeddedKafkaCondition.getBroker();
		Map<String, Object> consumerProps = KafkaTestUtils.consumerProps(embeddedKafka, "group-id", false);
		consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
		DefaultKafkaConsumerFactory<String, String> cf = new DefaultKafkaConsumerFactory<>(
				consumerProps);
		consumer = cf.createConsumer();
		embeddedKafka.consumeFromAnEmbeddedTopic(consumer, "counts-id");
	}

	@AfterAll
	public static void tearDown() {
		consumer.close();
	}

	@Test
	void kstreamBinderWithPojoInputAndStringOuput(EmbeddedKafkaBroker embeddedKafka) throws Exception {
		SpringApplication app = new SpringApplication(ProductCountApplication.class);
		app.setWebApplicationType(WebApplicationType.NONE);
		ConfigurableApplicationContext context = app.run("--server.port=0",
				"--spring.jmx.enabled=false",
				"--spring.cloud.stream.function.bindings.process-in-0=input",
				"--spring.cloud.stream.function.bindings.process-out-0=output",
				"--spring.cloud.stream.bindings.input.destination=foos",
				"--spring.cloud.stream.bindings.output.destination=counts-id",
				"--spring.cloud.stream.kafka.streams.binder.configuration.commit.interval.ms=1000",
				"--spring.cloud.stream.kafka.streams.binder.configuration.default.key.serde"
						+ "=org.apache.kafka.common.serialization.Serdes$StringSerde",
				"--spring.cloud.stream.kafka.streams.binder.configuration.default.value.serde"
						+ "=org.apache.kafka.common.serialization.Serdes$StringSerde",
				"--spring.cloud.stream.kafka.streams.bindings.input.consumer.applicationId=ProductCountApplication-xyz",
				"--spring.cloud.stream.kafka.streams.binder.brokers="
						+ embeddedKafka.getBrokersAsString());
		try {
			receiveAndValidateFoo(embeddedKafka);
			// Assertions on StreamBuilderFactoryBean
			StreamsBuilderFactoryBean streamsBuilderFactoryBean = context
					.getBean("&stream-builder-process", StreamsBuilderFactoryBean.class);
			CleanupConfig cleanup = TestUtils.getPropertyValue(streamsBuilderFactoryBean,
					"cleanupConfig", CleanupConfig.class);
			assertThat(cleanup.cleanupOnStart()).isFalse();
			assertThat(cleanup.cleanupOnStop()).isFalse();
		}
		finally {
			context.close();
		}
	}

	private void receiveAndValidateFoo(EmbeddedKafkaBroker embeddedKafka) throws Exception {
		Map<String, Object> senderProps = KafkaTestUtils.producerProps(embeddedKafka);
		DefaultKafkaProducerFactory<Integer, String> pf = new DefaultKafkaProducerFactory<>(
				senderProps);
		KafkaTemplate<Integer, String> template = new KafkaTemplate<>(pf, true);
		template.setDefaultTopic("foos");
		template.sendDefault("{\"id\":\"123\"}");
		ConsumerRecord<String, String> cr = KafkaTestUtils.getSingleRecord(consumer,
				"counts-id");
		assertThat(cr.value().contains("Count for product with ID 123: 1")).isTrue();
	}

	@EnableAutoConfiguration
	public static class ProductCountApplication {

		@Bean
		public Function<KStream<Object, Product>, KStream<Integer, String>> process() {
			return input -> input.filter((key, product) -> product.getId() == 123)
					.map((key, value) -> new KeyValue<>(value, value))
					.groupByKey(Grouped.with(new JsonSerde<>(Product.class),
							new JsonSerde<>(Product.class)))
					.windowedBy(TimeWindows.ofSizeWithNoGrace(Duration.ofMillis(5000)))
					.count(Materialized.as("id-count-store")).toStream()
					.map((key, value) -> new KeyValue<>(key.key().id,
							"Count for product with ID 123: " + value));
		}

	}

	static class Product {

		Integer id;

		public Integer getId() {
			return id;
		}

		public void setId(Integer id) {
			this.id = id;
		}

	}

}
