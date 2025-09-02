package auth;

import static auth.AuthUtils.*;
import static utils.ConfigUtil.*;

import auth.sso.SsoManager;
import client.AuthServiceClient;
import com.datahub.authentication.Actor;
import com.datahub.authentication.ActorType;
import com.datahub.authentication.Authentication;
import com.datahub.plugins.auth.authorization.Authorizer;
import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.google.inject.name.Named;
import com.linkedin.entity.client.SystemEntityClient;
import com.linkedin.entity.client.SystemRestliEntityClient;
import com.linkedin.metadata.models.registry.EmptyEntityRegistry;
import com.linkedin.metadata.restli.DefaultRestliClientFactory;
import com.linkedin.parseq.retry.backoff.ExponentialBackoff;
import com.linkedin.util.Configuration;
import config.ConfigurationProvider;
import controllers.SsoCallbackController;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

import io.datahubproject.metadata.context.ActorContext;
import io.datahubproject.metadata.context.AuthorizerContext;
import io.datahubproject.metadata.context.EntityRegistryContext;
import io.datahubproject.metadata.context.OperationContext;
import io.datahubproject.metadata.context.OperationContextConfig;
import io.datahubproject.metadata.context.SearchContext;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.pac4j.core.config.Config;
import org.pac4j.core.context.session.SessionStore;
import org.pac4j.play.LogoutController;
import org.pac4j.play.http.PlayHttpActionAdapter;
import org.pac4j.play.store.PlayCacheSessionStore;
import org.pac4j.play.store.PlayCookieSessionStore;
import org.pac4j.play.store.PlaySessionStore;
import org.pac4j.play.store.ShiroAesDataEncrypter;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import play.Environment;
import play.cache.SyncCacheApi;
import utils.ConfigUtil;

/** Responsible for configuring, validating, and providing authentication related components. */
@Slf4j
public class AuthModule extends AbstractModule {

  /**
   * Pac4j Stores Session State in a browser-side cookie in encrypted fashion. This configuration
   * value provides a stable encryption base from which to derive the encryption key.
   *
   * <p>We hash this value (SHA256), then take the first 16 bytes as the AES key.
   */
  private static final String PAC4J_AES_KEY_BASE_CONF = "play.http.secret.key";

  private static final String PAC4J_SESSIONSTORE_PROVIDER_CONF = "pac4j.sessionStore.provider";
  private static final String ENTITY_CLIENT_RETRY_INTERVAL = "entityClient.retryInterval";
  private static final String ENTITY_CLIENT_NUM_RETRIES = "entityClient.numRetries";
  private static final String ENTITY_CLIENT_RESTLI_GET_BATCH_SIZE = "entityClient.restli.get.batchSize";
  private static final String GET_SSO_SETTINGS_ENDPOINT = "auth/getSsoSettings";

  private final com.typesafe.config.Config _configs;

  public AuthModule(final Environment environment, final com.typesafe.config.Config configs) {
    _configs = configs;
  }

