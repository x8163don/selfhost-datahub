package io.datahubproject.openlineage.converter;

import com.linkedin.common.AuditStamp;
import com.linkedin.common.DataPlatformInstance;
import com.linkedin.common.Edge;
import com.linkedin.common.EdgeArray;
import com.linkedin.common.GlobalTags;
import com.linkedin.common.Owner;
import com.linkedin.common.OwnerArray;
import com.linkedin.common.Ownership;
import com.linkedin.common.OwnershipSource;
import com.linkedin.common.OwnershipSourceType;
import com.linkedin.common.OwnershipType;
import com.linkedin.common.TagAssociation;
import com.linkedin.common.TagAssociationArray;
import com.linkedin.common.TimeStamp;
import com.linkedin.common.UrnArray;
import com.linkedin.common.urn.DataFlowUrn;
import com.linkedin.common.urn.DataJobUrn;
import com.linkedin.common.urn.DataPlatformUrn;
import com.linkedin.common.urn.DatasetUrn;
import com.linkedin.common.urn.TagUrn;
import com.linkedin.common.urn.TupleKey;
import com.linkedin.common.urn.Urn;
import com.linkedin.common.urn.UrnUtils;
import com.linkedin.data.template.StringMap;
import com.linkedin.datajob.DataFlowInfo;
import com.linkedin.datajob.DataJobInfo;
import com.linkedin.datajob.DataJobInputOutput;
import com.linkedin.dataprocess.DataProcessInstanceProperties;
import com.linkedin.dataprocess.DataProcessInstanceRelationships;
import com.linkedin.dataprocess.DataProcessInstanceRunEvent;
import com.linkedin.dataprocess.DataProcessInstanceRunResult;
import com.linkedin.dataprocess.DataProcessRunStatus;
import com.linkedin.dataprocess.RunResultType;
import com.linkedin.dataset.DatasetLineageType;
import com.linkedin.dataset.FineGrainedLineage;
import com.linkedin.dataset.FineGrainedLineageArray;
import com.linkedin.dataset.FineGrainedLineageDownstreamType;
import com.linkedin.dataset.FineGrainedLineageUpstreamType;
import com.linkedin.dataset.Upstream;
import com.linkedin.dataset.UpstreamArray;
import com.linkedin.dataset.UpstreamLineage;
import com.linkedin.domain.Domains;
import com.linkedin.schema.MapType;
import com.linkedin.schema.MySqlDDL;
import com.linkedin.schema.NullType;
import com.linkedin.schema.NumberType;
import com.linkedin.schema.SchemaField;
import com.linkedin.schema.SchemaFieldArray;
import com.linkedin.schema.SchemaFieldDataType;
import com.linkedin.schema.SchemaMetadata;
import com.linkedin.schema.StringType;
import com.linkedin.schema.TimeType;
import io.datahubproject.openlineage.config.DatahubOpenlineageConfig;
import io.datahubproject.openlineage.dataset.DatahubDataset;
import io.datahubproject.openlineage.dataset.DatahubJob;
import io.datahubproject.openlineage.dataset.HdfsPathDataset;
import io.datahubproject.openlineage.dataset.HdfsPlatform;
import io.openlineage.client.OpenLineage;
import io.openlineage.client.OpenLineageClientUtils;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONArray;
import org.json.JSONException;

@Slf4j
public class OpenLineageToDataHub {

  public static final String FILE_NAMESPACE = "file";
  public static final String SCHEME_SEPARATOR = "://";
  public static final String URN_LI_CORPUSER = "urn:li:corpuser:";
  public static final String URN_LI_CORPUSER_DATAHUB = URN_LI_CORPUSER + "datahub";
  public static final String URN_LI_DATA_PROCESS_INSTANCE = "urn:li:dataProcessInstance:";

  public static final Map<String, String> PLATFORM_MAP =
      Stream.of(
              new String[][] {
                {"awsathena", "athena"}, {"sqlserver", "mssql"},
              })
          .collect(Collectors.toMap(data -> data[0], data -> data[1]));

  private OpenLineageToDataHub() {}

