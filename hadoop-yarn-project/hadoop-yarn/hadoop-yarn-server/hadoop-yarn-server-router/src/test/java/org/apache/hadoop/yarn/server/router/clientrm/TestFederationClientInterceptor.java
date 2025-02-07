/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.yarn.server.router.clientrm;

import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.hadoop.test.LambdaTestUtils;
import org.apache.hadoop.yarn.MockApps;
import org.apache.hadoop.yarn.api.protocolrecords.GetApplicationAttemptReportRequest;
import org.apache.hadoop.yarn.api.protocolrecords.GetApplicationAttemptReportResponse;
import org.apache.hadoop.yarn.api.protocolrecords.GetApplicationReportRequest;
import org.apache.hadoop.yarn.api.protocolrecords.GetApplicationReportResponse;
import org.apache.hadoop.yarn.api.protocolrecords.GetApplicationsRequest;
import org.apache.hadoop.yarn.api.protocolrecords.GetApplicationsResponse;
import org.apache.hadoop.yarn.api.protocolrecords.GetClusterMetricsRequest;
import org.apache.hadoop.yarn.api.protocolrecords.GetClusterMetricsResponse;
import org.apache.hadoop.yarn.api.protocolrecords.GetNewApplicationRequest;
import org.apache.hadoop.yarn.api.protocolrecords.GetNewApplicationResponse;
import org.apache.hadoop.yarn.api.protocolrecords.KillApplicationRequest;
import org.apache.hadoop.yarn.api.protocolrecords.KillApplicationResponse;
import org.apache.hadoop.yarn.api.protocolrecords.SubmitApplicationRequest;
import org.apache.hadoop.yarn.api.protocolrecords.SubmitApplicationResponse;
import org.apache.hadoop.yarn.api.protocolrecords.GetClusterNodesResponse;
import org.apache.hadoop.yarn.api.protocolrecords.GetClusterNodesRequest;
import org.apache.hadoop.yarn.api.protocolrecords.GetNodesToLabelsResponse;
import org.apache.hadoop.yarn.api.protocolrecords.GetNodesToLabelsRequest;
import org.apache.hadoop.yarn.api.protocolrecords.GetLabelsToNodesResponse;
import org.apache.hadoop.yarn.api.protocolrecords.GetLabelsToNodesRequest;
import org.apache.hadoop.yarn.api.protocolrecords.GetClusterNodeLabelsResponse;
import org.apache.hadoop.yarn.api.protocolrecords.GetClusterNodeLabelsRequest;
import org.apache.hadoop.yarn.api.records.ApplicationAttemptId;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.api.records.ApplicationSubmissionContext;
import org.apache.hadoop.yarn.api.records.ContainerLaunchContext;
import org.apache.hadoop.yarn.api.records.Priority;
import org.apache.hadoop.yarn.api.records.YarnApplicationState;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.exceptions.YarnException;
import org.apache.hadoop.yarn.server.federation.policies.manager.UniformBroadcastPolicyManager;
import org.apache.hadoop.yarn.server.federation.store.impl.MemoryFederationStateStore;
import org.apache.hadoop.yarn.server.federation.store.records.SubClusterId;
import org.apache.hadoop.yarn.server.federation.utils.FederationStateStoreFacade;
import org.apache.hadoop.yarn.server.federation.utils.FederationStateStoreTestUtil;
import org.apache.hadoop.yarn.server.resourcemanager.ResourceManager;
import org.apache.hadoop.yarn.util.resource.Resources;
import org.junit.Assert;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Extends the {@code BaseRouterClientRMTest} and overrides methods in order to
 * use the {@code RouterClientRMService} pipeline test cases for testing the
 * {@code FederationInterceptor} class. The tests for
 * {@code RouterClientRMService} has been written cleverly so that it can be
 * reused to validate different request intercepter chains.
 */
public class TestFederationClientInterceptor extends BaseRouterClientRMTest {
  private static final Logger LOG =
      LoggerFactory.getLogger(TestFederationClientInterceptor.class);

  private TestableFederationClientInterceptor interceptor;
  private MemoryFederationStateStore stateStore;
  private FederationStateStoreTestUtil stateStoreUtil;
  private List<SubClusterId> subClusters;

  private String user = "test-user";

  private final static int NUM_SUBCLUSTER = 4;

