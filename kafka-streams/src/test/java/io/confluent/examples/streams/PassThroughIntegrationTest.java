package io.confluent.examples.streams;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.kstream.KStreamBuilder;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Future;

import io.confluent.examples.streams.kafka.EmbeddedSingleNodeKafkaCluster;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration test that reads data from an input topic and writes the same data as-is to
 * a new output topic, using an embedded Kafka cluster.
 */
public class PassThroughIntegrationTest {

  private static EmbeddedSingleNodeKafkaCluster cluster = null;
  private static String inputTopic = "inputTopic";
  private static String outputTopic = "outputTopic";

  @BeforeClass
  public static void startKafkaCluster() throws Exception {
    cluster = new EmbeddedSingleNodeKafkaCluster();
    cluster.createTopic(inputTopic);
    cluster.createTopic(outputTopic);
  }

  @AfterClass
  public static void stopKafkaCluster() throws IOException {
    if (cluster != null) {
      cluster.stop();
    }
  }

  @Test
  public void shouldWriteTheInputDataAsIsToTheOutputTopic() throws Exception {
    List<String> inputValues = Arrays.asList(
        "hello world",
        "the world is not enough",
        "the world of the stock market is coming to an end"
    );

    //
    // Step 1: Configure and start the Streams job.
    //
    KStreamBuilder builder = new KStreamBuilder();

    Properties streamsConfiguration = new Properties();
    streamsConfiguration.put(StreamsConfig.JOB_ID_CONFIG, "pass-through-integration-test");
    streamsConfiguration.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, cluster.bootstrapServers());
    streamsConfiguration.put(StreamsConfig.ZOOKEEPER_CONNECT_CONFIG, cluster.zookeeperConnect());
    streamsConfiguration.put(StreamsConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    streamsConfiguration.put(StreamsConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    streamsConfiguration.put(StreamsConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    streamsConfiguration.put(StreamsConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

    // Write the input data as-is to the output topic.
    builder.stream(inputTopic).to(outputTopic);

    KafkaStreams streams = new KafkaStreams(builder, streamsConfiguration);
    streams.start();

    // Wait briefly for the streaming job to be fully up and running (otherwise it might miss
    // some or all of the input data we produce below).
    Thread.sleep(2000);

    //
    // Step 2: Produce some input data to the input topic.
    //
    Properties producerConfig = new Properties();
    producerConfig.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, cluster.bootstrapServers());
    producerConfig.put(ProducerConfig.ACKS_CONFIG, "all");
    producerConfig.put(ProducerConfig.RETRIES_CONFIG, 0);
    producerConfig.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    producerConfig.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);

    Producer<String, String> producer = new KafkaProducer<>(producerConfig);
    for (String value : inputValues) {
      Future<RecordMetadata> f = producer.send(new ProducerRecord<>(inputTopic, value));
      f.get();
    }
    producer.flush();
    producer.close();

    // Give the streaming job some time to do its work.
    Thread.sleep(2000);
    streams.close();

    //
    // Step 3: Verify the job's output data.
    //
    Properties consumerConfig = new Properties();
    consumerConfig.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, cluster.bootstrapServers());
    consumerConfig.put(ConsumerConfig.GROUP_ID_CONFIG, "pass-through-integration-test-standard-consumer");
    consumerConfig.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    consumerConfig.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    consumerConfig.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

    KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerConfig);
    consumer.subscribe(Collections.singletonList(outputTopic));
    List<String> actualValues = IntegrationTestUtils.readValues(consumer, inputValues.size());
    assertThat(actualValues).isEqualTo(inputValues);
  }

}