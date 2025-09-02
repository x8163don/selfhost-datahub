package com.linkedin.datahub.graphql.types.dataset.mappers;

import com.linkedin.common.urn.Urn;
import com.linkedin.datahub.graphql.QueryContext;
import com.linkedin.datahub.graphql.generated.Dataset;
import com.linkedin.datahub.graphql.generated.ForeignKeyConstraint;
import com.linkedin.datahub.graphql.generated.SchemaFieldEntity;
import com.linkedin.datahub.graphql.types.common.mappers.UrnToEntityMapper;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ForeignKeyConstraintMapper {
  private ForeignKeyConstraintMapper() {}

  public static ForeignKeyConstraint map(
      @Nullable QueryContext context, com.linkedin.schema.ForeignKeyConstraint constraint) {
    ForeignKeyConstraint result = new ForeignKeyConstraint();
    result.setName(constraint.getName());
    if (constraint.hasForeignDataset()) {
      result.setForeignDataset(
          (Dataset) UrnToEntityMapper.map(context, constraint.getForeignDataset()));
    }
    if (constraint.hasSourceFields()) {
      result.setSourceFields(
          constraint.getSourceFields().stream()
              .map(schemaFieldUrn -> mapSchemaFieldEntity(context, schemaFieldUrn))
              .collect(Collectors.toList()));
    }
    if (constraint.hasForeignFields()) {
      result.setForeignFields(
          constraint.getForeignFields().stream()
              .map(schemaFieldUrn -> mapSchemaFieldEntity(context, schemaFieldUrn))
              .collect(Collectors.toList()));
    }
    return result;
  }

  private static SchemaFieldEntity mapSchemaFieldEntity(
      @Nullable QueryContext context, Urn schemaFieldUrn) {
    SchemaFieldEntity result = new SchemaFieldEntity();
    try {
      Urn resourceUrn = Urn.createFromString(schemaFieldUrn.getEntityKey().get(0));
      result.setParent(UrnToEntityMapper.map(context, resourceUrn));
    } catch (Exception e) {
      throw new RuntimeException("Error converting schemaField parent urn string to Urn", e);
    }
    result.setFieldPath(schemaFieldUrn.getEntityKey().get(1));
    return result;
  }
}