  @Override
  public void setUp() {
    super.setUpConfig();
    interceptor = new TestableFederationClientInterceptor();

    stateStore = new MemoryFederationStateStore();
    stateStore.init(this.getConf());
    FederationStateStoreFacade.getInstance().reinitialize(stateStore,
        getConf());
    stateStoreUtil = new FederationStateStoreTestUtil(stateStore);

    interceptor.setConf(this.getConf());
    interceptor.init(user);

    subClusters = new ArrayList<SubClusterId>();

    try {
      for (int i = 0; i < NUM_SUBCLUSTER; i++) {
        SubClusterId sc = SubClusterId.newInstance(Integer.toString(i));
        stateStoreUtil.registerSubCluster(sc);
        subClusters.add(sc);
      }
    } catch (YarnException e) {
      LOG.error(e.getMessage());
      Assert.fail();
    }

  }

  @Override
  public void tearDown() {
    interceptor.shutdown();
    super.tearDown();
  }

  @Override
  protected YarnConfiguration createConfiguration() {
    YarnConfiguration conf = new YarnConfiguration();
    conf.setBoolean(YarnConfiguration.FEDERATION_ENABLED, true);
    String mockPassThroughInterceptorClass =
        PassThroughClientRequestInterceptor.class.getName();

    // Create a request intercepter pipeline for testing. The last one in the
    // chain is the federation intercepter that calls the mock resource manager.
    // The others in the chain will simply forward it to the next one in the
    // chain
    conf.set(YarnConfiguration.ROUTER_CLIENTRM_INTERCEPTOR_CLASS_PIPELINE,
        mockPassThroughInterceptorClass + "," + mockPassThroughInterceptorClass
            + "," + TestableFederationClientInterceptor.class.getName());

    conf.set(YarnConfiguration.FEDERATION_POLICY_MANAGER,
        UniformBroadcastPolicyManager.class.getName());

    // Disable StateStoreFacade cache
    conf.setInt(YarnConfiguration.FEDERATION_CACHE_TIME_TO_LIVE_SECS, 0);

    return conf;
  }

  /**
   * This test validates the correctness of GetNewApplication. The return
   * ApplicationId has to belong to one of the SubCluster in the cluster.
   */
  @Test
  public void testGetNewApplication()
      throws YarnException, IOException, InterruptedException {
    LOG.info("Test FederationClientInterceptor: Get New Application");

    GetNewApplicationRequest request = GetNewApplicationRequest.newInstance();
    GetNewApplicationResponse response = interceptor.getNewApplication(request);

    Assert.assertNotNull(response);
    Assert.assertNotNull(response.getApplicationId());
    Assert.assertTrue(response.getApplicationId()
        .getClusterTimestamp() == ResourceManager.getClusterTimeStamp());
  }

  /**
   * This test validates the correctness of SubmitApplication. The application
   * has to be submitted to one of the SubCluster in the cluster.
   */
  @Test
  public void testSubmitApplication()
      throws YarnException, IOException, InterruptedException {
    LOG.info("Test FederationClientInterceptor: Submit Application");

    ApplicationId appId = ApplicationId.newInstance(System.currentTimeMillis(),
        1);
    SubmitApplicationRequest request = mockSubmitApplicationRequest(appId);

    SubmitApplicationResponse response = interceptor.submitApplication(request);

    Assert.assertNotNull(response);
    SubClusterId scIdResult = stateStoreUtil.queryApplicationHomeSC(appId);
    Assert.assertNotNull(scIdResult);
    Assert.assertTrue(subClusters.contains(scIdResult));
  }

  private SubmitApplicationRequest mockSubmitApplicationRequest(
      ApplicationId appId) {
    ContainerLaunchContext amContainerSpec = mock(ContainerLaunchContext.class);
    ApplicationSubmissionContext context = ApplicationSubmissionContext
        .newInstance(appId, MockApps.newAppName(), "default",
            Priority.newInstance(0), amContainerSpec, false, false, -1,
            Resources.createResource(
                YarnConfiguration.DEFAULT_RM_SCHEDULER_MINIMUM_ALLOCATION_MB),
            "MockApp");
    SubmitApplicationRequest request = SubmitApplicationRequest
        .newInstance(context);
    return request;
  }