  public static Optional<DatasetUrn> convertOpenlineageDatasetToDatasetUrn(
      OpenLineage.Dataset dataset, DatahubOpenlineageConfig mappingConfig) {
    String namespace = dataset.getNamespace();
    String datasetName = dataset.getName();

    String platform;
    if (namespace.contains(SCHEME_SEPARATOR)) {
      try {
        URI datasetUri;
        if (!namespace.endsWith("/") && !datasetName.startsWith("/")) {
          datasetUri = new URI(namespace + "/" + datasetName);
        } else {
          datasetUri = new URI(namespace + datasetName);
        }
        if (PLATFORM_MAP.containsKey(datasetUri.getScheme())) {
          platform = PLATFORM_MAP.get(datasetUri.getScheme());
        } else {
          platform = datasetUri.getScheme();
        }
        datasetName = datasetUri.getPath();
        if (HdfsPlatform.isFsPlatformPrefix(platform)) {
          try {
            HdfsPathDataset hdfsPathDataset = HdfsPathDataset.create(datasetUri, mappingConfig);
            return Optional.of(hdfsPathDataset.urn());
          } catch (InstantiationException e) {
            log.warn(
                "Unable to create urn from namespace: {} and dataset {}.", namespace, datasetName);
            return Optional.empty();
          }
        } else {
          datasetName = dataset.getName();
        }
      } catch (URISyntaxException e) {
        log.warn("Unable to create URI from namespace: {} and dataset {}.", namespace, datasetName);
        return Optional.empty();
      }
    } else {
      platform = namespace;
      datasetName = dataset.getName();
    }

    if (mappingConfig.getCommonDatasetPlatformInstance() != null) {
      datasetName = mappingConfig.getCommonDatasetPlatformInstance() + "." + datasetName;
    }

    return Optional.of(
        new DatasetUrn(new DataPlatformUrn(platform), datasetName, mappingConfig.getFabricType()));
  }

  public static GlobalTags generateTags(List<String> tags) {
    tags.sort(String::compareToIgnoreCase);
    GlobalTags globalTags = new GlobalTags();
    TagAssociationArray tagAssociationArray = new TagAssociationArray();
    for (String tag : tags) {
      TagAssociation tagAssociation = new TagAssociation();
      tagAssociation.setTag(new TagUrn(tag));
      tagAssociationArray.add(tagAssociation);
    }
    globalTags.setTags(tagAssociationArray);
    return globalTags;
  }

  public static Domains generateDomains(List<String> domains) {
    domains.sort(String::compareToIgnoreCase);
    Domains datahubDomains = new Domains();
    UrnArray domainArray = new UrnArray();
    for (String domain : domains) {
      try {
        domainArray.add(Urn.createFromString(domain));
      } catch (URISyntaxException e) {
        log.warn("Unable to create domain urn for domain urn: {}", domain);
      }
    }
    datahubDomains.setDomains(domainArray);
    return datahubDomains;
  }

  public static Urn dataPlatformInstanceUrn(String platform, String instance)
      throws URISyntaxException {
    return new Urn(
        "dataPlatformInstance",
        new TupleKey(Arrays.asList(new DataPlatformUrn(platform).toString(), instance)));
  }

