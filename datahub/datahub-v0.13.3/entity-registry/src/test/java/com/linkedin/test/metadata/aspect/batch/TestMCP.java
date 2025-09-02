package com.linkedin.test.metadata.aspect.batch;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.linkedin.common.AuditStamp;
import com.linkedin.common.urn.Urn;
import com.linkedin.data.template.RecordTemplate;
import com.linkedin.events.metadata.ChangeType;
import com.linkedin.metadata.aspect.ReadItem;
import com.linkedin.metadata.aspect.SystemAspect;
import com.linkedin.metadata.aspect.batch.BatchItem;
import com.linkedin.metadata.aspect.batch.ChangeMCP;
import com.linkedin.metadata.models.AspectSpec;
import com.linkedin.metadata.models.EntitySpec;
import com.linkedin.metadata.models.registry.EntityRegistry;
import com.linkedin.mxe.MetadataChangeProposal;
import com.linkedin.mxe.SystemMetadata;
import com.linkedin.test.metadata.aspect.TestEntityRegistry;
import java.net.URISyntaxException;
import java.util.Collection;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

@Builder(toBuilder = true)
@Getter
public class TestMCP implements ChangeMCP {
  private static final String TEST_DATASET_URN =
      "urn:li:dataset:(urn:li:dataPlatform:datahub,Test,PROD)";

  public static <T extends RecordTemplate> Collection<ReadItem> ofOneBatchItem(
      Urn urn, T aspect, EntityRegistry entityRegistry) {
    return Set.of(
        TestMCP.builder()
            .urn(urn)
            .entitySpec(entityRegistry.getEntitySpec(urn.getEntityType()))
            .aspectSpec(
                entityRegistry.getAspectSpecs().get(TestEntityRegistry.getAspectName(aspect)))
            .recordTemplate(aspect)
            .build());
  }

  public static <T extends RecordTemplate> Collection<ReadItem> ofOneBatchItemDatasetUrn(
      T aspect, EntityRegistry entityRegistry) {
    try {
      return ofOneBatchItem(Urn.createFromString(TEST_DATASET_URN), aspect, entityRegistry);
    } catch (URISyntaxException e) {
      throw new RuntimeException(e);
    }
  }

  public static <T extends RecordTemplate> Set<BatchItem> ofOneUpsertItem(
      Urn urn, T aspect, EntityRegistry entityRegistry) {
    return Set.of(
        TestMCP.builder()
            .urn(urn)
            .entitySpec(entityRegistry.getEntitySpec(urn.getEntityType()))
            .aspectSpec(
                entityRegistry.getAspectSpecs().get(TestEntityRegistry.getAspectName(aspect)))
            .recordTemplate(aspect)
            .build());
  }

  public static <T extends RecordTemplate> Set<BatchItem> ofOneUpsertItemDatasetUrn(
      T aspect, EntityRegistry entityRegistry) {
    try {
      return ofOneUpsertItem(Urn.createFromString(TEST_DATASET_URN), aspect, entityRegistry);
    } catch (URISyntaxException e) {
      throw new RuntimeException(e);
    }
  }

  public static <T extends RecordTemplate> Set<ChangeMCP> ofOneMCP(
      Urn urn, T newAspect, EntityRegistry entityRegistry) {
    return ofOneMCP(urn, null, newAspect, entityRegistry);
  }

  public static <T extends RecordTemplate> Set<ChangeMCP> ofOneMCP(
      Urn urn, @Nullable T oldAspect, T newAspect, EntityRegistry entityRegistry) {

    SystemAspect mockNewSystemAspect = mock(SystemAspect.class);
    when(mockNewSystemAspect.getRecordTemplate()).thenReturn(newAspect);
    when(mockNewSystemAspect.getAspect(any(Class.class)))
        .thenAnswer(args -> ReadItem.getAspect(args.getArgument(0), newAspect));

    SystemAspect mockOldSystemAspect = null;
    if (oldAspect != null) {
      mockOldSystemAspect = mock(SystemAspect.class);
      when(mockOldSystemAspect.getRecordTemplate()).thenReturn(oldAspect);
      when(mockOldSystemAspect.getAspect(any(Class.class)))
          .thenAnswer(args -> ReadItem.getAspect(args.getArgument(0), oldAspect));
    }

    return Set.of(
        TestMCP.builder()
            .urn(urn)
            .entitySpec(entityRegistry.getEntitySpec(urn.getEntityType()))
            .aspectSpec(
                entityRegistry.getAspectSpecs().get(TestEntityRegistry.getAspectName(newAspect)))
            .recordTemplate(newAspect)
            .systemAspect(mockNewSystemAspect)
            .previousSystemAspect(mockOldSystemAspect)
            .build());
  }

  private Urn urn;
  private RecordTemplate recordTemplate;
  private SystemMetadata systemMetadata;
  private AuditStamp auditStamp;
  private ChangeType changeType;
  @Nonnull private final EntitySpec entitySpec;
  @Nonnull private final AspectSpec aspectSpec;
  private SystemAspect systemAspect;
  private MetadataChangeProposal metadataChangeProposal;
  @Setter private SystemAspect previousSystemAspect;
  @Setter private long nextAspectVersion;

  @Nonnull
  @Override
  public SystemAspect getSystemAspect(@Nullable Long nextAspectVersion) {
    return null;
  }
}