  /**
   * This test validates the correctness of SubmitApplication in case of
   * multiple submission. The first retry has to be submitted to the same
   * SubCluster of the first attempt.
   */
  @Test
  public void testSubmitApplicationMultipleSubmission()
      throws YarnException, IOException, InterruptedException {
    LOG.info(
        "Test FederationClientInterceptor: Submit Application - Multiple");

    ApplicationId appId =
        ApplicationId.newInstance(System.currentTimeMillis(), 1);
    SubmitApplicationRequest request = mockSubmitApplicationRequest(appId);

    // First attempt
    SubmitApplicationResponse response = interceptor.submitApplication(request);

    Assert.assertNotNull(response);
    SubClusterId scIdResult = stateStoreUtil.queryApplicationHomeSC(appId);
    Assert.assertNotNull(scIdResult);

    // First retry
    response = interceptor.submitApplication(request);

    Assert.assertNotNull(response);
    SubClusterId scIdResult2 = stateStoreUtil.queryApplicationHomeSC(appId);
    Assert.assertNotNull(scIdResult2);
    Assert.assertEquals(scIdResult, scIdResult);
  }

  /**
   * This test validates the correctness of SubmitApplication in case of empty
   * request.
   */
  @Test
  public void testSubmitApplicationEmptyRequest()
      throws YarnException, IOException, InterruptedException {
    LOG.info(
        "Test FederationClientInterceptor: Submit Application - Empty");
    try {
      interceptor.submitApplication(null);
      Assert.fail();
    } catch (YarnException e) {
      Assert.assertTrue(
          e.getMessage().startsWith("Missing submitApplication request or "
              + "applicationSubmissionContext information."));
    }
    try {
      interceptor.submitApplication(SubmitApplicationRequest.newInstance(null));
      Assert.fail();
    } catch (YarnException e) {
      Assert.assertTrue(
          e.getMessage().startsWith("Missing submitApplication request or "
              + "applicationSubmissionContext information."));
    }
    try {
      ApplicationSubmissionContext context = ApplicationSubmissionContext
          .newInstance(null, "", "", null, null, false, false, -1, null, null);
      SubmitApplicationRequest request =
          SubmitApplicationRequest.newInstance(context);
      interceptor.submitApplication(request);
      Assert.fail();
    } catch (YarnException e) {
      Assert.assertTrue(
          e.getMessage().startsWith("Missing submitApplication request or "
              + "applicationSubmissionContext information."));
    }
  }

  /**
   * This test validates the correctness of ForceKillApplication in case the
   * application exists in the cluster.
   */
  @Test
  public void testForceKillApplication()
      throws YarnException, IOException, InterruptedException {
    LOG.info("Test FederationClientInterceptor: Force Kill Application");

    ApplicationId appId =
        ApplicationId.newInstance(System.currentTimeMillis(), 1);
    SubmitApplicationRequest request = mockSubmitApplicationRequest(appId);

    // Submit the application we are going to kill later
    SubmitApplicationResponse response = interceptor.submitApplication(request);

    Assert.assertNotNull(response);
    Assert.assertNotNull(stateStoreUtil.queryApplicationHomeSC(appId));

    KillApplicationRequest requestKill =
        KillApplicationRequest.newInstance(appId);
    KillApplicationResponse responseKill =
        interceptor.forceKillApplication(requestKill);
    Assert.assertNotNull(responseKill);
  }

  /**
   * This test validates the correctness of ForceKillApplication in case of
   * application does not exist in StateStore.
   */
  @Test
  public void testForceKillApplicationNotExists()
      throws YarnException, IOException, InterruptedException {
    LOG.info("Test FederationClientInterceptor: "
        + "Force Kill Application - Not Exists");

    ApplicationId appId =
        ApplicationId.newInstance(System.currentTimeMillis(), 1);
    KillApplicationRequest requestKill =
        KillApplicationRequest.newInstance(appId);
    try {
      interceptor.forceKillApplication(requestKill);
      Assert.fail();
    } catch (YarnException e) {
      Assert.assertTrue(e.getMessage().equals(
          "Application " + appId + " does not exist in FederationStateStore"));
    }
  }

  /**
   * This test validates the correctness of ForceKillApplication in case of
   * empty request.
   */
  @Test
  public void testForceKillApplicationEmptyRequest()
      throws YarnException, IOException, InterruptedException {
    LOG.info(
        "Test FederationClientInterceptor: Force Kill Application - Empty");
    try {
      interceptor.forceKillApplication(null);
      Assert.fail();
    } catch (YarnException e) {
      Assert.assertTrue(e.getMessage().startsWith(
          "Missing forceKillApplication request or ApplicationId."));
    }
    try {
      interceptor
          .forceKillApplication(KillApplicationRequest.newInstance(null));
      Assert.fail();
    } catch (YarnException e) {
      Assert.assertTrue(e.getMessage().startsWith(
          "Missing forceKillApplication request or ApplicationId."));
    }
  }

