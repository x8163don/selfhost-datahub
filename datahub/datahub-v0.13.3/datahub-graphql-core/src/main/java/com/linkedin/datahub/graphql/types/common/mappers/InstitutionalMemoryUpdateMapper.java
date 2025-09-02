package com.linkedin.datahub.graphql.types.common.mappers;

import com.linkedin.common.InstitutionalMemory;
import com.linkedin.common.InstitutionalMemoryMetadataArray;
import com.linkedin.datahub.graphql.QueryContext;
import com.linkedin.datahub.graphql.generated.InstitutionalMemoryUpdate;
import com.linkedin.datahub.graphql.types.mappers.ModelMapper;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class InstitutionalMemoryUpdateMapper
    implements ModelMapper<InstitutionalMemoryUpdate, InstitutionalMemory> {

  private static final InstitutionalMemoryUpdateMapper INSTANCE =
      new InstitutionalMemoryUpdateMapper();

  public static InstitutionalMemory map(
      @Nullable QueryContext context, @Nonnull final InstitutionalMemoryUpdate input) {
    return INSTANCE.apply(context, input);
  }

  @Override
  public InstitutionalMemory apply(
      @Nullable QueryContext context, @Nonnull final InstitutionalMemoryUpdate input) {
    final InstitutionalMemory institutionalMemory = new InstitutionalMemory();
    institutionalMemory.setElements(
        new InstitutionalMemoryMetadataArray(
            input.getElements().stream()
                .map(e -> InstitutionalMemoryMetadataUpdateMapper.map(context, e))
                .collect(Collectors.toList())));
    return institutionalMemory;
  }
}
