package com.linkedin.metadata.service;

import static com.linkedin.metadata.Constants.*;

import com.google.common.collect.ImmutableSet;
import com.linkedin.common.urn.Urn;
import com.linkedin.entity.EntityResponse;
import com.linkedin.entity.client.SystemEntityClient;
import com.linkedin.identity.CorpUserSettings;
import com.linkedin.metadata.Constants;
import com.linkedin.metadata.entity.AspectUtils;
import com.linkedin.mxe.MetadataChangeProposal;
import com.linkedin.settings.global.GlobalSettingsInfo;
import io.datahubproject.metadata.context.OperationContext;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;

/**
 * This class is used to permit easy CRUD operations on both <b>Global</b> and <b>Personal</b>
 * DataHub settings.
 *
 * <p>Note that no Authorization is performed within the service. The expectation is that the caller
 * has already verified the permissions of the active Actor.
 */
@Slf4j
public class SettingsService extends BaseService {

  public SettingsService(@Nonnull final SystemEntityClient entityClient) {
    super(entityClient);
  }

  /**
   * Returns the settings for a particular user, or null if they do not exist yet.
   *
   * @param user the urn of the user to fetch settings for
   * @param authentication the current authentication
   * @return an instance of {@link CorpUserSettings} for the specified user, or null if none exists.
   */
  @Nullable
  public CorpUserSettings getCorpUserSettings(
      @Nonnull OperationContext opContext, @Nonnull final Urn user) {
    Objects.requireNonNull(user, "user must not be null");
    Objects.requireNonNull(opContext.getSessionAuthentication(), "authentication must not be null");
    try {
      EntityResponse response =
          this.entityClient.getV2(
              opContext,
              CORP_USER_ENTITY_NAME,
              user,
              ImmutableSet.of(CORP_USER_SETTINGS_ASPECT_NAME));
      if (response != null
          && response.getAspects().containsKey(Constants.CORP_USER_SETTINGS_ASPECT_NAME)) {
        return new CorpUserSettings(
            response.getAspects().get(Constants.CORP_USER_SETTINGS_ASPECT_NAME).getValue().data());
      }
      // No aspect found
      return null;
    } catch (Exception e) {
      throw new RuntimeException(
          String.format("Failed to retrieve Corp User settings for user with urn %s", user), e);
    }
  }

  /**
   * Updates the settings for a given user.
   *
   * <p>Note that this method does not do authorization validation. It is assumed that users of this
   * class have already authorized the operation.
   *
   * @param user the urn of the user
   * @param authentication the current authentication
   */
  public void updateCorpUserSettings(
      @Nonnull OperationContext opContext,
      @Nonnull final Urn user,
      @Nonnull final CorpUserSettings newSettings) {
    Objects.requireNonNull(user, "user must not be null");
    Objects.requireNonNull(newSettings, "newSettings must not be null");
    Objects.requireNonNull(opContext.getSessionAuthentication(), "authentication must not be null");
    try {
      MetadataChangeProposal proposal =
          AspectUtils.buildMetadataChangeProposal(
              user, CORP_USER_SETTINGS_ASPECT_NAME, newSettings);
      this.entityClient.ingestProposal(opContext, proposal, false);
    } catch (Exception e) {
      throw new RuntimeException(
          String.format("Failed to update Corp User settings for user with urn %s", user), e);
    }
  }

  /**
   * Returns the Global Settings. They are expected to exist.
   *
   * @param authentication the current authentication
   * @return an instance of {@link GlobalSettingsInfo}, or null if none exists.
   */
  public GlobalSettingsInfo getGlobalSettings(@Nonnull OperationContext opContext) {
    Objects.requireNonNull(opContext.getSessionAuthentication(), "authentication must not be null");
    try {
      EntityResponse response =
          this.entityClient.getV2(
              opContext,
              GLOBAL_SETTINGS_ENTITY_NAME,
              GLOBAL_SETTINGS_URN,
              ImmutableSet.of(GLOBAL_SETTINGS_INFO_ASPECT_NAME));
      if (response != null
          && response.getAspects().containsKey(Constants.GLOBAL_SETTINGS_INFO_ASPECT_NAME)) {
        return new GlobalSettingsInfo(
            response
                .getAspects()
                .get(Constants.GLOBAL_SETTINGS_INFO_ASPECT_NAME)
                .getValue()
                .data());
      }
      // No aspect found
      log.warn(
          "Failed to retrieve Global Settings. No settings exist, but they should. Returning null");
      return null;
    } catch (Exception e) {
      throw new RuntimeException("Failed to retrieve Global Settings!", e);
    }
  }

  /**
   * Updates the Global settings.
   *
   * <p>This performs a read-modify-write of the underlying GlobalSettingsInfo aspect.
   *
   * <p>Note that this method does not do authorization validation. It is assumed that users of this
   * class have already authorized the operation.
   *
   * @param newSettings the new value for the global settings.
   * @param authentication the current authentication
   */
  public void updateGlobalSettings(
      @Nonnull OperationContext opContext, @Nonnull final GlobalSettingsInfo newSettings) {
    Objects.requireNonNull(newSettings, "newSettings must not be null");
    Objects.requireNonNull(opContext.getSessionAuthentication(), "authentication must not be null");
    try {
      MetadataChangeProposal proposal =
          AspectUtils.buildMetadataChangeProposal(
              GLOBAL_SETTINGS_URN, GLOBAL_SETTINGS_INFO_ASPECT_NAME, newSettings);
      this.entityClient.ingestProposal(opContext, proposal, false);
    } catch (Exception e) {
      throw new RuntimeException("Failed to update Global settings", e);
    }
  }
}