  public static DatahubJob convertRunEventToJob(
      OpenLineage.RunEvent event, DatahubOpenlineageConfig datahubConf)
      throws IOException, URISyntaxException {
    DatahubJob.DatahubJobBuilder jobBuilder = DatahubJob.builder();

    if (event.getEventTime() != null) {
      jobBuilder.eventTime(event.getEventTime().toInstant().toEpochMilli());
    }

    log.info("Emitting lineage: {}", OpenLineageClientUtils.toJson(event));
    DataFlowInfo dfi = convertRunEventToDataFlowInfo(event, datahubConf.getPipelineName());

    DataFlowUrn dataFlowUrn =
        getFlowUrn(
            event.getJob().getNamespace(),
            event.getJob().getName(),
            null,
            event.getProducer(),
            datahubConf);
    jobBuilder.flowUrn(dataFlowUrn);

    if (datahubConf.getPlatformInstance() != null) {
      DataPlatformInstance dpi =
          new DataPlatformInstance()
              .setPlatform(new DataPlatformUrn(dataFlowUrn.getOrchestratorEntity()))
              .setInstance(
                  dataPlatformInstanceUrn(
                      dataFlowUrn.getOrchestratorEntity(), datahubConf.getPlatformInstance()));
      jobBuilder.flowPlatformInstance(dpi);
    }

    StringMap customProperties = generateCustomProperties(event, true);
    dfi.setCustomProperties(customProperties);

    String description = getDescription(event);
    if (description != null) {
      dfi.setDescription(description);
    }
    jobBuilder.dataFlowInfo(dfi);

    Ownership ownership = generateOwnership(event);
    jobBuilder.flowOwnership(ownership);

    GlobalTags tags = generateTags(event);
    jobBuilder.flowGlobalTags(tags);

    DatahubJob datahubJob = jobBuilder.build();
    convertJobToDataJob(datahubJob, event, datahubConf);
    return datahubJob;
  }

  static void forEachValue(Map<String, Object> source, StringMap customProperties) {
    for (final Map.Entry<String, Object> entry : source.entrySet()) {
      if (entry.getValue() instanceof Map) {
        forEachValue((Map<String, Object>) entry.getValue(), customProperties);
      } else {
        customProperties.put(entry.getKey(), entry.getValue().toString());
      }
    }
  }

  private static Ownership generateOwnership(OpenLineage.RunEvent event) {
    Ownership ownership = new Ownership();
    OwnerArray owners = new OwnerArray();
    if ((event.getJob().getFacets() != null)
        && (event.getJob().getFacets().getOwnership() != null)) {
      for (OpenLineage.OwnershipJobFacetOwners ownerFacet :
          event.getJob().getFacets().getOwnership().getOwners()) {
        Owner owner = new Owner();
        try {
          owner.setOwner(Urn.createFromString(URN_LI_CORPUSER + ":" + ownerFacet.getName()));
          owner.setType(OwnershipType.DEVELOPER);
          OwnershipSource source = new OwnershipSource();
          source.setType(OwnershipSourceType.SERVICE);
          owner.setSource(source);
          owners.add(owner);
        } catch (URISyntaxException e) {
          log.warn("Unable to create owner urn for owner: {}", ownerFacet.getName());
        }
      }
    }
    ownership.setOwners(owners);
    try {
      AuditStamp auditStamp = new AuditStamp();
      auditStamp.setActor(Urn.createFromString(URN_LI_CORPUSER_DATAHUB));
      auditStamp.setTime(System.currentTimeMillis());
      ownership.setLastModified(auditStamp);
    } catch (URISyntaxException e) {
      log.warn("Unable to create actor urn for actor: {}", URN_LI_CORPUSER_DATAHUB);
    }
    return ownership;
  }

  private static String getDescription(OpenLineage.RunEvent event) {
    if (event.getJob().getFacets() != null
        && event.getJob().getFacets().getDocumentation() != null) {
      return event.getJob().getFacets().getDocumentation().getDescription();
    }
    return null;
  }