  /**
   * This test validates the correctness of GetApplicationReport in case the
   * application exists in the cluster.
   */
  @Test
  public void testGetApplicationReport()
      throws YarnException, IOException, InterruptedException {
    LOG.info("Test FederationClientInterceptor: Get Application Report");

    ApplicationId appId =
        ApplicationId.newInstance(System.currentTimeMillis(), 1);
    SubmitApplicationRequest request = mockSubmitApplicationRequest(appId);

    // Submit the application we want the report later
    SubmitApplicationResponse response = interceptor.submitApplication(request);

    Assert.assertNotNull(response);
    Assert.assertNotNull(stateStoreUtil.queryApplicationHomeSC(appId));

    GetApplicationReportRequest requestGet =
        GetApplicationReportRequest.newInstance(appId);

    GetApplicationReportResponse responseGet =
        interceptor.getApplicationReport(requestGet);

    Assert.assertNotNull(responseGet);
  }

  /**
   * This test validates the correctness of GetApplicationReport in case the
   * application does not exist in StateStore.
   */
  @Test
  public void testGetApplicationNotExists()
      throws YarnException, IOException, InterruptedException {
    LOG.info(
        "Test ApplicationClientProtocol: Get Application Report - Not Exists");
    ApplicationId appId =
        ApplicationId.newInstance(System.currentTimeMillis(), 1);
    GetApplicationReportRequest requestGet =
        GetApplicationReportRequest.newInstance(appId);
    try {
      interceptor.getApplicationReport(requestGet);
      Assert.fail();
    } catch (YarnException e) {
      Assert.assertTrue(e.getMessage().equals(
          "Application " + appId + " does not exist in FederationStateStore"));
    }
  }

  /**
   * This test validates the correctness of GetApplicationReport in case of
   * empty request.
   */
  @Test
  public void testGetApplicationEmptyRequest()
      throws YarnException, IOException, InterruptedException {
    LOG.info(
        "Test FederationClientInterceptor: Get Application Report - Empty");
    try {
      interceptor.getApplicationReport(null);
      Assert.fail();
    } catch (YarnException e) {
      Assert.assertTrue(
          e.getMessage().startsWith("Missing getApplicationReport request or "
              + "applicationId information."));
    }
    try {
      interceptor
          .getApplicationReport(GetApplicationReportRequest.newInstance(null));
      Assert.fail();
    } catch (YarnException e) {
      Assert.assertTrue(
          e.getMessage().startsWith("Missing getApplicationReport request or "
              + "applicationId information."));
    }
  }

  /**
   * This test validates the correctness of
   * GetApplicationAttemptReport in case the
   * application exists in the cluster.
   */
  @Test
  public void testGetApplicationAttemptReport()
          throws YarnException, IOException, InterruptedException {
    LOG.info("Test FederationClientInterceptor: " +
            "Get ApplicationAttempt Report");

    ApplicationId appId =
            ApplicationId.newInstance(System.currentTimeMillis(), 1);
    ApplicationAttemptId appAttemptId =
            ApplicationAttemptId.newInstance(appId, 1);

    SubmitApplicationRequest request = mockSubmitApplicationRequest(appId);

    // Submit the application we want the applicationAttempt report later
    SubmitApplicationResponse response = interceptor.submitApplication(request);

    Assert.assertNotNull(response);
    Assert.assertNotNull(stateStoreUtil.queryApplicationHomeSC(appId));

    GetApplicationAttemptReportRequest requestGet =
            GetApplicationAttemptReportRequest.newInstance(appAttemptId);

    GetApplicationAttemptReportResponse responseGet =
            interceptor.getApplicationAttemptReport(requestGet);

    Assert.assertNotNull(responseGet);
  }

