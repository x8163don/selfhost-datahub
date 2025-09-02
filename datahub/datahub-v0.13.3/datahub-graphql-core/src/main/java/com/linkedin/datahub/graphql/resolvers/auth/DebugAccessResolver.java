package com.linkedin.datahub.graphql.resolvers.auth;

import static com.linkedin.metadata.Constants.*;

import com.google.common.collect.ImmutableSet;
import com.linkedin.common.EntityRelationship;
import com.linkedin.common.EntityRelationships;
import com.linkedin.common.urn.Urn;
import com.linkedin.data.template.StringArray;
import com.linkedin.datahub.graphql.QueryContext;
import com.linkedin.datahub.graphql.authorization.AuthorizationUtils;
import com.linkedin.datahub.graphql.exception.AuthorizationException;
import com.linkedin.datahub.graphql.generated.DebugAccessResult;
import com.linkedin.entity.EntityResponse;
import com.linkedin.entity.client.EntityClient;
import com.linkedin.metadata.Constants;
import com.linkedin.metadata.graph.GraphClient;
import com.linkedin.metadata.query.filter.Condition;
import com.linkedin.metadata.query.filter.ConjunctiveCriterion;
import com.linkedin.metadata.query.filter.ConjunctiveCriterionArray;
import com.linkedin.metadata.query.filter.Criterion;
import com.linkedin.metadata.query.filter.CriterionArray;
import com.linkedin.metadata.query.filter.Filter;
import com.linkedin.metadata.query.filter.RelationshipDirection;
import com.linkedin.metadata.query.filter.SortCriterion;
import com.linkedin.metadata.query.filter.SortOrder;
import com.linkedin.metadata.search.SearchEntity;
import com.linkedin.policy.DataHubPolicyInfo;
import com.linkedin.r2.RemoteInvocationException;
import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class DebugAccessResolver implements DataFetcher<CompletableFuture<DebugAccessResult>> {

  private static final String LAST_UPDATED_AT_FIELD = "lastUpdatedTimestamp";
  private final EntityClient _entityClient;
  private final GraphClient _graphClient;

  public DebugAccessResolver(EntityClient entityClient, GraphClient graphClient) {
    _entityClient = entityClient;
    _graphClient = graphClient;
  }

  @Override
  public CompletableFuture<DebugAccessResult> get(DataFetchingEnvironment environment)
      throws Exception {
    return CompletableFuture.supplyAsync(
        () -> {
          final QueryContext context = environment.getContext();

          if (!AuthorizationUtils.canManageUsersAndGroups(context)) {
            throw new AuthorizationException(
                "Unauthorized to perform this action. Please contact your DataHub administrator.");
          }
          final String userUrn = environment.getArgument("userUrn");

          return populateDebugAccessResult(userUrn, context);
        });
  }

  public DebugAccessResult populateDebugAccessResult(String userUrn, QueryContext context) {

    try {
      final String actorUrn = context.getActorUrn();
      final DebugAccessResult result = new DebugAccessResult();
      final List<String> types =
          Arrays.asList("IsMemberOfRole", "IsMemberOfGroup", "IsMemberOfNativeGroup");
      EntityRelationships entityRelationships = getEntityRelationships(userUrn, types, actorUrn);

      List<String> roles =
          getUrnsFromEntityRelationships(entityRelationships, Constants.DATAHUB_ROLE_ENTITY_NAME);

      List<String> groups =
          getUrnsFromEntityRelationships(entityRelationships, Constants.CORP_GROUP_ENTITY_NAME);
      List<String> groupsWithRoles = new ArrayList<>();

      Set<String> rolesViaGroups = new HashSet<>();
      groups.forEach(
          groupUrn -> {
            EntityRelationships groupRelationships =
                getEntityRelationships(groupUrn, List.of("IsMemberOfRole"), actorUrn);
            List<String> rolesOfGroup =
                getUrnsFromEntityRelationships(
                    groupRelationships, Constants.DATAHUB_ROLE_ENTITY_NAME);
            if (rolesOfGroup.isEmpty()) {
              return;
            }
            groupsWithRoles.add(groupUrn);
            rolesViaGroups.addAll(rolesOfGroup);
          });
      Set<String> allRoles = new HashSet<>(roles);
      allRoles.addAll(rolesViaGroups);

      result.setRoles(roles);
      result.setGroups(groups);
      result.setGroupsWithRoles(groupsWithRoles);
      result.setRolesViaGroups(new ArrayList<>(rolesViaGroups));
      result.setAllRoles(new ArrayList<>(allRoles));

      Set<Urn> policyUrns = getPoliciesFor(context, userUrn, groups, result.getAllRoles());

      // List of Policy that apply to this user directly or indirectly.
      result.setPolicies(policyUrns.stream().map(Urn::toString).collect(Collectors.toList()));

      // List of privileges that this user has directly or indirectly.
      result.setPrivileges(new ArrayList<>(getPrivileges(context, policyUrns)));

      return result;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private Set<String> getPrivileges(final QueryContext context, Set<Urn> policyUrns) {
    try {
      final Map<Urn, EntityResponse> policies =
          _entityClient.batchGetV2(
              context.getOperationContext(),
              Constants.POLICY_ENTITY_NAME,
              policyUrns,
              ImmutableSet.of(Constants.DATAHUB_POLICY_INFO_ASPECT_NAME));

      return policies.keySet().stream()
          .filter(Objects::nonNull)
          .filter(key -> policies.get(key) != null)
          .filter(key -> policies.get(key).hasAspects())
          .map(key -> policies.get(key).getAspects())
          .filter(aspectMap -> aspectMap.containsKey(DATAHUB_POLICY_INFO_ASPECT_NAME))
          .map(
              aspectMap ->
                  new DataHubPolicyInfo(
                      aspectMap.get(DATAHUB_POLICY_INFO_ASPECT_NAME).getValue().data()))
          .map(DataHubPolicyInfo::getPrivileges)
          .flatMap(List::stream)
          .collect(Collectors.toSet());
    } catch (URISyntaxException | RemoteInvocationException e) {
      throw new RuntimeException("Failed to retrieve privileges from GMS", e);
    }
  }

  private List<String> getUrnsFromEntityRelationships(
      EntityRelationships entityRelationships, String entityName) {
    return entityRelationships.getRelationships().stream()
        .map(EntityRelationship::getEntity)
        .filter(entity -> entityName.equals(entity.getEntityType()))
        .map(Urn::toString)
        .distinct()
        .collect(Collectors.toList());
  }

  private EntityRelationships getEntityRelationships(
      final String urn, final List<String> types, final String actor) {
    return _graphClient.getRelatedEntities(
        urn, types, RelationshipDirection.OUTGOING, 0, 100, actor);
  }

  private Set<Urn> getPoliciesFor(
      final QueryContext context,
      final String user,
      final List<String> groups,
      final List<String> roles)
      throws RemoteInvocationException {
    final SortCriterion sortCriterion =
        new SortCriterion().setField(LAST_UPDATED_AT_FIELD).setOrder(SortOrder.DESCENDING);
    return _entityClient
        .search(
            context.getOperationContext().withSearchFlags(flags -> flags.setFulltext(true)),
            Constants.POLICY_ENTITY_NAME,
            "",
            buildFilterToGetPolicies(user, groups, roles),
            sortCriterion,
            0,
            10000)
        .getEntities()
        .stream()
        .map(SearchEntity::getEntity)
        .collect(Collectors.toSet());
  }

  private Filter buildFilterToGetPolicies(
      final String user, final List<String> groups, final List<String> roles) {

    // setOr(array(andArray(user), andArray(groups), andArray(roles), andArray(allUsers),
    // andArray(allGroups))
    ConjunctiveCriterionArray conjunctiveCriteria = new ConjunctiveCriterionArray();

    final CriterionArray allUsersAndArray = new CriterionArray();
    allUsersAndArray.add(
        new Criterion().setField("allUsers").setValue("true").setCondition(Condition.EQUAL));
    conjunctiveCriteria.add(new ConjunctiveCriterion().setAnd(allUsersAndArray));

    final CriterionArray allGroupsAndArray = new CriterionArray();
    allGroupsAndArray.add(
        new Criterion().setField("allGroups").setValue("true").setCondition(Condition.EQUAL));
    conjunctiveCriteria.add(new ConjunctiveCriterion().setAnd(allGroupsAndArray));

    if (user != null && !user.isEmpty()) {
      final CriterionArray userAndArray = new CriterionArray();
      userAndArray.add(
          new Criterion().setField("users").setValue(user).setCondition(Condition.EQUAL));
      conjunctiveCriteria.add(new ConjunctiveCriterion().setAnd(userAndArray));
    }

    if (groups != null && !groups.isEmpty()) {
      final CriterionArray groupsAndArray = new CriterionArray();
      groupsAndArray.add(
          new Criterion()
              .setField("groups")
              .setValue("")
              .setValues(new StringArray(groups))
              .setCondition(Condition.EQUAL));
      conjunctiveCriteria.add(new ConjunctiveCriterion().setAnd(groupsAndArray));
    }

    if (roles != null && !roles.isEmpty()) {
      final CriterionArray rolesAndArray = new CriterionArray();
      rolesAndArray.add(
          new Criterion()
              .setField("roles")
              .setValue("")
              .setValues(new StringArray(roles))
              .setCondition(Condition.EQUAL));
      conjunctiveCriteria.add(new ConjunctiveCriterion().setAnd(rolesAndArray));
    }
    return new Filter().setOr(conjunctiveCriteria);
  }
}