  private static UpstreamLineage getFineGrainedLineage(
      OpenLineage.Dataset dataset, DatahubOpenlineageConfig mappingConfig) {
    FineGrainedLineageArray fgla = new FineGrainedLineageArray();
    UpstreamArray upstreams = new UpstreamArray();

    if ((dataset.getFacets() == null) || (dataset.getFacets().getColumnLineage() == null)) {
      return null;
    }

    OpenLineage.ColumnLineageDatasetFacet columLineage = dataset.getFacets().getColumnLineage();
    Set<Map.Entry<String, OpenLineage.ColumnLineageDatasetFacetFieldsAdditional>> fields =
        columLineage.getFields().getAdditionalProperties().entrySet();
    for (Map.Entry<String, OpenLineage.ColumnLineageDatasetFacetFieldsAdditional> field : fields) {
      FineGrainedLineage fgl = new FineGrainedLineage();

      UrnArray upstreamFields = new UrnArray();
      UrnArray downstreamsFields = new UrnArray();
      Optional<DatasetUrn> datasetUrn =
          convertOpenlineageDatasetToDatasetUrn(dataset, mappingConfig);
      datasetUrn.ifPresent(
          urn ->
              downstreamsFields.add(
                  UrnUtils.getUrn("urn:li:schemaField:" + "(" + urn + "," + field.getKey() + ")")));
      OpenLineage.StaticDatasetBuilder staticDatasetBuilder =
          new OpenLineage.StaticDatasetBuilder();
      field
          .getValue()
          .getInputFields()
          .forEach(
              inputField -> {
                OpenLineage.Dataset staticDataset =
                    staticDatasetBuilder
                        .name(inputField.getName())
                        .namespace(inputField.getNamespace())
                        .build();
                Optional<DatasetUrn> urn =
                    convertOpenlineageDatasetToDatasetUrn(staticDataset, mappingConfig);
                if (urn.isPresent()) {
                  Urn datasetFieldUrn =
                      UrnUtils.getUrn(
                          "urn:li:schemaField:"
                              + "("
                              + urn.get()
                              + ","
                              + inputField.getField()
                              + ")");
                  upstreamFields.add(datasetFieldUrn);
                  upstreams.add(
                      new Upstream().setDataset(urn.get()).setType(DatasetLineageType.TRANSFORMED));
                }
              });

      // fgl.set(upstreamFields);
      upstreamFields.sort(Comparator.comparing(Urn::toString));
      fgl.setUpstreams(upstreamFields);
      fgl.setConfidenceScore(0.5f);
      fgl.setUpstreamType(FineGrainedLineageUpstreamType.FIELD_SET);

      downstreamsFields.sort(Comparator.comparing(Urn::toString));
      fgl.setDownstreams(downstreamsFields);
      fgl.setDownstreamType(FineGrainedLineageDownstreamType.FIELD_SET);
      fgla.add(fgl);
    }

    UpstreamLineage upstreamLineage = new UpstreamLineage();
    upstreamLineage.setFineGrainedLineages(fgla);
    upstreamLineage.setUpstreams(upstreams);
    return upstreamLineage;
  }

  private static GlobalTags generateTags(OpenLineage.RunEvent event) {
    if (event.getRun().getFacets() == null
        || event.getRun().getFacets().getAdditionalProperties() == null
        || event.getRun().getFacets().getAdditionalProperties().get("airflow") == null
        || event
                .getRun()
                .getFacets()
                .getAdditionalProperties()
                .get("airflow")
                .getAdditionalProperties()
                .get("dag")
            == null) {
      return null;
    }
    Map<String, Object> airflowProperties =
        event
            .getRun()
            .getFacets()
            .getAdditionalProperties()
            .get("airflow")
            .getAdditionalProperties();
    Map<String, Object> dagProperties = (Map<String, Object>) airflowProperties.get("dag");
    if (dagProperties.get("tags") != null) {
      try {
        JSONArray arr = new JSONArray(((String) dagProperties.get("tags")).replace("'", "\""));
        LinkedList<String> tags = new LinkedList<>();
        for (int i = 0; i < arr.length(); i++) {
          tags.add(arr.getString(i));
        }
        return generateTags(tags);
      } catch (JSONException e) {
        log.warn("Unable to parse tags from airflow properties: {}", e.getMessage());
        return null;
      }
    }

    return null;
  }

