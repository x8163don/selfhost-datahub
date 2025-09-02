package com.linkedin.datahub.graphql.types.common.mappers;

import com.linkedin.datahub.graphql.QueryContext;
import com.linkedin.datahub.graphql.generated.DataPlatformInstance;
import com.linkedin.datahub.graphql.generated.EntityType;
import com.linkedin.datahub.graphql.types.mappers.ModelMapper;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class DataPlatformInstanceAspectMapper
    implements ModelMapper<com.linkedin.common.DataPlatformInstance, DataPlatformInstance> {

  public static final DataPlatformInstanceAspectMapper INSTANCE =
      new DataPlatformInstanceAspectMapper();

  public static DataPlatformInstance map(
      @Nullable QueryContext context,
      @Nonnull final com.linkedin.common.DataPlatformInstance dataPlatformInstance) {
    return INSTANCE.apply(context, dataPlatformInstance);
  }

  @Override
  public DataPlatformInstance apply(
      @Nullable QueryContext context,
      @Nonnull final com.linkedin.common.DataPlatformInstance input) {
    final DataPlatformInstance result = new DataPlatformInstance();
    if (input.hasInstance()) {
      result.setType(EntityType.DATA_PLATFORM_INSTANCE);
      result.setUrn(input.getInstance().toString());
    }
    return result;
  }
}