  /**
   * This test validates the correctness of
   * GetApplicationAttemptReport in case the
   * application does not exist in StateStore.
   */
  @Test
  public void testGetApplicationAttemptNotExists()
          throws Exception {
    LOG.info(
            "Test ApplicationClientProtocol: " +
                    "Get ApplicationAttempt Report - Not Exists");
    ApplicationId appId =
            ApplicationId.newInstance(System.currentTimeMillis(), 1);
    ApplicationAttemptId appAttemptID =
            ApplicationAttemptId.newInstance(appId, 1);
    GetApplicationAttemptReportRequest requestGet =
            GetApplicationAttemptReportRequest.newInstance(appAttemptID);

    LambdaTestUtils.intercept(YarnException.class, "ApplicationAttempt " +
            appAttemptID + "belongs to Application " +
            appId + " does not exist in FederationStateStore",
        () -> interceptor.getApplicationAttemptReport(requestGet));
  }

  /**
   * This test validates
   * the correctness of GetApplicationAttemptReport in case of
   * empty request.
   */
  @Test
  public void testGetApplicationAttemptEmptyRequest()
          throws Exception {
    LOG.info("Test FederationClientInterceptor: " +
                    "Get ApplicationAttempt Report - Empty");

    LambdaTestUtils.intercept(YarnException.class,
            "Missing getApplicationAttemptReport " +
                    "request or applicationId " +
                    "or applicationAttemptId information.",
        () -> interceptor.getApplicationAttemptReport(null));

    LambdaTestUtils.intercept(YarnException.class,
            "Missing getApplicationAttemptReport " +
                    "request or applicationId " +
                    "or applicationAttemptId information.",
        () -> interceptor
                    .getApplicationAttemptReport(
                            GetApplicationAttemptReportRequest
                                    .newInstance(null)));

    LambdaTestUtils.intercept(YarnException.class,
            "Missing getApplicationAttemptReport " +
                    "request or applicationId " +
                    "or applicationAttemptId information.",
        () -> interceptor
                    .getApplicationAttemptReport(
                            GetApplicationAttemptReportRequest.newInstance(
                                    ApplicationAttemptId
                                            .newInstance(null, 1)
                            )));
  }


  @Test
  public void testGetClusterMetricsRequest() throws YarnException, IOException {
    LOG.info("Test FederationClientInterceptor : Get Cluster Metrics request");
    // null request
    GetClusterMetricsResponse response = interceptor.getClusterMetrics(null);
    Assert.assertEquals(subClusters.size(),
        response.getClusterMetrics().getNumNodeManagers());
    // normal request.
    response =
        interceptor.getClusterMetrics(GetClusterMetricsRequest.newInstance());
    Assert.assertEquals(subClusters.size(),
        response.getClusterMetrics().getNumNodeManagers());

    ClientMethod remoteMethod = new ClientMethod("getClusterMetrics",
        new Class[] {GetClusterMetricsRequest.class},
        new Object[] {GetClusterMetricsRequest.newInstance()});
    Map<SubClusterId, GetClusterMetricsResponse> clusterMetrics =interceptor.
        invokeConcurrent(new ArrayList<>(), remoteMethod,
            GetClusterMetricsResponse.class);
    Assert.assertEquals(true, clusterMetrics.isEmpty());
  }

  /**
   * This test validates the correctness of
   * GetApplicationsResponse in case the
   * application exists in the cluster.
   */
  @Test
  public void testGetApplicationsResponse()
      throws YarnException, IOException, InterruptedException {
    LOG.info("Test FederationClientInterceptor: Get Applications Response");
    ApplicationId appId =
        ApplicationId.newInstance(System.currentTimeMillis(), 1);

    SubmitApplicationRequest request = mockSubmitApplicationRequest(appId);
    SubmitApplicationResponse response = interceptor.submitApplication(request);

    Assert.assertNotNull(response);
    Assert.assertNotNull(stateStoreUtil.queryApplicationHomeSC(appId));

    Set<String> appTypes = Collections.singleton("MockApp");
    GetApplicationsRequest requestGet =
        GetApplicationsRequest.newInstance(appTypes);

    GetApplicationsResponse responseGet =
        interceptor.getApplications(requestGet);

    Assert.assertNotNull(responseGet);
  }

  /**
   * This test validates
   * the correctness of GetApplicationsResponse in case of
   * empty request.
   */
  @Test
  public void testGetApplicationsNullRequest() throws Exception {
    LOG.info("Test FederationClientInterceptor: Get Applications request");
    LambdaTestUtils.intercept(YarnException.class,
        "Missing getApplications request.",
        () -> interceptor.getApplications(null));
  }