  private static StringMap generateCustomProperties(
      OpenLineage.RunEvent event, boolean flowProperties) {
    StringMap customProperties = new StringMap();
    if ((event.getRun().getFacets() != null)
        && (event.getRun().getFacets().getProcessing_engine() != null)) {
      if (event.getRun().getFacets().getProcessing_engine().getName() != null) {
        customProperties.put(
            "processingEngine", event.getRun().getFacets().getProcessing_engine().getName());
      }

      customProperties.put(
          "processingEngine", event.getRun().getFacets().getProcessing_engine().getName());
      if (event.getRun().getFacets().getProcessing_engine().getVersion() != null) {
        customProperties.put(
            "processingEngineVersion",
            event.getRun().getFacets().getProcessing_engine().getVersion());
      }
      if (event.getRun().getFacets().getProcessing_engine().getOpenlineageAdapterVersion()
          != null) {
        customProperties.put(
            "openlineageAdapterVersion",
            event.getRun().getFacets().getProcessing_engine().getOpenlineageAdapterVersion());
      }
    }

    if ((event.getRun().getFacets() == null)
        || (event.getRun().getFacets().getAdditionalProperties() == null)) {
      return customProperties;
    }

    for (Map.Entry<String, OpenLineage.RunFacet> entry :
        event.getRun().getFacets().getAdditionalProperties().entrySet()) {
      switch (entry.getKey()) {
        case "spark_version":
          {
            if (entry.getValue().getAdditionalProperties().get("spark-version") != null) {
              customProperties.put(
                  "spark-version",
                  (String) entry.getValue().getAdditionalProperties().get("spark-version"));
            }
            if (entry.getValue().getAdditionalProperties().get("openlineage-spark-version")
                != null) {
              customProperties.put(
                  "openlineage-spark-version",
                  (String)
                      entry.getValue().getAdditionalProperties().get("openlineage-spark-version"));
            }
          }
          break;
        case "spark_properties":
          {
            if (entry.getValue() != null) {
              Map<String, Object> sparkProperties =
                  (Map<String, Object>)
                      entry.getValue().getAdditionalProperties().get("properties");
              log.info("Spark properties: {}, Properties: {}", entry.getValue(), sparkProperties);
              if (sparkProperties != null) {
                forEachValue(sparkProperties, customProperties);
              }
            }
          }
          break;
        case "airflow":
          {
            Map<String, Object> airflowProperties;
            if (flowProperties) {
              airflowProperties =
                  (Map<String, Object>) entry.getValue().getAdditionalProperties().get("dag");
            } else {
              airflowProperties =
                  (Map<String, Object>) entry.getValue().getAdditionalProperties().get("task");
            }
            forEachValue(airflowProperties, customProperties);
          }
          break;
        case "unknownSourceAttribute":
          {
            if (!flowProperties) {
              List<Map<String, Object>> unknownItems =
                  (List<Map<String, Object>>)
                      entry.getValue().getAdditionalProperties().get("unknownItems");
              for (Map<String, Object> item : unknownItems) {
                forEachValue(item, customProperties);
              }
            }
          }
          break;
        default:
          break;
      }
    }
    return customProperties;
  }

