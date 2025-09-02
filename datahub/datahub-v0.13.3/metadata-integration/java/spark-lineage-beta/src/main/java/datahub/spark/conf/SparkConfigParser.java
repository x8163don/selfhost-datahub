package datahub.spark.conf;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkedin.common.FabricType;
import com.linkedin.common.urn.DataJobUrn;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import io.datahubproject.openlineage.config.DatahubOpenlineageConfig;
import io.datahubproject.openlineage.dataset.PathSpec;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.Collectors;
import org.apache.spark.SparkConf;
import org.apache.spark.SparkEnv;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SparkConfigParser {
  public static final String PARENT_JOB_KEY = "parent.datajob_urn";
  public static final String TRANSPORT_KEY = "transport";
  public static final String GMS_URL_KEY = "rest.server";
  public static final String GMS_AUTH_TOKEN = "rest.token";
  public static final String DISABLE_SSL_VERIFICATION_KEY = "rest.disable_ssl_verification";
  public static final String MAX_RETRIES = "rest.max_retries";
  public static final String RETRY_INTERVAL_IN_SEC = "rest.retry_interval_in_sec";

  public static final String COALESCE_KEY = "coalesce_jobs";
  public static final String PATCH_ENABLED = "patch.enabled";

  public static final String STAGE_METADATA_COALESCING = "stage_metadata_coalescing";
  public static final String STREAMING_JOB = "streaming_job";
  public static final String STREAMING_HEARTBEAT = "streaming_heartbeat";
  public static final String DATAHUB_FLOW_NAME = "flow_name";
  public static final String DATASET_ENV_KEY = "metadata.dataset.env";
  public static final String DATASET_MATERIALIZE_KEY = "metadata.dataset.materialize";
  public static final String DATASET_PLATFORM_INSTANCE_KEY = "metadata.dataset.platformInstance";
  public static final String DATASET_INCLUDE_SCHEMA_METADATA =
      "metadata.dataset.experimental_include_schema_metadata";
  public static final String SPARK_PLATFORM_INSTANCE_KEY = "platformInstance";
  public static final String REMOVE_PARTITION_PATTERN = "metadata.remove_partition_pattern";
  public static final String SPARK_APP_NAME = "spark.app.name";
  public static final String SPARK_MASTER = "spark.master";
  public static final String PLATFORM_KEY = "platform";
  public static final String PATH_SPEC_LIST_KEY = "path_spec_list";
  public static final String FILE_PARTITION_REGEXP_PATTERN = "file_partition_regexp";
  public static final String FABRIC_TYPE_KEY = "env";
  public static final String PLATFORM_INSTANCE_KEY = "platformInstance";
  public static final String DATABRICKS_CLUSTER_KEY = "databricks.cluster";
  public static final String PIPELINE_KEY = "metadata.pipeline";
  public static final String PIPELINE_PLATFORM_INSTANCE_KEY = PIPELINE_KEY + ".platformInstance";

  public static final String TAGS_KEY = "tags";

  public static final String DOMAINS_KEY = "domains";

  private static final Logger log = LoggerFactory.getLogger(SparkConfigParser.class);
  public static final String SPARK_DATABRICKS_CLUSTER_USAGE_TAGS_CLUSTER_ALL_TAGS =
      "spark.databricks.clusterUsageTags.clusterAllTags";

  private static final ObjectMapper mapper = new ObjectMapper();

  private SparkConfigParser() {}

  public static Properties moveKeysToRoot(Properties properties, String prefix) {
    Properties newProperties = new Properties();
    Enumeration<?> propertyNames = properties.propertyNames();

    while (propertyNames.hasMoreElements()) {
      String key = (String) propertyNames.nextElement();
      String value = properties.getProperty(key);

      if (key.startsWith(prefix)) {
        key = key.substring(prefix.length());
      }

      newProperties.setProperty(key, value);
      log.info("Setting property {} to {}", key, value);
    }

    return newProperties;
  }

  public static Config parsePropertiesToConfig(Properties properties) {
    properties
        .keySet()
        .removeIf(
            o ->
                (!o.toString().startsWith("spark.datahub.")
                    && !o.toString()
                        .startsWith(SPARK_DATABRICKS_CLUSTER_USAGE_TAGS_CLUSTER_ALL_TAGS)));
    properties = SparkConfigParser.moveKeysToRoot(properties, "spark.datahub.");
    return ConfigFactory.parseProperties(properties);
  }

  public static Config parseSparkConfig() {
    if (SparkEnv.get() == null) {
      return ConfigFactory.empty();
    }

    SparkConf conf = SparkEnv.get().conf();
    String propertiesString =
        Arrays.stream(conf.getAllWithPrefix("spark.datahub."))
            .map(tup -> tup._1 + "= \"" + tup._2 + "\"")
            .collect(Collectors.joining("\n"));

    return ConfigFactory.parseString(propertiesString);
  }

  public static Optional<Map<String, String>> getDatabricksClusterTags(
      String databricksClusterTags) {
    try {
      List<Map<String, String>> list =
          mapper.readValue(
              databricksClusterTags, new TypeReference<List<Map<String, String>>>() {});
      Map<String, String> hashMap = new HashMap<>();
      for (Map<String, String> map : list) {
        hashMap.put(map.get("key"), map.get("value"));
      }
      return Optional.of(hashMap);
    } catch (Exception e) {
      log.warn("Error parsing databricks cluster tags", e);
    }
    return Optional.empty();
  }

  public static DatahubOpenlineageConfig sparkConfigToDatahubOpenlineageConf(
      Config sparkConfig, SparkAppContext sparkAppContext) {
    DatahubOpenlineageConfig.DatahubOpenlineageConfigBuilder builder =
        DatahubOpenlineageConfig.builder();
    builder.filePartitionRegexpPattern(
        SparkConfigParser.getFilePartitionRegexpPattern(sparkConfig));
    builder.fabricType(SparkConfigParser.getCommonFabricType(sparkConfig));
    builder.includeSchemaMetadata(SparkConfigParser.isIncludeSchemaMetadata(sparkConfig));
    builder.materializeDataset(SparkConfigParser.isDatasetMaterialize(sparkConfig));
    builder.pathSpecs(SparkConfigParser.getPathSpecListMap(sparkConfig));
    String pipelineName = SparkConfigParser.getPipelineName(sparkConfig, sparkAppContext);
    if (pipelineName != null) {
      builder.pipelineName(pipelineName);
    }
    builder.platformInstance(SparkConfigParser.getPlatformInstance(sparkConfig));
    builder.commonDatasetPlatformInstance(SparkConfigParser.getCommonPlatformInstance(sparkConfig));
    builder.usePatch(SparkConfigParser.isPatchEnabled(sparkConfig));
    try {
      String parentJob = SparkConfigParser.getParentJobKey(sparkConfig);
      if (parentJob != null) {
        builder.parentJobUrn(DataJobUrn.createFromString(parentJob));
      }
    } catch (URISyntaxException e) {
      throw new RuntimeException(e);
    }
    return builder.build();
  }

  public static FabricType getCommonFabricType(Config datahubConfig) {
    String fabricTypeString =
        datahubConfig.hasPath(DATASET_ENV_KEY)
            ? datahubConfig.getString(DATASET_ENV_KEY).toUpperCase()
            : "PROD";
    FabricType fabricType = null;
    try {
      fabricType = FabricType.valueOf(fabricTypeString);
    } catch (IllegalArgumentException e) {
      log.warn("Invalid env ({}). Setting env to default PROD", fabricTypeString);
      fabricType = FabricType.PROD;
    }
    return fabricType;
  }

  public static String getCommonPlatformInstance(Config datahubConfig) {
    return datahubConfig.hasPath(DATASET_PLATFORM_INSTANCE_KEY)
        ? datahubConfig.getString(DATASET_PLATFORM_INSTANCE_KEY)
        : null;
  }

  public static Optional<Map<String, String>> getDatabricksTags(Config datahubConfig) {
    return datahubConfig.hasPath(SPARK_DATABRICKS_CLUSTER_USAGE_TAGS_CLUSTER_ALL_TAGS)
        ? getDatabricksClusterTags(
            datahubConfig.getString(SPARK_DATABRICKS_CLUSTER_USAGE_TAGS_CLUSTER_ALL_TAGS))
        : Optional.empty();
  }

  public static String getParentJobKey(Config datahubConfig) {
    return datahubConfig.hasPath(PARENT_JOB_KEY) ? datahubConfig.getString(PARENT_JOB_KEY) : null;
  }

  public static String[] getTags(Config datahubConfig) {
    return datahubConfig.hasPath(TAGS_KEY) ? datahubConfig.getString(TAGS_KEY).split(",") : null;
  }

  public static String[] getDomains(Config datahubConfig) {
    return datahubConfig.hasPath(DOMAINS_KEY)
        ? datahubConfig.getString(DOMAINS_KEY).split(",")
        : null;
  }

  public static String getSparkMaster(Config datahubConfig) {
    return datahubConfig.hasPath(SPARK_MASTER)
        ? datahubConfig
            .getString(SPARK_MASTER)
            .replaceAll(":", "_")
            .replaceAll("/", "_")
            .replaceAll(",", "_")
            .replaceAll("[_]+", "_")
        : "default";
  }

  public static String getRemovePartitionPattern(Config datahubConfig) {
    return datahubConfig.hasPath(REMOVE_PARTITION_PATTERN)
        ? datahubConfig.getString(REMOVE_PARTITION_PATTERN)
        : null;
  }

  public static String getSparkAppName(Config datahubConfig) {
    return datahubConfig.hasPath(SPARK_APP_NAME)
        ? datahubConfig.getString(SPARK_APP_NAME)
        : "default";
  }

  public static Map<String, List<PathSpec>> getPathSpecListMap(Config datahubConfig) {
    HashMap<String, List<PathSpec>> pathSpecMap = new HashMap<>();

    if (datahubConfig.hasPath(PLATFORM_KEY)) {
      for (String key : datahubConfig.getConfig(PLATFORM_KEY).root().keySet()) {
        String aliasKey = PLATFORM_KEY + "." + key;
        List<PathSpec> platformSpecs = new LinkedList<>();
        for (String pathSpecKey : datahubConfig.getConfig(aliasKey).root().keySet()) {
          PathSpec.PathSpecBuilder pathSpecBuilder = PathSpec.builder();
          pathSpecBuilder.alias(pathSpecKey);
          pathSpecBuilder.platform(key);
          if (datahubConfig.hasPath(aliasKey + ".env")) {
            pathSpecBuilder.env(datahubConfig.getString(aliasKey + ".env"));
          }
          if (datahubConfig.hasPath(aliasKey + ".platformInstance")) {
            pathSpecBuilder.platformInstance(
                Optional.ofNullable(datahubConfig.getString(aliasKey + ".platformInstance")));
          }
          pathSpecBuilder.pathSpecList(
              Arrays.asList(datahubConfig.getString(aliasKey + "." + pathSpecKey).split(",")));

          platformSpecs.add(pathSpecBuilder.build());
        }
        pathSpecMap.put(key, platformSpecs);
      }
    }
    return pathSpecMap;
  }

  public static String getPlatformInstance(Config pathSpecConfig) {
    return pathSpecConfig.hasPath(PLATFORM_INSTANCE_KEY)
        ? pathSpecConfig.getString(PLATFORM_INSTANCE_KEY)
        : null;
  }

  public static String getFilePartitionRegexpPattern(Config config) {
    return config.hasPath(FILE_PARTITION_REGEXP_PATTERN)
        ? config.getString(FILE_PARTITION_REGEXP_PATTERN)
        : null;
  }

  public static int getStreamingHeartbeatSec(Config datahubConfig) {
    return datahubConfig.hasPath(STREAMING_HEARTBEAT)
        ? datahubConfig.getInt(STREAMING_HEARTBEAT)
        : 5 * 60;
  }

  public static boolean isDatasetMaterialize(Config datahubConfig) {
    return datahubConfig.hasPath(DATASET_MATERIALIZE_KEY)
        && datahubConfig.getBoolean(DATASET_MATERIALIZE_KEY);
  }

  public static boolean isIncludeSchemaMetadata(Config datahubConfig) {
    return datahubConfig.hasPath(DATASET_INCLUDE_SCHEMA_METADATA)
        && datahubConfig.getBoolean(DATASET_INCLUDE_SCHEMA_METADATA);
  }

  public static String getPipelineName(Config datahubConfig, SparkAppContext appContext) {
    String name = appContext != null && appContext.appName != null ? appContext.appName : null;
    if (datahubConfig.hasPath(DATAHUB_FLOW_NAME)) {
      name = datahubConfig.getString(DATAHUB_FLOW_NAME);
    }
    if (datahubConfig.hasPath(DATABRICKS_CLUSTER_KEY)) {
      return (datahubConfig.getString(DATABRICKS_CLUSTER_KEY) + "_" + name).replaceAll("[,]", "");
    }

    // TODO: appending of platform instance needs to be done at central location
    // like adding constructor to dataflowurl
    if (datahubConfig.hasPath(PIPELINE_PLATFORM_INSTANCE_KEY)) {
      name = datahubConfig.getString(PIPELINE_PLATFORM_INSTANCE_KEY) + "." + name;
    }
    return name;
  }

  public static boolean isCoalesceEnabled(Config datahubConfig) {
    if (!datahubConfig.hasPath(COALESCE_KEY)) {
      return true;
    }
    return datahubConfig.hasPath(COALESCE_KEY) && datahubConfig.getBoolean(COALESCE_KEY);
  }

  public static boolean isPatchEnabled(Config datahubConfig) {
    if (!datahubConfig.hasPath(PATCH_ENABLED)) {
      return false;
    }
    return datahubConfig.hasPath(PATCH_ENABLED) && datahubConfig.getBoolean(PATCH_ENABLED);
  }

  public static boolean isEmitCoalescePeriodically(Config datahubConfig) {
    if (!datahubConfig.hasPath(STAGE_METADATA_COALESCING)) {
      // if databricks tags are present and stage_metadata_coalescing is not present, then default
      // to true for coalescing periodically
      // because on DataBricks platform we don't get application stop event
      return getDatabricksTags(datahubConfig).isPresent() && isCoalesceEnabled(datahubConfig);
    }

    return datahubConfig.hasPath(STAGE_METADATA_COALESCING)
        && datahubConfig.getBoolean(STAGE_METADATA_COALESCING);
  }
}
