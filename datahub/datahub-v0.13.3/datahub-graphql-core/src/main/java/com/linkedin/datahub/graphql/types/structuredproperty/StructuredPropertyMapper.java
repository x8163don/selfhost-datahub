package com.linkedin.datahub.graphql.types.structuredproperty;

import static com.linkedin.metadata.Constants.*;

import com.linkedin.common.urn.Urn;
import com.linkedin.data.DataMap;
import com.linkedin.data.template.StringArrayMap;
import com.linkedin.datahub.graphql.QueryContext;
import com.linkedin.datahub.graphql.generated.AllowedValue;
import com.linkedin.datahub.graphql.generated.DataTypeEntity;
import com.linkedin.datahub.graphql.generated.EntityType;
import com.linkedin.datahub.graphql.generated.EntityTypeEntity;
import com.linkedin.datahub.graphql.generated.NumberValue;
import com.linkedin.datahub.graphql.generated.PropertyCardinality;
import com.linkedin.datahub.graphql.generated.StringValue;
import com.linkedin.datahub.graphql.generated.StructuredPropertyDefinition;
import com.linkedin.datahub.graphql.generated.StructuredPropertyEntity;
import com.linkedin.datahub.graphql.generated.TypeQualifier;
import com.linkedin.datahub.graphql.types.common.mappers.util.MappingHelper;
import com.linkedin.datahub.graphql.types.mappers.ModelMapper;
import com.linkedin.entity.EntityResponse;
import com.linkedin.entity.EnvelopedAspectMap;
import com.linkedin.structured.PropertyValueArray;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class StructuredPropertyMapper
    implements ModelMapper<EntityResponse, StructuredPropertyEntity> {

  private static final String ALLOWED_TYPES = "allowedTypes";

  public static final StructuredPropertyMapper INSTANCE = new StructuredPropertyMapper();

  public static StructuredPropertyEntity map(
      @Nullable QueryContext context, @Nonnull final EntityResponse entityResponse) {
    return INSTANCE.apply(context, entityResponse);
  }

  @Override
  public StructuredPropertyEntity apply(
      @Nullable QueryContext queryContext, @Nonnull final EntityResponse entityResponse) {
    final StructuredPropertyEntity result = new StructuredPropertyEntity();
    result.setUrn(entityResponse.getUrn().toString());
    result.setType(EntityType.STRUCTURED_PROPERTY);
    EnvelopedAspectMap aspectMap = entityResponse.getAspects();
    MappingHelper<StructuredPropertyEntity> mappingHelper = new MappingHelper<>(aspectMap, result);
    mappingHelper.mapToResult(
        STRUCTURED_PROPERTY_DEFINITION_ASPECT_NAME, (this::mapStructuredPropertyDefinition));
    return mappingHelper.getResult();
  }

  private void mapStructuredPropertyDefinition(
      @Nonnull StructuredPropertyEntity extendedProperty, @Nonnull DataMap dataMap) {
    com.linkedin.structured.StructuredPropertyDefinition gmsDefinition =
        new com.linkedin.structured.StructuredPropertyDefinition(dataMap);
    StructuredPropertyDefinition definition = new StructuredPropertyDefinition();
    definition.setQualifiedName(gmsDefinition.getQualifiedName());
    definition.setCardinality(
        PropertyCardinality.valueOf(gmsDefinition.getCardinality().toString()));
    definition.setImmutable(gmsDefinition.isImmutable());
    definition.setValueType(createDataTypeEntity(gmsDefinition.getValueType()));
    if (gmsDefinition.hasDisplayName()) {
      definition.setDisplayName(gmsDefinition.getDisplayName());
    }
    if (gmsDefinition.getDescription() != null) {
      definition.setDescription(gmsDefinition.getDescription());
    }
    if (gmsDefinition.hasAllowedValues()) {
      definition.setAllowedValues(mapAllowedValues(gmsDefinition.getAllowedValues()));
    }
    if (gmsDefinition.hasTypeQualifier()) {
      definition.setTypeQualifier(mapTypeQualifier(gmsDefinition.getTypeQualifier()));
    }
    definition.setEntityTypes(
        gmsDefinition.getEntityTypes().stream()
            .map(this::createEntityTypeEntity)
            .collect(Collectors.toList()));
    extendedProperty.setDefinition(definition);
  }

  private List<AllowedValue> mapAllowedValues(@Nonnull PropertyValueArray gmsValues) {
    List<AllowedValue> allowedValues = new ArrayList<>();
    gmsValues.forEach(
        value -> {
          final AllowedValue allowedValue = new AllowedValue();
          if (value.getValue().isString()) {
            allowedValue.setValue(new StringValue(value.getValue().getString()));
          } else if (value.getValue().isDouble()) {
            allowedValue.setValue(new NumberValue(value.getValue().getDouble()));
          }
          if (value.getDescription() != null) {
            allowedValue.setDescription(value.getDescription());
          }
          allowedValues.add(allowedValue);
        });
    return allowedValues;
  }

  private DataTypeEntity createDataTypeEntity(final Urn dataTypeUrn) {
    final DataTypeEntity dataType = new DataTypeEntity();
    dataType.setUrn(dataTypeUrn.toString());
    dataType.setType(EntityType.DATA_TYPE);
    return dataType;
  }

  private TypeQualifier mapTypeQualifier(final StringArrayMap gmsTypeQualifier) {
    final TypeQualifier typeQualifier = new TypeQualifier();
    List<String> allowedTypes = gmsTypeQualifier.get(ALLOWED_TYPES);
    if (allowedTypes != null) {
      typeQualifier.setAllowedTypes(
          allowedTypes.stream().map(this::createEntityTypeEntity).collect(Collectors.toList()));
    }
    return typeQualifier;
  }

  private EntityTypeEntity createEntityTypeEntity(final Urn entityTypeUrn) {
    return createEntityTypeEntity(entityTypeUrn.toString());
  }

  private EntityTypeEntity createEntityTypeEntity(final String entityTypeUrnStr) {
    final EntityTypeEntity entityType = new EntityTypeEntity();
    entityType.setUrn(entityTypeUrnStr);
    entityType.setType(EntityType.ENTITY_TYPE);
    return entityType;
  }
}
