package com.linkedin.datahub.graphql.types.dataset.mappers;

import static com.linkedin.datahub.graphql.authorization.AuthorizationUtils.canView;
import static com.linkedin.metadata.Constants.*;

import com.linkedin.common.Deprecation;
import com.linkedin.common.GlobalTags;
import com.linkedin.common.GlossaryTerms;
import com.linkedin.common.InstitutionalMemory;
import com.linkedin.common.Ownership;
import com.linkedin.common.Status;
import com.linkedin.common.urn.Urn;
import com.linkedin.data.DataMap;
import com.linkedin.datahub.graphql.QueryContext;
import com.linkedin.datahub.graphql.authorization.AuthorizationUtils;
import com.linkedin.datahub.graphql.generated.Container;
import com.linkedin.datahub.graphql.generated.DataPlatform;
import com.linkedin.datahub.graphql.generated.DatasetEditableProperties;
import com.linkedin.datahub.graphql.generated.EntityType;
import com.linkedin.datahub.graphql.generated.FabricType;
import com.linkedin.datahub.graphql.generated.VersionedDataset;
import com.linkedin.datahub.graphql.types.common.mappers.CustomPropertiesMapper;
import com.linkedin.datahub.graphql.types.common.mappers.DeprecationMapper;
import com.linkedin.datahub.graphql.types.common.mappers.InstitutionalMemoryMapper;
import com.linkedin.datahub.graphql.types.common.mappers.OwnershipMapper;
import com.linkedin.datahub.graphql.types.common.mappers.StatusMapper;
import com.linkedin.datahub.graphql.types.common.mappers.util.MappingHelper;
import com.linkedin.datahub.graphql.types.domain.DomainAssociationMapper;
import com.linkedin.datahub.graphql.types.glossary.mappers.GlossaryTermsMapper;
import com.linkedin.datahub.graphql.types.mappers.ModelMapper;
import com.linkedin.datahub.graphql.types.tag.mappers.GlobalTagsMapper;
import com.linkedin.dataset.DatasetDeprecation;
import com.linkedin.dataset.DatasetProperties;
import com.linkedin.dataset.EditableDatasetProperties;
import com.linkedin.dataset.ViewProperties;
import com.linkedin.domain.Domains;
import com.linkedin.entity.EntityResponse;
import com.linkedin.entity.EnvelopedAspectMap;
import com.linkedin.metadata.key.DatasetKey;
import com.linkedin.mxe.SystemMetadata;
import com.linkedin.schema.EditableSchemaMetadata;
import com.linkedin.schema.SchemaMetadata;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;

/**
 * Maps GMS response objects to objects conforming to the GQL schema.
 *
 * <p>To be replaced by auto-generated mappers implementations
 */
@Slf4j
public class VersionedDatasetMapper implements ModelMapper<EntityResponse, VersionedDataset> {

  public static final VersionedDatasetMapper INSTANCE = new VersionedDatasetMapper();

  public static VersionedDataset map(
      @Nullable final QueryContext context, @Nonnull final EntityResponse dataset) {
    return INSTANCE.apply(context, dataset);
  }

  @Override
  public VersionedDataset apply(
      @Nullable final QueryContext context, @Nonnull final EntityResponse entityResponse) {
    VersionedDataset result = new VersionedDataset();
    Urn entityUrn = entityResponse.getUrn();
    result.setUrn(entityResponse.getUrn().toString());
    result.setType(EntityType.DATASET);

    EnvelopedAspectMap aspectMap = entityResponse.getAspects();
    MappingHelper<VersionedDataset> mappingHelper = new MappingHelper<>(aspectMap, result);
    SystemMetadata schemaSystemMetadata = getSystemMetadata(aspectMap, SCHEMA_METADATA_ASPECT_NAME);

    mappingHelper.mapToResult(DATASET_KEY_ASPECT_NAME, this::mapDatasetKey);
    mappingHelper.mapToResult(
        DATASET_PROPERTIES_ASPECT_NAME,
        (entity, dataMap) -> this.mapDatasetProperties(entity, dataMap, entityUrn));
    mappingHelper.mapToResult(
        DATASET_DEPRECATION_ASPECT_NAME,
        (dataset, dataMap) ->
            dataset.setDeprecation(
                DatasetDeprecationMapper.map(context, new DatasetDeprecation(dataMap))));
    mappingHelper.mapToResult(
        SCHEMA_METADATA_ASPECT_NAME,
        (dataset, dataMap) ->
            dataset.setSchema(
                SchemaMapper.map(
                    context, new SchemaMetadata(dataMap), schemaSystemMetadata, entityUrn)));
    mappingHelper.mapToResult(
        EDITABLE_DATASET_PROPERTIES_ASPECT_NAME, this::mapEditableDatasetProperties);
    mappingHelper.mapToResult(VIEW_PROPERTIES_ASPECT_NAME, this::mapViewProperties);
    mappingHelper.mapToResult(
        INSTITUTIONAL_MEMORY_ASPECT_NAME,
        (dataset, dataMap) ->
            dataset.setInstitutionalMemory(
                InstitutionalMemoryMapper.map(
                    context, new InstitutionalMemory(dataMap), entityUrn)));
    mappingHelper.mapToResult(
        OWNERSHIP_ASPECT_NAME,
        (dataset, dataMap) ->
            dataset.setOwnership(OwnershipMapper.map(context, new Ownership(dataMap), entityUrn)));
    mappingHelper.mapToResult(
        STATUS_ASPECT_NAME,
        (dataset, dataMap) -> dataset.setStatus(StatusMapper.map(context, new Status(dataMap))));
    mappingHelper.mapToResult(
        GLOBAL_TAGS_ASPECT_NAME,
        (dataset, dataMap) -> mapGlobalTags(context, dataset, dataMap, entityUrn));
    mappingHelper.mapToResult(
        EDITABLE_SCHEMA_METADATA_ASPECT_NAME,
        (dataset, dataMap) ->
            dataset.setEditableSchemaMetadata(
                EditableSchemaMetadataMapper.map(
                    context, new EditableSchemaMetadata(dataMap), entityUrn)));
    mappingHelper.mapToResult(
        GLOSSARY_TERMS_ASPECT_NAME,
        (dataset, dataMap) ->
            dataset.setGlossaryTerms(
                GlossaryTermsMapper.map(context, new GlossaryTerms(dataMap), entityUrn)));
    mappingHelper.mapToResult(
        context, CONTAINER_ASPECT_NAME, VersionedDatasetMapper::mapContainers);
    mappingHelper.mapToResult(context, DOMAINS_ASPECT_NAME, VersionedDatasetMapper::mapDomains);
    mappingHelper.mapToResult(
        DEPRECATION_ASPECT_NAME,
        (dataset, dataMap) ->
            dataset.setDeprecation(DeprecationMapper.map(context, new Deprecation(dataMap))));

    if (context != null && !canView(context.getOperationContext(), entityUrn)) {
      return AuthorizationUtils.restrictEntity(mappingHelper.getResult(), VersionedDataset.class);
    } else {
      return mappingHelper.getResult();
    }
  }

