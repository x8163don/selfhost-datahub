package com.linkedin.gms.factory.kafka;

import com.linkedin.gms.factory.config.ConfigurationProvider;
import com.linkedin.gms.factory.kafka.schemaregistry.SchemaRegistryConfig;
import com.linkedin.metadata.config.kafka.KafkaConfiguration;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.apache.avro.generic.GenericRecord;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.KafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.CommonContainerStoppingErrorHandler;
import org.springframework.kafka.listener.CommonDelegatingErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;

@Slf4j
@Configuration
public class KafkaEventConsumerFactory {

  private int kafkaEventConsumerConcurrency;

  @Bean(name = "kafkaConsumerFactory")
  protected DefaultKafkaConsumerFactory<String, GenericRecord> createConsumerFactory(
      @Qualifier("configurationProvider") ConfigurationProvider provider,
      KafkaProperties baseKafkaProperties,
      @Qualifier("schemaRegistryConfig") SchemaRegistryConfig schemaRegistryConfig) {
    kafkaEventConsumerConcurrency = provider.getKafka().getListener().getConcurrency();

    KafkaConfiguration kafkaConfiguration = provider.getKafka();
    Map<String, Object> customizedProperties =
        buildCustomizedProperties(baseKafkaProperties, kafkaConfiguration, schemaRegistryConfig);

    return new DefaultKafkaConsumerFactory<>(customizedProperties);
  }

  @Bean(name = "duheKafkaConsumerFactory")
  protected DefaultKafkaConsumerFactory<String, GenericRecord> duheKafkaConsumerFactory(
      @Qualifier("configurationProvider") ConfigurationProvider provider,
      KafkaProperties baseKafkaProperties,
      @Qualifier("duheSchemaRegistryConfig") SchemaRegistryConfig schemaRegistryConfig) {

    KafkaConfiguration kafkaConfiguration = provider.getKafka();
    Map<String, Object> customizedProperties =
        buildCustomizedProperties(baseKafkaProperties, kafkaConfiguration, schemaRegistryConfig);

    return new DefaultKafkaConsumerFactory<>(customizedProperties);
  }

  private static Map<String, Object> buildCustomizedProperties(
      KafkaProperties baseKafkaProperties,
      KafkaConfiguration kafkaConfiguration,
      SchemaRegistryConfig schemaRegistryConfig) {
    KafkaProperties.Consumer consumerProps = baseKafkaProperties.getConsumer();

    // Records will be flushed every 10 seconds.
    consumerProps.setEnableAutoCommit(true);
    consumerProps.setAutoCommitInterval(Duration.ofSeconds(10));

    // KAFKA_BOOTSTRAP_SERVER has precedence over SPRING_KAFKA_BOOTSTRAP_SERVERS
    if (kafkaConfiguration.getBootstrapServers() != null
        && kafkaConfiguration.getBootstrapServers().length() > 0) {
      consumerProps.setBootstrapServers(
          Arrays.asList(kafkaConfiguration.getBootstrapServers().split(",")));
    } // else we rely on KafkaProperties which defaults to localhost:9092

    Map<String, Object> customizedProperties = baseKafkaProperties.buildConsumerProperties();
    customizedProperties.put(
        ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
    customizedProperties.put(
        ErrorHandlingDeserializer.KEY_DESERIALIZER_CLASS, StringDeserializer.class);
    customizedProperties.put(
        ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
    customizedProperties.put(
        ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, schemaRegistryConfig.getDeserializer());

    // Override KafkaProperties with SchemaRegistryConfig only for non-empty values
    schemaRegistryConfig.getProperties().entrySet().stream()
        .filter(entry -> entry.getValue() != null && !entry.getValue().toString().isEmpty())
        .forEach(entry -> customizedProperties.put(entry.getKey(), entry.getValue()));

    customizedProperties.put(
        ConsumerConfig.MAX_PARTITION_FETCH_BYTES_CONFIG,
        kafkaConfiguration.getConsumer().getMaxPartitionFetchBytes());

    return customizedProperties;
  }

  @Bean(name = "kafkaEventConsumer")
  protected KafkaListenerContainerFactory<?> createInstance(
      @Qualifier("kafkaConsumerFactory")
          DefaultKafkaConsumerFactory<String, GenericRecord> kafkaConsumerFactory,
      @Qualifier("configurationProvider") ConfigurationProvider configurationProvider) {

    ConcurrentKafkaListenerContainerFactory<String, GenericRecord> factory =
        new ConcurrentKafkaListenerContainerFactory<>();
    factory.setConsumerFactory(kafkaConsumerFactory);
    factory.setContainerCustomizer(new ThreadPoolContainerCustomizer());
    factory.setConcurrency(kafkaEventConsumerConcurrency);

    /* Sets up a delegating error handler for Deserialization errors, if disabled will
     use DefaultErrorHandler (does back-off retry and then logs) rather than stopping the container. Stopping the container
     prevents lost messages until the error can be examined, disabling this will allow progress, but may lose data
    */
    if (configurationProvider.getKafka().getConsumer().isStopOnDeserializationError()) {
      CommonDelegatingErrorHandler delegatingErrorHandler =
          new CommonDelegatingErrorHandler(new DefaultErrorHandler());
      delegatingErrorHandler.addDelegate(
          DeserializationException.class, new CommonContainerStoppingErrorHandler());
      factory.setCommonErrorHandler(delegatingErrorHandler);
    }
    log.info(
        String.format(
            "Event-based KafkaListenerContainerFactory built successfully. Consumer concurrency = %s",
            kafkaEventConsumerConcurrency));

    return factory;
  }

  @Bean(name = "duheKafkaEventConsumer")
  protected KafkaListenerContainerFactory<?> duheKafkaEventConsumer(
      @Qualifier("duheKafkaConsumerFactory")
          DefaultKafkaConsumerFactory<String, GenericRecord> kafkaConsumerFactory) {

    ConcurrentKafkaListenerContainerFactory<String, GenericRecord> factory =
        new ConcurrentKafkaListenerContainerFactory<>();
    factory.setConsumerFactory(kafkaConsumerFactory);
    factory.setContainerCustomizer(new ThreadPoolContainerCustomizer());
    factory.setConcurrency(1);

    log.info(
        "Event-based DUHE KafkaListenerContainerFactory built successfully. Consumer concurrency = 1");
    return factory;
  }
}