  private static void convertJobToDataJob(
      DatahubJob datahubJob, OpenLineage.RunEvent event, DatahubOpenlineageConfig datahubConf)
      throws URISyntaxException, IOException {

    OpenLineage.Job job = event.getJob();
    DataJobInfo dji = new DataJobInfo();

    if (job.getName().contains(".")) {

      String jobName = job.getName().substring(job.getName().indexOf(".") + 1);
      dji.setName(jobName);
    } else {
      dji.setName(job.getName());
    }

    String jobProcessingEngine = null;
    if ((event.getRun().getFacets() != null)
        && (event.getRun().getFacets().getProcessing_engine() != null)) {
      jobProcessingEngine = event.getRun().getFacets().getProcessing_engine().getName();
    }

    DataFlowUrn flowUrn =
        getFlowUrn(
            event.getJob().getNamespace(),
            event.getJob().getName(),
            jobProcessingEngine,
            event.getProducer(),
            datahubConf);

    dji.setFlowUrn(flowUrn);
    dji.setType(DataJobInfo.Type.create(flowUrn.getOrchestratorEntity()));

    DataJobUrn dataJobUrn = new DataJobUrn(flowUrn, job.getName());
    datahubJob.setJobUrn(dataJobUrn);
    StringMap customProperties = generateCustomProperties(event, false);
    dji.setCustomProperties(customProperties);

    TimeStamp timestamp = new TimeStamp();

    if (event.getEventTime() != null) {
      dji.setCreated(timestamp.setTime(event.getEventTime().toInstant().toEpochMilli()));
    }

    String description = getDescription(event);
    if (description != null) {
      dji.setDescription(description);
    }
    datahubJob.setJobInfo(dji);
    DataJobInputOutput inputOutput = new DataJobInputOutput();

    processJobInputs(datahubJob, event, datahubConf);

    processJobOutputs(datahubJob, event, datahubConf);

    DataProcessInstanceRunEvent dpire = processDataProcessInstanceResult(event);
    datahubJob.setDataProcessInstanceRunEvent(dpire);

    DataProcessInstanceProperties dpiProperties = getJobDataProcessInstanceProperties(event);
    datahubJob.setDataProcessInstanceProperties(dpiProperties);

    processParentJob(event, job, inputOutput, datahubConf);

    DataProcessInstanceRelationships dataProcessInstanceRelationships =
        new DataProcessInstanceRelationships();
    dataProcessInstanceRelationships.setParentTemplate(dataJobUrn);
    dataProcessInstanceRelationships.setUpstreamInstances(new UrnArray());
    datahubJob.setDataProcessInstanceRelationships(dataProcessInstanceRelationships);

    try {
      Urn dpiUrn =
          Urn.createFromString(URN_LI_DATA_PROCESS_INSTANCE + event.getRun().getRunId().toString());
      datahubJob.setDataProcessInstanceUrn(dpiUrn);
    } catch (URISyntaxException e) {
      throw new RuntimeException("Unable to create dataprocess instance urn:" + e);
    }
  }

  private static DataProcessInstanceProperties getJobDataProcessInstanceProperties(
      OpenLineage.RunEvent event) throws URISyntaxException {
    DataProcessInstanceProperties dpiProperties = new DataProcessInstanceProperties();
    dpiProperties.setName(event.getRun().getRunId().toString());
    AuditStamp auditStamp = new AuditStamp();
    if (event.getEventTime() != null) {
      auditStamp.setTime(event.getEventTime().toInstant().toEpochMilli());
    }
    auditStamp.setActor(Urn.createFromString(URN_LI_CORPUSER_DATAHUB));
    dpiProperties.setCreated(auditStamp);
    return dpiProperties;
  }

  public static Edge createEdge(Urn urn, ZonedDateTime eventTime) {
    Edge edge = new Edge();
    edge.setLastModified(createAuditStamp(eventTime));
    edge.setDestinationUrn(urn);
    return edge;
  }

  public static AuditStamp createAuditStamp(ZonedDateTime eventTime) {
    AuditStamp auditStamp = new AuditStamp();
    if (eventTime != null) {
      auditStamp.setTime(eventTime.toInstant().toEpochMilli());
    } else {
      auditStamp.setTime(System.currentTimeMillis());
    }
    try {
      auditStamp.setActor(Urn.createFromString(URN_LI_CORPUSER_DATAHUB));
    } catch (URISyntaxException e) {
      throw new RuntimeException("Unable to create actor urn:" + e);
    }
    return auditStamp;
  }

  private static void processParentJob(
      OpenLineage.RunEvent event,
      OpenLineage.Job job,
      DataJobInputOutput inputOutput,
      DatahubOpenlineageConfig datahubConf) {
    if ((event.getRun().getFacets() != null) && (event.getRun().getFacets().getParent() != null)) {
      DataJobUrn parentDataJobUrn =
          new DataJobUrn(
              getFlowUrn(
                  event.getRun().getFacets().getParent().getJob().getNamespace(),
                  event.getRun().getFacets().getParent().getJob().getName(),
                  null,
                  event.getRun().getFacets().getParent().get_producer(),
                  datahubConf),
              job.getName());

      Edge edge = createEdge(parentDataJobUrn, event.getEventTime());
      EdgeArray array = new EdgeArray();
      array.add(edge);
      inputOutput.setInputDatajobEdges(array);
    }
  }

