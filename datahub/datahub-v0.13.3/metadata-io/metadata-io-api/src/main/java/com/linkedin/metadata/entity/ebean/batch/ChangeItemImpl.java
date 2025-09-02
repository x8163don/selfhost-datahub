package com.linkedin.metadata.entity.ebean.batch;

import com.datahub.util.exception.ModelConversionException;
import com.linkedin.common.AuditStamp;
import com.linkedin.common.urn.Urn;
import com.linkedin.data.template.RecordTemplate;
import com.linkedin.events.metadata.ChangeType;
import com.linkedin.metadata.aspect.AspectRetriever;
import com.linkedin.metadata.aspect.SystemAspect;
import com.linkedin.metadata.aspect.batch.ChangeMCP;
import com.linkedin.metadata.aspect.batch.MCPItem;
import com.linkedin.metadata.aspect.patch.template.common.GenericPatchTemplate;
import com.linkedin.metadata.entity.AspectUtils;
import com.linkedin.metadata.entity.EntityApiUtils;
import com.linkedin.metadata.entity.EntityAspect;
import com.linkedin.metadata.entity.validation.ValidationApiUtils;
import com.linkedin.metadata.models.AspectSpec;
import com.linkedin.metadata.models.EntitySpec;
import com.linkedin.metadata.utils.EntityKeyUtils;
import com.linkedin.metadata.utils.GenericRecordUtils;
import com.linkedin.metadata.utils.SystemMetadataUtils;
import com.linkedin.mxe.MetadataChangeProposal;
import com.linkedin.mxe.SystemMetadata;
import java.io.IOException;
import java.sql.Timestamp;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Getter
@Builder(toBuilder = true)
public class ChangeItemImpl implements ChangeMCP {
  public static ChangeItemImpl fromPatch(
      @Nonnull Urn urn,
      @Nonnull AspectSpec aspectSpec,
      @Nullable RecordTemplate recordTemplate,
      GenericPatchTemplate<? extends RecordTemplate> genericPatchTemplate,
      @Nonnull AuditStamp auditStamp,
      AspectRetriever aspectRetriever) {
    ChangeItemImplBuilder builder =
        ChangeItemImpl.builder().urn(urn).auditStamp(auditStamp).aspectName(aspectSpec.getName());

    RecordTemplate currentValue =
        recordTemplate != null ? recordTemplate : genericPatchTemplate.getDefault();

    try {
      builder.recordTemplate(genericPatchTemplate.applyPatch(currentValue));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    return builder.build(aspectRetriever);
  }

  // type of change
  @Nonnull private final ChangeType changeType;

  // urn an urn associated with the new aspect
  @Nonnull private final Urn urn;

  // aspectName name of the aspect being inserted
  @Nonnull private final String aspectName;

  @Nonnull private final RecordTemplate recordTemplate;

  @Nonnull private final SystemMetadata systemMetadata;

  @Nonnull private final AuditStamp auditStamp;

  @Nullable private final MetadataChangeProposal metadataChangeProposal;

  // derived
  @Nonnull private final EntitySpec entitySpec;
  @Nonnull private final AspectSpec aspectSpec;

  @Setter @Nullable private SystemAspect previousSystemAspect;
  @Setter private long nextAspectVersion;

  @Nonnull
  @Override
  public SystemAspect getSystemAspect(@Nullable Long version) {
    EntityAspect entityAspect = new EntityAspect();
    entityAspect.setAspect(getAspectName());
    entityAspect.setMetadata(EntityApiUtils.toJsonAspect(getRecordTemplate()));
    entityAspect.setUrn(getUrn().toString());
    entityAspect.setVersion(version == null ? getNextAspectVersion() : version);
    entityAspect.setCreatedOn(new Timestamp(getAuditStamp().getTime()));
    entityAspect.setCreatedBy(getAuditStamp().getActor().toString());
    entityAspect.setSystemMetadata(EntityApiUtils.toJsonAspect(getSystemMetadata()));
    return EntityAspect.EntitySystemAspect.builder()
        .build(getEntitySpec(), getAspectSpec(), entityAspect);
  }

  @Nonnull
  public MetadataChangeProposal getMetadataChangeProposal() {
    if (metadataChangeProposal != null) {
      return metadataChangeProposal;
    } else {
      final MetadataChangeProposal mcp = new MetadataChangeProposal();
      mcp.setEntityUrn(getUrn());
      mcp.setChangeType(getChangeType());
      mcp.setEntityType(getEntitySpec().getName());
      mcp.setAspectName(getAspectName());
      mcp.setAspect(GenericRecordUtils.serializeAspect(getRecordTemplate()));
      mcp.setSystemMetadata(getSystemMetadata());
      mcp.setEntityKeyAspect(
          GenericRecordUtils.serializeAspect(
              EntityKeyUtils.convertUrnToEntityKey(getUrn(), entitySpec.getKeyAspectSpec())));
      return mcp;
    }
  }

  public static class ChangeItemImplBuilder {

    // Ensure use of other builders
    private ChangeItemImpl build() {
      return null;
    }

    public ChangeItemImplBuilder systemMetadata(SystemMetadata systemMetadata) {
      this.systemMetadata = SystemMetadataUtils.generateSystemMetadataIfEmpty(systemMetadata);
      return this;
    }

    @SneakyThrows
    public ChangeItemImpl build(AspectRetriever aspectRetriever) {
      // Apply change type default
      this.changeType = validateOrDefaultChangeType(changeType);

      ValidationApiUtils.validateUrn(aspectRetriever.getEntityRegistry(), this.urn);
      log.debug("entity type = {}", this.urn.getEntityType());

      entitySpec(aspectRetriever.getEntityRegistry().getEntitySpec(this.urn.getEntityType()));
      log.debug("entity spec = {}", this.entitySpec);

      aspectSpec(ValidationApiUtils.validate(this.entitySpec, this.aspectName));
      log.debug("aspect spec = {}", this.aspectSpec);

      ValidationApiUtils.validateRecordTemplate(
          this.entitySpec, this.urn, this.recordTemplate, aspectRetriever);

      return new ChangeItemImpl(
          this.changeType,
          this.urn,
          this.aspectName,
          this.recordTemplate,
          SystemMetadataUtils.generateSystemMetadataIfEmpty(this.systemMetadata),
          this.auditStamp,
          this.metadataChangeProposal,
          this.entitySpec,
          this.aspectSpec,
          this.previousSystemAspect,
          this.nextAspectVersion);
    }

    public static ChangeItemImpl build(
        MetadataChangeProposal mcp, AuditStamp auditStamp, AspectRetriever aspectRetriever) {

      log.debug("entity type = {}", mcp.getEntityType());
      EntitySpec entitySpec =
          aspectRetriever.getEntityRegistry().getEntitySpec(mcp.getEntityType());
      AspectSpec aspectSpec = AspectUtils.validateAspect(mcp, entitySpec);

      if (!MCPItem.isValidChangeType(ChangeType.UPSERT, aspectSpec)) {
        throw new UnsupportedOperationException(
            "ChangeType not supported: "
                + mcp.getChangeType()
                + " for aspect "
                + mcp.getAspectName());
      }

      Urn urn = mcp.getEntityUrn();
      if (urn == null) {
        urn = EntityKeyUtils.getUrnFromProposal(mcp, entitySpec.getKeyAspectSpec());
      }

      return ChangeItemImpl.builder()
          .changeType(mcp.getChangeType())
          .urn(urn)
          .aspectName(mcp.getAspectName())
          .systemMetadata(
              SystemMetadataUtils.generateSystemMetadataIfEmpty(mcp.getSystemMetadata()))
          .metadataChangeProposal(mcp)
          .auditStamp(auditStamp)
          .recordTemplate(convertToRecordTemplate(mcp, aspectSpec))
          .build(aspectRetriever);
    }

    // specific to impl, other impls support PATCH, etc
    private static ChangeType validateOrDefaultChangeType(@Nullable ChangeType changeType) {
      final ChangeType finalChangeType = changeType == null ? ChangeType.UPSERT : changeType;
      if (!MCPItem.CHANGE_TYPES.contains(finalChangeType)) {
        throw new IllegalArgumentException(
            String.format("ChangeType %s not in %s", changeType, MCPItem.CHANGE_TYPES));
      }
      return finalChangeType;
    }

    private static RecordTemplate convertToRecordTemplate(
        MetadataChangeProposal mcp, AspectSpec aspectSpec) {
      RecordTemplate aspect;
      try {
        aspect =
            GenericRecordUtils.deserializeAspect(
                mcp.getAspect().getValue(), mcp.getAspect().getContentType(), aspectSpec);
        ValidationApiUtils.validateOrThrow(aspect);
      } catch (ModelConversionException e) {
        throw new RuntimeException(
            String.format(
                "Could not deserialize %s for aspect %s",
                mcp.getAspect().getValue(), mcp.getAspectName()));
      }
      return aspect;
    }
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    ChangeItemImpl that = (ChangeItemImpl) o;
    return urn.equals(that.urn)
        && aspectName.equals(that.aspectName)
        && Objects.equals(systemMetadata, that.systemMetadata)
        && recordTemplate.equals(that.recordTemplate);
  }

  @Override
  public int hashCode() {
    return Objects.hash(urn, aspectName, systemMetadata, recordTemplate);
  }

  @Override
  public String toString() {
    return "ChangeItemImpl{"
        + "changeType="
        + changeType
        + ", urn="
        + urn
        + ", aspectName='"
        + aspectName
        + '\''
        + ", recordTemplate="
        + recordTemplate
        + ", systemMetadata="
        + systemMetadata
        + '}';
  }
}