  /**
   * This test validates
   * the correctness of GetApplicationsResponse in case applications
   * with given type does not exist.
   */
  @Test
  public void testGetApplicationsApplicationTypeNotExists() throws Exception{
    LOG.info("Test FederationClientInterceptor: Application with type does "
        + "not exist");

    ApplicationId appId =
        ApplicationId.newInstance(System.currentTimeMillis(), 1);

    SubmitApplicationRequest request = mockSubmitApplicationRequest(appId);
    SubmitApplicationResponse response = interceptor.submitApplication(request);

    Assert.assertNotNull(response);
    Assert.assertNotNull(stateStoreUtil.queryApplicationHomeSC(appId));

    Set<String> appTypes = Collections.singleton("SPARK");

    GetApplicationsRequest requestGet =
        GetApplicationsRequest.newInstance(appTypes);

    GetApplicationsResponse responseGet =
        interceptor.getApplications(requestGet);

    Assert.assertNotNull(responseGet);
    Assert.assertTrue(responseGet.getApplicationList().isEmpty());
  }

  /**
   * This test validates
   * the correctness of GetApplicationsResponse in case applications
   * with given YarnApplicationState does not exist.
   */
  @Test
  public void testGetApplicationsApplicationStateNotExists() throws Exception{
    LOG.info("Test FederationClientInterceptor:" +
        " Application with state does not exist");

    ApplicationId appId =
        ApplicationId.newInstance(System.currentTimeMillis(), 1);

    SubmitApplicationRequest request = mockSubmitApplicationRequest(appId);
    SubmitApplicationResponse response = interceptor.submitApplication(request);

    Assert.assertNotNull(response);
    Assert.assertNotNull(stateStoreUtil.queryApplicationHomeSC(appId));

    EnumSet<YarnApplicationState> applicationStates = EnumSet.noneOf(
        YarnApplicationState.class);
    applicationStates.add(YarnApplicationState.KILLED);

    GetApplicationsRequest requestGet =
        GetApplicationsRequest.newInstance(applicationStates);

    GetApplicationsResponse responseGet =
        interceptor.getApplications(requestGet);

    Assert.assertNotNull(responseGet);
    Assert.assertTrue(responseGet.getApplicationList().isEmpty());
  }

  @Test
  public void testGetClusterNodesRequest() throws Exception {
    LOG.info("Test FederationClientInterceptor : Get Cluster Nodeds request");
    // null request
    LambdaTestUtils.intercept(YarnException.class, "Missing getClusterNodes request.",
        () -> interceptor.getClusterNodes(null));
    // normal request.
    GetClusterNodesResponse response =
        interceptor.getClusterNodes(GetClusterNodesRequest.newInstance());
    Assert.assertEquals(subClusters.size(), response.getNodeReports().size());
  }

  @Test
  public void testGetNodeToLabelsRequest() throws Exception {
    LOG.info("Test FederationClientInterceptor :  Get Node To Labels request");
    // null request
    LambdaTestUtils.intercept(YarnException.class, "Missing getNodesToLabels request.",
        () -> interceptor.getNodeToLabels(null));
    // normal request.
    GetNodesToLabelsResponse response =
        interceptor.getNodeToLabels(GetNodesToLabelsRequest.newInstance());
    Assert.assertEquals(0, response.getNodeToLabels().size());
  }

  @Test
  public void testGetLabelsToNodesRequest() throws Exception {
    LOG.info("Test FederationClientInterceptor :  Get Labels To Node request");
    // null request
    LambdaTestUtils.intercept(YarnException.class, "Missing getLabelsToNodes request.",
        () -> interceptor.getLabelsToNodes(null));
    // normal request.
    GetLabelsToNodesResponse response =
        interceptor.getLabelsToNodes(GetLabelsToNodesRequest.newInstance());
    Assert.assertEquals(0, response.getLabelsToNodes().size());
  }

  @Test
  public void testClusterNodeLabelsRequest() throws Exception {
    LOG.info("Test FederationClientInterceptor :  Get Cluster NodeLabels request");
    // null request
    LambdaTestUtils.intercept(YarnException.class, "Missing getClusterNodeLabels request.",
        () -> interceptor.getClusterNodeLabels(null));
    // normal request.
    GetClusterNodeLabelsResponse response =
        interceptor.getClusterNodeLabels(GetClusterNodeLabelsRequest.newInstance());
    Assert.assertEquals(0, response.getNodeLabelList().size());
  }
}
