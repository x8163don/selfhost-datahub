package com.linkedin.metadata.aspect.plugins;

import com.linkedin.common.urn.Urn;
import com.linkedin.events.metadata.ChangeType;
import com.linkedin.metadata.aspect.plugins.config.AspectPluginConfig;
import com.linkedin.metadata.models.AspectSpec;
import com.linkedin.metadata.models.EntitySpec;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;

@AllArgsConstructor
@EqualsAndHashCode
public abstract class PluginSpec {
  protected static String ENTITY_WILDCARD = "*";

  @Nonnull
  public abstract AspectPluginConfig getConfig();

  public abstract PluginSpec setConfig(@Nonnull AspectPluginConfig config);

  public boolean enabled() {
    return true;
  }

  public boolean shouldApply(
      @Nullable ChangeType changeType, @Nonnull Urn entityUrn, @Nonnull AspectSpec aspectSpec) {
    return shouldApply(changeType, entityUrn.getEntityType(), aspectSpec);
  }

  public boolean shouldApply(
      @Nullable ChangeType changeType,
      @Nonnull EntitySpec entitySpec,
      @Nonnull AspectSpec aspectSpec) {
    return shouldApply(changeType, entitySpec.getName(), aspectSpec.getName());
  }

  public boolean shouldApply(
      @Nullable ChangeType changeType, @Nonnull String entityName, @Nonnull AspectSpec aspectSpec) {
    return shouldApply(changeType, entityName, aspectSpec.getName());
  }

  public boolean shouldApply(
      @Nullable ChangeType changeType, @Nonnull String entityName, @Nonnull String aspectName) {
    return getConfig().isEnabled()
        && isChangeTypeSupported(changeType)
        && isEntityAspectSupported(entityName, aspectName);
  }

  protected boolean isEntityAspectSupported(
      @Nonnull EntitySpec entitySpec, @Nonnull AspectSpec aspectSpec) {
    return isEntityAspectSupported(entitySpec.getName(), aspectSpec.getName());
  }

  protected boolean isEntityAspectSupported(
      @Nonnull String entityName, @Nonnull String aspectName) {
    return (getConfig().getSupportedEntityAspectNames().stream()
            .anyMatch(
                supported ->
                    ENTITY_WILDCARD.equals(supported.getEntityName())
                        || supported.getEntityName().equals(entityName)))
        && isAspectSupported(aspectName);
  }

  protected boolean isAspectSupported(@Nonnull String aspectName) {
    return getConfig().getSupportedEntityAspectNames().stream()
        .anyMatch(
            supported ->
                ENTITY_WILDCARD.equals(supported.getAspectName())
                    || supported.getAspectName().equals(aspectName));
  }

  protected boolean isChangeTypeSupported(@Nullable ChangeType changeType) {
    return (changeType == null && getConfig().getSupportedOperations().isEmpty())
        || getConfig().getSupportedOperations().stream()
            .anyMatch(supported -> supported.equalsIgnoreCase(String.valueOf(changeType)));
  }
}
