package com.linkedin.metadata.aspect.batch;

import com.linkedin.metadata.aspect.ReadItem;
import com.linkedin.metadata.aspect.RetrieverContext;
import com.linkedin.metadata.aspect.SystemAspect;
import com.linkedin.metadata.aspect.plugins.hooks.MutationHook;
import com.linkedin.metadata.aspect.plugins.validation.ValidationExceptionCollection;
import com.linkedin.mxe.SystemMetadata;
import com.linkedin.util.Pair;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nonnull;
import org.apache.commons.lang3.StringUtils;

/**
 * A batch of aspects in the context of either an MCP or MCL write path to a data store. The item is
 * a record that encapsulates the change type, raw aspect and ancillary information like {@link
 * SystemMetadata} and record/message created time
 */
public interface AspectsBatch {
  Collection<? extends BatchItem> getItems();

  RetrieverContext getRetrieverContext();

  /**
   * Returns MCP items. Could be patch, upsert, etc.
   *
   * @return batch items
   */
  default List<MCPItem> getMCPItems() {
    return getItems().stream()
        .filter(item -> item instanceof MCPItem)
        .map(item -> (MCPItem) item)
        .collect(Collectors.toList());
  }

  /**
   * Convert patches to upserts, apply hooks at the aspect and batch level.
   *
   * @param latestAspects latest version in the database
   * @return The new urn/aspectnames and the uniform upserts, possibly expanded/mutated by the
   *     various hooks
   */
  Pair<Map<String, Set<String>>, List<ChangeMCP>> toUpsertBatchItems(
      Map<String, Map<String, SystemAspect>> latestAspects);

  /**
   * Apply read mutations to batch
   *
   * @param items
   */
  default void applyReadMutationHooks(Collection<ReadItem> items) {
    applyReadMutationHooks(items, getRetrieverContext());
  }

  static void applyReadMutationHooks(
      Collection<ReadItem> items, @Nonnull RetrieverContext retrieverContext) {
    for (MutationHook mutationHook :
        retrieverContext.getAspectRetriever().getEntityRegistry().getAllMutationHooks()) {
      mutationHook.applyReadMutation(items, retrieverContext);
    }
  }

  /**
   * Apply write mutations to batch
   *
   * @param changeMCPS
   */
  default void applyWriteMutationHooks(Collection<ChangeMCP> changeMCPS) {
    applyWriteMutationHooks(changeMCPS, getRetrieverContext());
  }

  static void applyWriteMutationHooks(
      Collection<ChangeMCP> changeMCPS, @Nonnull RetrieverContext retrieverContext) {
    for (MutationHook mutationHook :
        retrieverContext.getAspectRetriever().getEntityRegistry().getAllMutationHooks()) {
      mutationHook.applyWriteMutation(changeMCPS, retrieverContext);
    }
  }

  default <T extends BatchItem> ValidationExceptionCollection validateProposed(
      Collection<T> mcpItems) {
    return validateProposed(mcpItems, getRetrieverContext());
  }

  static <T extends BatchItem> ValidationExceptionCollection validateProposed(
      Collection<T> mcpItems, @Nonnull RetrieverContext retrieverContext) {
    ValidationExceptionCollection exceptions = ValidationExceptionCollection.newCollection();
    retrieverContext
        .getAspectRetriever()
        .getEntityRegistry()
        .getAllAspectPayloadValidators()
        .stream()
        .flatMap(validator -> validator.validateProposed(mcpItems, retrieverContext))
        .forEach(exceptions::addException);
    return exceptions;
  }

  default ValidationExceptionCollection validatePreCommit(Collection<ChangeMCP> changeMCPs) {
    return validatePreCommit(changeMCPs, getRetrieverContext());
  }

  static ValidationExceptionCollection validatePreCommit(
      Collection<ChangeMCP> changeMCPs, @Nonnull RetrieverContext retrieverContext) {
    ValidationExceptionCollection exceptions = ValidationExceptionCollection.newCollection();
    retrieverContext
        .getAspectRetriever()
        .getEntityRegistry()
        .getAllAspectPayloadValidators()
        .stream()
        .flatMap(validator -> validator.validatePreCommit(changeMCPs, retrieverContext))
        .forEach(exceptions::addException);
    return exceptions;
  }