  private SystemMetadata getSystemMetadata(EnvelopedAspectMap aspectMap, String aspectName) {
    if (aspectMap.containsKey(aspectName) && aspectMap.get(aspectName).hasSystemMetadata()) {
      return aspectMap.get(aspectName).getSystemMetadata();
    }
    return null;
  }

  private void mapDatasetKey(@Nonnull VersionedDataset dataset, @Nonnull DataMap dataMap) {
    final DatasetKey gmsKey = new DatasetKey(dataMap);
    dataset.setName(gmsKey.getName());
    dataset.setOrigin(FabricType.valueOf(gmsKey.getOrigin().toString()));
    dataset.setPlatform(
        DataPlatform.builder()
            .setType(EntityType.DATA_PLATFORM)
            .setUrn(gmsKey.getPlatform().toString())
            .build());
  }

  private void mapDatasetProperties(
      @Nonnull VersionedDataset dataset, @Nonnull DataMap dataMap, Urn entityUrn) {
    final DatasetProperties gmsProperties = new DatasetProperties(dataMap);
    final com.linkedin.datahub.graphql.generated.DatasetProperties properties =
        new com.linkedin.datahub.graphql.generated.DatasetProperties();
    properties.setDescription(gmsProperties.getDescription());
    properties.setOrigin(dataset.getOrigin());
    if (gmsProperties.getExternalUrl() != null) {
      properties.setExternalUrl(gmsProperties.getExternalUrl().toString());
    }
    properties.setCustomProperties(
        CustomPropertiesMapper.map(gmsProperties.getCustomProperties(), entityUrn));
    if (gmsProperties.getName() != null) {
      properties.setName(gmsProperties.getName());
    } else {
      properties.setName(dataset.getName());
    }
    properties.setQualifiedName(gmsProperties.getQualifiedName());
    dataset.setProperties(properties);
  }

  private void mapEditableDatasetProperties(
      @Nonnull VersionedDataset dataset, @Nonnull DataMap dataMap) {
    final EditableDatasetProperties editableDatasetProperties =
        new EditableDatasetProperties(dataMap);
    final DatasetEditableProperties editableProperties = new DatasetEditableProperties();
    editableProperties.setDescription(editableDatasetProperties.getDescription());
    dataset.setEditableProperties(editableProperties);
  }

  private void mapViewProperties(@Nonnull VersionedDataset dataset, @Nonnull DataMap dataMap) {
    final ViewProperties properties = new ViewProperties(dataMap);
    final com.linkedin.datahub.graphql.generated.ViewProperties graphqlProperties =
        new com.linkedin.datahub.graphql.generated.ViewProperties();
    graphqlProperties.setMaterialized(properties.isMaterialized());
    graphqlProperties.setLanguage(properties.getViewLanguage());
    graphqlProperties.setLogic(properties.getViewLogic());
    dataset.setViewProperties(graphqlProperties);
  }

  private static void mapGlobalTags(
      @Nullable final QueryContext context,
      @Nonnull VersionedDataset dataset,
      @Nonnull DataMap dataMap,
      @Nonnull Urn entityUrn) {
    com.linkedin.datahub.graphql.generated.GlobalTags globalTags =
        GlobalTagsMapper.map(context, new GlobalTags(dataMap), entityUrn);
    dataset.setTags(globalTags);
  }

  private static void mapContainers(
      @Nullable final QueryContext context,
      @Nonnull VersionedDataset dataset,
      @Nonnull DataMap dataMap) {
    final com.linkedin.container.Container gmsContainer =
        new com.linkedin.container.Container(dataMap);
    dataset.setContainer(
        Container.builder()
            .setType(EntityType.CONTAINER)
            .setUrn(gmsContainer.getContainer().toString())
            .build());
  }

  private static void mapDomains(
      @Nullable final QueryContext context,
      @Nonnull VersionedDataset dataset,
      @Nonnull DataMap dataMap) {
    final Domains domains = new Domains(dataMap);
    // Currently we only take the first domain if it exists.
    dataset.setDomain(DomainAssociationMapper.map(context, domains, dataset.getUrn()));
  }
}
