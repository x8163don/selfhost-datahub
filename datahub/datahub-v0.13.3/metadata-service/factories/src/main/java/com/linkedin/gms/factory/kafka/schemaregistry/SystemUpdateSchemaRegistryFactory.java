package com.linkedin.gms.factory.kafka.schemaregistry;

import static com.linkedin.metadata.boot.kafka.DataHubUpgradeKafkaListener.TOPIC_NAME;

import com.linkedin.gms.factory.config.ConfigurationProvider;
import com.linkedin.metadata.boot.kafka.MockSystemUpdateDeserializer;
import com.linkedin.metadata.boot.kafka.MockSystemUpdateSerializer;
import com.linkedin.metadata.config.kafka.KafkaConfiguration;
import com.linkedin.metadata.registry.SchemaRegistryService;
import com.linkedin.mxe.Topics;
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
public class SystemUpdateSchemaRegistryFactory {

  public static final String SYSTEM_UPDATE_TOPIC_KEY_PREFIX = "data-hub.system-update.topic-key.";
  public static final String SYSTEM_UPDATE_TOPIC_KEY_ID_SUFFIX = ".id";

  public static final String DUHE_SCHEMA_REGISTRY_TOPIC_KEY =
      SYSTEM_UPDATE_TOPIC_KEY_PREFIX + "duhe";
  public static final String MCL_VERSIONED_SCHEMA_REGISTRY_TOPIC_KEY =
      SYSTEM_UPDATE_TOPIC_KEY_PREFIX + "mcl-versioned";

  @Value(TOPIC_NAME)
  private String duheTopicName;

  @Value("${METADATA_CHANGE_LOG_VERSIONED_TOPIC_NAME:" + Topics.METADATA_CHANGE_LOG_VERSIONED + "}")
  private String mclTopicName;

  /** Configure Kafka Producer/Consumer processes with a custom schema registry. */
  @Bean("duheSchemaRegistryConfig")
  protected SchemaRegistryConfig duheSchemaRegistryConfig(
      final ConfigurationProvider provider, final SchemaRegistryService schemaRegistryService) {
    Map<String, Object> props = new HashMap<>();
    KafkaConfiguration kafkaConfiguration = provider.getKafka();

    props.put(
        AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG,
        kafkaConfiguration.getSchemaRegistry().getUrl());

    // topic names
    props.putAll(
        Map.of(
            DUHE_SCHEMA_REGISTRY_TOPIC_KEY, duheTopicName,
            MCL_VERSIONED_SCHEMA_REGISTRY_TOPIC_KEY, mclTopicName));

    // topic ordinals
    props.putAll(
        Map.of(
            DUHE_SCHEMA_REGISTRY_TOPIC_KEY + SYSTEM_UPDATE_TOPIC_KEY_ID_SUFFIX,
                schemaRegistryService.getSchemaIdForTopic(duheTopicName).get().toString(),
            MCL_VERSIONED_SCHEMA_REGISTRY_TOPIC_KEY + SYSTEM_UPDATE_TOPIC_KEY_ID_SUFFIX,
                schemaRegistryService.getSchemaIdForTopic(mclTopicName).get().toString()));

    log.info("DataHub System Update Registry");
    return new SchemaRegistryConfig(
        MockSystemUpdateSerializer.class, MockSystemUpdateDeserializer.class, props);
  }
}