  private static void processJobInputs(
      DatahubJob datahubJob, OpenLineage.RunEvent event, DatahubOpenlineageConfig datahubConf) {

    if (event.getInputs() == null) {
      return;
    }

    for (OpenLineage.InputDataset input :
        event.getInputs().stream()
            .filter(input -> input.getFacets() != null)
            .distinct()
            .collect(Collectors.toList())) {
      Optional<DatasetUrn> datasetUrn = convertOpenlineageDatasetToDatasetUrn(input, datahubConf);
      if (datasetUrn.isPresent()) {
        DatahubDataset.DatahubDatasetBuilder builder = DatahubDataset.builder();
        builder.urn(datasetUrn.get());
        if (datahubConf.isMaterializeDataset()) {
          builder.schemaMetadata(getSchemaMetadata(input));
        }
        if (datahubConf.isCaptureColumnLevelLineage()) {
          UpstreamLineage upstreamLineage = getFineGrainedLineage(input, datahubConf);
          if (upstreamLineage != null) {
            builder.lineage(upstreamLineage);
          }
        }
        datahubJob.getInSet().add(builder.build());
      }
    }
  }

  private static void processJobOutputs(
      DatahubJob datahubJob, OpenLineage.RunEvent event, DatahubOpenlineageConfig datahubConf) {

    if (event.getOutputs() == null) {
      return;
    }

    for (OpenLineage.OutputDataset output :
        event.getOutputs().stream()
            .filter(input -> input.getFacets() != null)
            .distinct()
            .collect(Collectors.toList())) {
      Optional<DatasetUrn> datasetUrn = convertOpenlineageDatasetToDatasetUrn(output, datahubConf);
      if (datasetUrn.isPresent()) {
        DatahubDataset.DatahubDatasetBuilder builder = DatahubDataset.builder();
        builder.urn(datasetUrn.get());
        if (datahubConf.isMaterializeDataset()) {
          builder.schemaMetadata(getSchemaMetadata(output));
        }
        if (datahubConf.isCaptureColumnLevelLineage()) {
          UpstreamLineage upstreamLineage = getFineGrainedLineage(output, datahubConf);
          if (upstreamLineage != null) {
            builder.lineage(upstreamLineage);
          }
        }
        datahubJob.getOutSet().add(builder.build());
      }
    }
  }

  private static DataProcessInstanceRunEvent processDataProcessInstanceResult(
      OpenLineage.RunEvent event) {
    DataProcessInstanceRunEvent dpire = new DataProcessInstanceRunEvent();

    DataProcessInstanceRunResult result = new DataProcessInstanceRunResult();
    switch (event.getEventType()) {
      case COMPLETE:
        dpire.setStatus(DataProcessRunStatus.COMPLETE);
        result.setType(RunResultType.SUCCESS);
        result.setNativeResultType(event.getEventType().toString());
        if (event.getEventTime() != null) {
          dpire.setTimestampMillis(event.getEventTime().toInstant().toEpochMilli());
        }
        dpire.setResult(result);
        break;
      case FAIL:
      case ABORT:
        dpire.setStatus(DataProcessRunStatus.COMPLETE);
        result.setType(RunResultType.FAILURE);
        result.setNativeResultType(event.getEventType().toString());
        if (event.getEventTime() != null) {
          dpire.setTimestampMillis(event.getEventTime().toInstant().toEpochMilli());
        }
        dpire.setResult(result);
        break;
      case START:
      case RUNNING:
        dpire.setStatus(DataProcessRunStatus.STARTED);
        // result.setNativeResultType(event.getEventType().toString());
        if (event.getEventTime() != null) {
          dpire.setTimestampMillis(event.getEventTime().toInstant().toEpochMilli());
        }
        break;
      case OTHER:
      default:
        result.setNativeResultType(event.getEventType().toString());
        if (event.getEventTime() != null) {
          dpire.setTimestampMillis(event.getEventTime().toInstant().toEpochMilli());
        }
        result.setType(RunResultType.$UNKNOWN);
        dpire.setResult(result);
        break;
    }
    return dpire;
  }

  public static String getFlowName(String jobName, String flowName) {
    String[] nameSplit = jobName.split("\\.");
    if (flowName != null) {
      return flowName;
    } else {
      return nameSplit[0];
    }
  }