  @Override
  protected void configure() {
    /**
     * In Pac4J, you are given the option to store the profiles of authenticated users in either (i)
     * PlayCacheSessionStore - saves your data in the Play cache or (ii) PlayCookieSessionStore
     * saves your data in the Play session cookie However there is problem
     * (https://github.com/datahub-project/datahub/issues/4448) observed when storing the Pac4j
     * profile in cookie. Whenever the profile returned by Pac4j is greater than 4096 characters,
     * the response will be rejected by the browser. Default to PlayCacheCookieStore so that
     * datahub-frontend container remains as a stateless service
     */
    String sessionStoreProvider = _configs.getString(PAC4J_SESSIONSTORE_PROVIDER_CONF);

    if (sessionStoreProvider.equals("PlayCacheSessionStore")) {
      final PlayCacheSessionStore playCacheSessionStore =
          new PlayCacheSessionStore(getProvider(SyncCacheApi.class));
      bind(SessionStore.class).toInstance(playCacheSessionStore);
      bind(PlaySessionStore.class).toInstance(playCacheSessionStore);
    } else {
      PlayCookieSessionStore playCacheCookieStore;
      try {
        // To generate a valid encryption key from an input value, we first
        // hash the input to generate a fixed-length string. Then, we convert
        // it to hex and slice the first 16 bytes, because AES key length must strictly
        // have a specific length.
        final String aesKeyBase = _configs.getString(PAC4J_AES_KEY_BASE_CONF);
        final String aesKeyHash =
            DigestUtils.sha256Hex(aesKeyBase.getBytes(StandardCharsets.UTF_8));
        final String aesEncryptionKey = aesKeyHash.substring(0, 16);
        playCacheCookieStore =
            new PlayCookieSessionStore(new ShiroAesDataEncrypter(aesEncryptionKey.getBytes()));
      } catch (Exception e) {
        throw new RuntimeException("Failed to instantiate Pac4j cookie session store!", e);
      }
      bind(SessionStore.class).toInstance(playCacheCookieStore);
      bind(PlaySessionStore.class).toInstance(playCacheCookieStore);
    }

    try {
      bind(SsoCallbackController.class)
          .toConstructor(
              SsoCallbackController.class.getConstructor(
                  SsoManager.class,
                  OperationContext.class,
                  SystemEntityClient.class,
                  AuthServiceClient.class,
                  org.pac4j.core.config.Config.class,
                  com.typesafe.config.Config.class));
    } catch (NoSuchMethodException | SecurityException e) {
      throw new RuntimeException(
          "Failed to bind to SsoCallbackController. Cannot find constructor", e);
    }
    // logout
    final LogoutController logoutController = new LogoutController();
    logoutController.setDefaultUrl("/");
    bind(LogoutController.class).toInstance(logoutController);
  }

  @Provides
  @Singleton
  protected Config provideConfig() {
    Config config = new Config();
    config.setHttpActionAdapter(new PlayHttpActionAdapter());
    return config;
  }

  @Provides
  @Singleton
  protected SsoManager provideSsoManager(
      Authentication systemAuthentication, CloseableHttpClient httpClient) {
    SsoManager manager =
        new SsoManager(
            _configs, systemAuthentication, getSsoSettingsRequestUrl(_configs), httpClient);
    manager.initializeSsoProvider();
    return manager;
  }

  @Provides
  @Singleton
  protected Authentication provideSystemAuthentication() {
    // Returns an instance of Authentication used to authenticate system initiated calls to Metadata
    // Service.
    String systemClientId = _configs.getString(SYSTEM_CLIENT_ID_CONFIG_PATH);
    String systemSecret = _configs.getString(SYSTEM_CLIENT_SECRET_CONFIG_PATH);
    final Actor systemActor =
        new Actor(ActorType.USER, systemClientId); // TODO: Change to service actor once supported.
    return new Authentication(
        systemActor,
        String.format("Basic %s:%s", systemClientId, systemSecret),
        Collections.emptyMap());
  }

  @Provides
  @Singleton
  @Named("systemOperationContext")
  protected OperationContext provideOperationContext(
          final Authentication systemAuthentication,
          final ConfigurationProvider configurationProvider) {
    ActorContext systemActorContext =
            ActorContext.builder()
                    .systemAuth(true)
                    .authentication(systemAuthentication)
                    .build();
    OperationContextConfig systemConfig = OperationContextConfig.builder()
            .viewAuthorizationConfiguration(configurationProvider.getAuthorization().getView())
            .allowSystemAuthentication(true)
            .build();

    return OperationContext.builder()
            .operationContextConfig(systemConfig)
            .systemActorContext(systemActorContext)
            .searchContext(SearchContext.EMPTY)
            .entityRegistryContext(EntityRegistryContext.builder().build(EmptyEntityRegistry.EMPTY))
            // Authorizer.EMPTY doesn't actually apply to system auth
            .authorizerContext(AuthorizerContext.builder().authorizer(Authorizer.EMPTY).build())
            .build(systemAuthentication);
  }

  @Provides
  @Singleton
  protected ConfigurationProvider provideConfigurationProvider() {
    AnnotationConfigApplicationContext context =
        new AnnotationConfigApplicationContext(ConfigurationProvider.class);
    return context.getBean(ConfigurationProvider.class);
  }

