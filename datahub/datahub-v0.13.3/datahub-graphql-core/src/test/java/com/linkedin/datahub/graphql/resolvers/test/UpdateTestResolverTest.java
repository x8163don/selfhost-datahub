package com.linkedin.datahub.graphql.resolvers.test;

import static com.linkedin.datahub.graphql.TestUtils.*;
import static org.mockito.ArgumentMatchers.any;
import static org.testng.Assert.*;

import com.linkedin.common.urn.UrnUtils;
import com.linkedin.datahub.graphql.QueryContext;
import com.linkedin.datahub.graphql.generated.TestDefinitionInput;
import com.linkedin.datahub.graphql.generated.UpdateTestInput;
import com.linkedin.entity.client.EntityClient;
import com.linkedin.events.metadata.ChangeType;
import com.linkedin.metadata.Constants;
import com.linkedin.metadata.utils.GenericRecordUtils;
import com.linkedin.mxe.MetadataChangeProposal;
import com.linkedin.r2.RemoteInvocationException;
import com.linkedin.test.TestDefinitionType;
import com.linkedin.test.TestInfo;
import graphql.schema.DataFetchingEnvironment;
import java.util.concurrent.CompletionException;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.testng.annotations.Test;

public class UpdateTestResolverTest {

  private static final String TEST_URN = "urn:li:test:test-id";
  private static final UpdateTestInput TEST_INPUT =
      new UpdateTestInput(
          "test-name", "test-category", "test-description", new TestDefinitionInput("{}"));

  @Test
  public void testGetSuccess() throws Exception {
    // Update resolver
    EntityClient mockClient = Mockito.mock(EntityClient.class);
    UpdateTestResolver resolver = new UpdateTestResolver(mockClient);

    // Execute resolver
    QueryContext mockContext = getMockAllowContext();
    DataFetchingEnvironment mockEnv = Mockito.mock(DataFetchingEnvironment.class);
    Mockito.when(mockEnv.getArgument(Mockito.eq("urn"))).thenReturn(TEST_URN);
    Mockito.when(mockEnv.getArgument(Mockito.eq("input"))).thenReturn(TEST_INPUT);
    Mockito.when(mockEnv.getContext()).thenReturn(mockContext);

    resolver.get(mockEnv).get();

    ArgumentCaptor<MetadataChangeProposal> proposalCaptor =
        ArgumentCaptor.forClass(MetadataChangeProposal.class);
    Mockito.verify(mockClient, Mockito.times(1))
        .ingestProposal(any(), proposalCaptor.capture(), Mockito.eq(false));
    MetadataChangeProposal resultProposal = proposalCaptor.getValue();
    assertEquals(resultProposal.getEntityType(), Constants.TEST_ENTITY_NAME);
    assertEquals(resultProposal.getAspectName(), Constants.TEST_INFO_ASPECT_NAME);
    assertEquals(resultProposal.getChangeType(), ChangeType.UPSERT);
    assertEquals(resultProposal.getEntityUrn(), UrnUtils.getUrn(TEST_URN));
    TestInfo resultInfo =
        GenericRecordUtils.deserializeAspect(
            resultProposal.getAspect().getValue(),
            resultProposal.getAspect().getContentType(),
            TestInfo.class);
    assertEquals(resultInfo.getName(), "test-name");
    assertEquals(resultInfo.getCategory(), "test-category");
    assertEquals(resultInfo.getDescription(), "test-description");
    assertEquals(resultInfo.getDefinition().getType(), TestDefinitionType.JSON);
    assertEquals(resultInfo.getDefinition().getJson(), "{}");
  }

  @Test
  public void testGetUnauthorized() throws Exception {
    // Update resolver
    EntityClient mockClient = Mockito.mock(EntityClient.class);
    UpdateTestResolver resolver = new UpdateTestResolver(mockClient);

    // Execute resolver
    DataFetchingEnvironment mockEnv = Mockito.mock(DataFetchingEnvironment.class);
    QueryContext mockContext = getMockDenyContext();
    Mockito.when(mockEnv.getArgument(Mockito.eq("urn"))).thenReturn(TEST_URN);
    Mockito.when(mockEnv.getArgument(Mockito.eq("input"))).thenReturn(TEST_INPUT);
    Mockito.when(mockEnv.getContext()).thenReturn(mockContext);

    assertThrows(CompletionException.class, () -> resolver.get(mockEnv).join());
    Mockito.verify(mockClient, Mockito.times(0)).ingestProposal(any(), Mockito.any());
  }

  @Test
  public void testGetEntityClientException() throws Exception {
    // Update resolver
    EntityClient mockClient = Mockito.mock(EntityClient.class);
    Mockito.doThrow(RemoteInvocationException.class)
        .when(mockClient)
        .ingestProposal(any(), Mockito.any());
    UpdateTestResolver resolver = new UpdateTestResolver(mockClient);

    // Execute resolver
    DataFetchingEnvironment mockEnv = Mockito.mock(DataFetchingEnvironment.class);
    QueryContext mockContext = getMockAllowContext();
    Mockito.when(mockEnv.getArgument(Mockito.eq("input"))).thenReturn(TEST_INPUT);
    Mockito.when(mockEnv.getContext()).thenReturn(mockContext);

    assertThrows(CompletionException.class, () -> resolver.get(mockEnv).join());
  }
}
