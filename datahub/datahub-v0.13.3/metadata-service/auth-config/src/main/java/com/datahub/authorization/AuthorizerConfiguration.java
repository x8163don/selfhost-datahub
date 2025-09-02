package com.datahub.authorization;

import com.datahub.plugins.auth.authorization.Authorizer;
import java.util.Map;
import lombok.Data;

/** POJO representing {@link Authorizer} configurations provided in the application.yaml. */
@Data
public class AuthorizerConfiguration {
  /** Whether to enable this authorizer */
  private boolean enabled;

  /** A fully-qualified class name for the {@link Authorizer} implementation to be registered. */
  private String type;

  /** A set of authorizer-specific configurations passed through during "init" of the authorizer. */
  private Map<String, Object> configs;
}