  default Stream<ChangeMCP> applyMCPSideEffects(Collection<ChangeMCP> items) {
    return applyMCPSideEffects(items, getRetrieverContext());
  }

  static Stream<ChangeMCP> applyMCPSideEffects(
      Collection<ChangeMCP> items, @Nonnull RetrieverContext retrieverContext) {
    return retrieverContext.getAspectRetriever().getEntityRegistry().getAllMCPSideEffects().stream()
        .flatMap(mcpSideEffect -> mcpSideEffect.apply(items, retrieverContext));
  }

  default Stream<MCLItem> applyMCLSideEffects(Collection<MCLItem> items) {
    return applyMCLSideEffects(items, getRetrieverContext());
  }

  static Stream<MCLItem> applyMCLSideEffects(
      Collection<MCLItem> items, @Nonnull RetrieverContext retrieverContext) {
    return retrieverContext.getAspectRetriever().getEntityRegistry().getAllMCLSideEffects().stream()
        .flatMap(mclSideEffect -> mclSideEffect.apply(items, retrieverContext));
  }

  default boolean containsDuplicateAspects() {
    return getItems().stream()
            .map(i -> String.format("%s_%s", i.getClass().getName(), i.hashCode()))
            .distinct()
            .count()
        != getItems().size();
  }

  default Map<String, Set<String>> getUrnAspectsMap() {
    return getItems().stream()
        .map(aspect -> Pair.of(aspect.getUrn().toString(), aspect.getAspectName()))
        .collect(
            Collectors.groupingBy(
                Pair::getKey, Collectors.mapping(Pair::getValue, Collectors.toSet())));
  }

  default Map<String, Set<String>> getNewUrnAspectsMap(
      Map<String, Set<String>> existingMap, List<? extends BatchItem> items) {
    Map<String, HashSet<String>> newItemsMap =
        items.stream()
            .map(aspect -> Pair.of(aspect.getUrn().toString(), aspect.getAspectName()))
            .collect(
                Collectors.groupingBy(
                    Pair::getKey,
                    Collectors.mapping(Pair::getValue, Collectors.toCollection(HashSet::new))));

    return newItemsMap.entrySet().stream()
        .filter(
            entry ->
                !existingMap.containsKey(entry.getKey())
                    || !existingMap.get(entry.getKey()).containsAll(entry.getValue()))
        .peek(
            entry -> {
              if (existingMap.containsKey(entry.getKey())) {
                entry.getValue().removeAll(existingMap.get(entry.getKey()));
              }
            })
        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
  }

  static <T> Map<String, Map<String, T>> merge(
      @Nonnull Map<String, Map<String, T>> a, @Nonnull Map<String, Map<String, T>> b) {
    return Stream.concat(a.entrySet().stream(), b.entrySet().stream())
        .flatMap(
            entry ->
                entry.getValue().entrySet().stream()
                    .map(innerEntry -> Pair.of(entry.getKey(), innerEntry)))
        .collect(
            Collectors.groupingBy(
                Pair::getKey,
                Collectors.mapping(
                    Pair::getValue, Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue))));
  }

  default String toAbbreviatedString(int maxWidth) {
    return toAbbreviatedString(getItems(), maxWidth);
  }

  static String toAbbreviatedString(Collection<? extends BatchItem> items, int maxWidth) {
    List<String> itemsAbbreviated = new ArrayList<String>();
    items.forEach(
        item -> {
          if (item instanceof ChangeMCP) {
            itemsAbbreviated.add(((ChangeMCP) item).toAbbreviatedString());
          } else {
            itemsAbbreviated.add(item.toString());
          }
        });
    return "AspectsBatchImpl{"
        + "items="
        + StringUtils.abbreviate(itemsAbbreviated.toString(), maxWidth)
        + '}';
  }
}
