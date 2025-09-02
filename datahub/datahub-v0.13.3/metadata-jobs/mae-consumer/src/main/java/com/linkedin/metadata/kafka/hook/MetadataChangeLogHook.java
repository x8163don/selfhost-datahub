package com.linkedin.metadata.kafka.hook;

import com.linkedin.mxe.MetadataChangeLog;
import io.datahubproject.metadata.context.OperationContext;
import javax.annotation.Nonnull;

/**
 * Custom hook which is invoked on receiving a new {@link MetadataChangeLog} event.
 *
 * <p>The semantics of this hook are currently "at most once". That is, the hook will not be called
 * with the same message. In the future, we intend to migrate to "at least once" semantics, meaning
 * that the hook will be responsible for implementing idempotency.
 */
public interface MetadataChangeLogHook {

  /** Initialize the hook */
  default MetadataChangeLogHook init(@Nonnull OperationContext systemOperationContext) {
    return this;
  }

  /**
   * Return whether the hook is enabled or not. If not enabled, the below invoke method is not
   * triggered
   */
  default boolean isEnabled() {
    return true;
  }

  /** Invoke the hook when a MetadataChangeLog is received */
  void invoke(@Nonnull MetadataChangeLog log) throws Exception;

  /**
   * Controls hook execution ordering
   *
   * @return order to execute
   */
  default int executionOrder() {
    return 100;
  }
}