  @Provides
  @Singleton
  protected SystemEntityClient provideEntityClient(
      @Named("systemOperationContext") final OperationContext systemOperationContext,
      final ConfigurationProvider configurationProvider) {

    return new SystemRestliEntityClient(
        buildRestliClient(),
        new ExponentialBackoff(_configs.getInt(ENTITY_CLIENT_RETRY_INTERVAL)),
        _configs.getInt(ENTITY_CLIENT_NUM_RETRIES),
        configurationProvider.getCache().getClient().getEntityClient(),
        Math.max(1, _configs.getInt(ENTITY_CLIENT_RESTLI_GET_BATCH_SIZE)));
  }

  @Provides
  @Singleton
  protected AuthServiceClient provideAuthClient(
      Authentication systemAuthentication, CloseableHttpClient httpClient) {
    // Init a GMS auth client
    final String metadataServiceHost = getMetadataServiceHost(_configs);

    final int metadataServicePort = getMetadataServicePort(_configs);

    final boolean metadataServiceUseSsl = doesMetadataServiceUseSsl(_configs);

    return new AuthServiceClient(
        metadataServiceHost,
        metadataServicePort,
        metadataServiceUseSsl,
        systemAuthentication,
        httpClient);
  }

  @Provides
  @Singleton
  protected CloseableHttpClient provideHttpClient() {
    return HttpClients.createDefault();
  }

  private com.linkedin.restli.client.Client buildRestliClient() {
    final String metadataServiceHost =
        utils.ConfigUtil.getString(
            _configs,
            METADATA_SERVICE_HOST_CONFIG_PATH,
            utils.ConfigUtil.DEFAULT_METADATA_SERVICE_HOST);
    final int metadataServicePort =
        utils.ConfigUtil.getInt(
            _configs,
            utils.ConfigUtil.METADATA_SERVICE_PORT_CONFIG_PATH,
            utils.ConfigUtil.DEFAULT_METADATA_SERVICE_PORT);
    final boolean metadataServiceUseSsl =
        utils.ConfigUtil.getBoolean(
            _configs,
            utils.ConfigUtil.METADATA_SERVICE_USE_SSL_CONFIG_PATH,
            ConfigUtil.DEFAULT_METADATA_SERVICE_USE_SSL);
    final String metadataServiceSslProtocol =
        utils.ConfigUtil.getString(
            _configs,
            utils.ConfigUtil.METADATA_SERVICE_SSL_PROTOCOL_CONFIG_PATH,
            ConfigUtil.DEFAULT_METADATA_SERVICE_SSL_PROTOCOL);
    return DefaultRestliClientFactory.getRestLiClient(
        metadataServiceHost,
        metadataServicePort,
        metadataServiceUseSsl,
        metadataServiceSslProtocol);
  }

  protected boolean doesMetadataServiceUseSsl(com.typesafe.config.Config configs) {
    return configs.hasPath(METADATA_SERVICE_USE_SSL_CONFIG_PATH)
        ? configs.getBoolean(METADATA_SERVICE_USE_SSL_CONFIG_PATH)
        : Boolean.parseBoolean(
            Configuration.getEnvironmentVariable(GMS_USE_SSL_ENV_VAR, DEFAULT_GMS_USE_SSL));
  }

  protected String getMetadataServiceHost(com.typesafe.config.Config configs) {
    return configs.hasPath(METADATA_SERVICE_HOST_CONFIG_PATH)
        ? configs.getString(METADATA_SERVICE_HOST_CONFIG_PATH)
        : Configuration.getEnvironmentVariable(GMS_HOST_ENV_VAR, DEFAULT_GMS_HOST);
  }

  protected Integer getMetadataServicePort(com.typesafe.config.Config configs) {
    return configs.hasPath(METADATA_SERVICE_PORT_CONFIG_PATH)
        ? configs.getInt(METADATA_SERVICE_PORT_CONFIG_PATH)
        : Integer.parseInt(
            Configuration.getEnvironmentVariable(GMS_PORT_ENV_VAR, DEFAULT_GMS_PORT));
  }

  protected String getSsoSettingsRequestUrl(com.typesafe.config.Config configs) {
    final String protocol = doesMetadataServiceUseSsl(configs) ? "https" : "http";
    final String metadataServiceHost = getMetadataServiceHost(configs);
    final Integer metadataServicePort = getMetadataServicePort(configs);

    return String.format(
        "%s://%s:%s/%s",
        protocol, metadataServiceHost, metadataServicePort, GET_SSO_SETTINGS_ENDPOINT);
  }
}