  public static DataFlowUrn getFlowUrn(
      String namespace,
      String jobName,
      String processingEngine,
      URI producer,
      DatahubOpenlineageConfig datahubOpenlineageConfig) {
    String producerName = null;
    if (producer != null) {
      producerName = producer.toString();
    }

    String orchestrator = getOrchestrator(processingEngine, producerName);
    String flowName = datahubOpenlineageConfig.getPipelineName();
    if (datahubOpenlineageConfig.getPlatformInstance() != null) {
      namespace = datahubOpenlineageConfig.getPlatformInstance();
    }
    return (new DataFlowUrn(orchestrator, getFlowName(jobName, flowName), namespace));
  }

  public static DataFlowInfo convertRunEventToDataFlowInfo(
      OpenLineage.RunEvent event, String flowName) throws IOException {
    DataFlowInfo dataFlowInfo = new DataFlowInfo();
    dataFlowInfo.setName(getFlowName(event.getJob().getName(), flowName));
    return dataFlowInfo;
  }

  private static String getOrchestrator(String processingEngine, String producer) {
    String regex = "https://github.com/OpenLineage/OpenLineage/.*/(.*)$";
    Pattern pattern = Pattern.compile(regex, Pattern.MULTILINE);
    String orchestrator = null;
    if (processingEngine != null) {
      orchestrator = processingEngine.toLowerCase();
    } else if (producer != null) {
      Matcher matcher = pattern.matcher(producer);
      if ((matcher.matches()) && ((matcher.groupCount() == 1))) {
        orchestrator = matcher.group(1);
      } else if (producer.startsWith("https://github.com/apache/airflow/")) {
        orchestrator = "airflow";
      }
    }
    if (orchestrator == null) {
      throw new RuntimeException("Unable to determine orchestrator");
    }
    return orchestrator;
  }

  public static SchemaFieldDataType.Type convertOlFieldTypeToDHFieldType(
      String openLineageFieldType) {
    switch (openLineageFieldType) {
      case "string":
        return SchemaFieldDataType.Type.create(new StringType());
      case "long":
      case "int":
        return SchemaFieldDataType.Type.create(new NumberType());
      case "timestamp":
        return SchemaFieldDataType.Type.create(new TimeType());
      case "struct":
        return SchemaFieldDataType.Type.create(new MapType());
      default:
        return SchemaFieldDataType.Type.create(new NullType());
    }
  }

  public static SchemaMetadata getSchemaMetadata(OpenLineage.Dataset dataset) {
    SchemaFieldArray schemaFieldArray = new SchemaFieldArray();
    if ((dataset.getFacets() == null) || (dataset.getFacets().getSchema() == null)) {
      return null;
    }
    dataset
        .getFacets()
        .getSchema()
        .getFields()
        .forEach(
            field -> {
              SchemaField schemaField = new SchemaField();
              schemaField.setFieldPath(field.getName());
              schemaField.setNativeDataType(field.getType());
              schemaField.setType(
                  new SchemaFieldDataType()
                      .setType(convertOlFieldTypeToDHFieldType(field.getType())));
              schemaFieldArray.add(schemaField);
            });
    SchemaMetadata schemaMetadata = new SchemaMetadata();
    schemaMetadata.setPlatformSchema(new SchemaMetadata.PlatformSchema());
    schemaMetadata.setSchemaName("");
    schemaMetadata.setVersion(1L);
    schemaMetadata.setHash("");

    MySqlDDL ddl = new MySqlDDL();
    ddl.setTableSchema(OpenLineageClientUtils.toJson(dataset.getFacets().getSchema().getFields()));
    SchemaMetadata.PlatformSchema platformSchema = new SchemaMetadata.PlatformSchema();
    platformSchema.setMySqlDDL(ddl);
    schemaMetadata.setPlatformSchema(platformSchema);

    schemaMetadata.setPlatform(new DataPlatformUrn(dataset.getNamespace()));

    schemaMetadata.setFields(schemaFieldArray);
    return schemaMetadata;
  }
}
