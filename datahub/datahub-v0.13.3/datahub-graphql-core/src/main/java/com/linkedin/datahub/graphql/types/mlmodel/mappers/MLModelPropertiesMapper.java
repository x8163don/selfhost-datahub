package com.linkedin.datahub.graphql.types.mlmodel.mappers;

import static com.linkedin.datahub.graphql.authorization.AuthorizationUtils.canView;

import com.linkedin.common.urn.Urn;
import com.linkedin.datahub.graphql.QueryContext;
import com.linkedin.datahub.graphql.generated.MLModelGroup;
import com.linkedin.datahub.graphql.generated.MLModelProperties;
import com.linkedin.datahub.graphql.types.common.mappers.CustomPropertiesMapper;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import lombok.NonNull;

public class MLModelPropertiesMapper {

  public static final MLModelPropertiesMapper INSTANCE = new MLModelPropertiesMapper();

  public static MLModelProperties map(
      @Nullable final QueryContext context,
      @NonNull final com.linkedin.ml.metadata.MLModelProperties mlModelProperties,
      Urn entityUrn) {
    return INSTANCE.apply(context, mlModelProperties, entityUrn);
  }

  public MLModelProperties apply(
      @Nullable final QueryContext context,
      @NonNull final com.linkedin.ml.metadata.MLModelProperties mlModelProperties,
      Urn entityUrn) {
    final MLModelProperties result = new MLModelProperties();

    result.setDate(mlModelProperties.getDate());
    result.setDescription(mlModelProperties.getDescription());
    if (mlModelProperties.getExternalUrl() != null) {
      result.setExternalUrl(mlModelProperties.getExternalUrl().toString());
    }
    if (mlModelProperties.getVersion() != null) {
      result.setVersion(mlModelProperties.getVersion().getVersionTag());
    }
    result.setType(mlModelProperties.getType());
    if (mlModelProperties.getHyperParams() != null) {
      result.setHyperParams(
          mlModelProperties.getHyperParams().stream()
              .map(param -> MLHyperParamMapper.map(context, param))
              .collect(Collectors.toList()));
    }

    result.setCustomProperties(
        CustomPropertiesMapper.map(mlModelProperties.getCustomProperties(), entityUrn));

    if (mlModelProperties.getTrainingMetrics() != null) {
      result.setTrainingMetrics(
          mlModelProperties.getTrainingMetrics().stream()
              .map(metric -> MLMetricMapper.map(context, metric))
              .collect(Collectors.toList()));
    }

    if (mlModelProperties.getGroups() != null) {
      result.setGroups(
          mlModelProperties.getGroups().stream()
              .filter(g -> context == null || canView(context.getOperationContext(), g))
              .map(
                  group -> {
                    final MLModelGroup subgroup = new MLModelGroup();
                    subgroup.setUrn(group.toString());
                    return subgroup;
                  })
              .collect(Collectors.toList()));
    }

    if (mlModelProperties.getMlFeatures() != null) {
      result.setMlFeatures(
          mlModelProperties.getMlFeatures().stream()
              .filter(f -> context == null || canView(context.getOperationContext(), f))
              .map(Urn::toString)
              .collect(Collectors.toList()));
    }
    result.setTags(mlModelProperties.getTags());

    return result;
  }
}
