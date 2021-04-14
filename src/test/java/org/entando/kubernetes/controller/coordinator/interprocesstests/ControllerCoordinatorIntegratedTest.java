/*
 *
 * Copyright 2015-Present Entando Inc. (http://www.entando.com) All rights reserved.
 *
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 2.1 of the License, or (at your option)
 * any later version.
 *
 *  This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 *
 */

package org.entando.kubernetes.controller.coordinator.interprocesstests;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.NamespacedKubernetesClient;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.Watcher.Action;
import io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.quarkus.runtime.StartupEvent;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.entando.kubernetes.client.DefaultIngressClient;
import org.entando.kubernetes.client.EntandoOperatorTestConfig;
import org.entando.kubernetes.client.EntandoOperatorTestConfig.TestTarget;
import org.entando.kubernetes.client.integrationtesthelpers.FluentIntegrationTesting;
import org.entando.kubernetes.client.integrationtesthelpers.HttpTestHelper;
import org.entando.kubernetes.client.integrationtesthelpers.TestFixturePreparation;
import org.entando.kubernetes.client.integrationtesthelpers.TestFixtureRequest;
import org.entando.kubernetes.controller.coordinator.EntandoControllerCoordinator;
import org.entando.kubernetes.controller.coordinator.ImageVersionPreparation;
import org.entando.kubernetes.controller.spi.common.DbmsDockerVendorStrategy;
import org.entando.kubernetes.controller.spi.common.EntandoOperatorComplianceMode;
import org.entando.kubernetes.controller.spi.common.EntandoOperatorConfigBase;
import org.entando.kubernetes.controller.spi.common.EntandoOperatorSpiConfig;
import org.entando.kubernetes.controller.spi.common.NameUtils;
import org.entando.kubernetes.controller.spi.common.PodResult;
import org.entando.kubernetes.controller.spi.common.PodResult.State;
import org.entando.kubernetes.controller.spi.common.SecretUtils;
import org.entando.kubernetes.controller.spi.container.KeycloakName;
import org.entando.kubernetes.controller.support.common.EntandoOperatorConfigProperty;
import org.entando.kubernetes.controller.support.common.KubeUtils;
import org.entando.kubernetes.controller.support.common.TlsHelper;
import org.entando.kubernetes.controller.support.creators.IngressCreator;
import org.entando.kubernetes.model.DbmsVendor;
import org.entando.kubernetes.model.EntandoDeploymentPhase;
import org.entando.kubernetes.model.compositeapp.DoneableEntandoCompositeApp;
import org.entando.kubernetes.model.compositeapp.EntandoCompositeApp;
import org.entando.kubernetes.model.compositeapp.EntandoCompositeAppBuilder;
import org.entando.kubernetes.model.compositeapp.EntandoCompositeAppOperationFactory;
import org.entando.kubernetes.model.externaldatabase.EntandoDatabaseService;
import org.entando.kubernetes.model.keycloakserver.DoneableEntandoKeycloakServer;
import org.entando.kubernetes.model.keycloakserver.EntandoKeycloakServer;
import org.entando.kubernetes.model.keycloakserver.EntandoKeycloakServerBuilder;
import org.entando.kubernetes.model.keycloakserver.EntandoKeycloakServerOperationFactory;
import org.entando.kubernetes.model.keycloakserver.StandardKeycloakImage;
import org.entando.kubernetes.model.plugin.DoneableEntandoPlugin;
import org.entando.kubernetes.model.plugin.EntandoPlugin;
import org.entando.kubernetes.model.plugin.EntandoPluginOperationFactory;
import org.entando.kubernetes.model.plugin.PluginSecurityLevel;
import org.entando.kubernetes.test.common.FluentTraversals;
import org.entando.kubernetes.test.common.VariableReferenceAssertions;
import org.entando.kubernetes.test.e2etest.helpers.K8SIntegrationTestHelper;
import org.entando.kubernetes.test.e2etest.podwaiters.JobPodWaiter;
import org.entando.kubernetes.test.e2etest.podwaiters.ServicePodWaiter;
import org.hamcrest.core.Is;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Tags;
import org.junit.jupiter.api.Test;
import org.junit.platform.commons.util.StringUtils;

@Tags({@Tag("end-to-end"), @Tag("inter-process"), @Tag("smoke-test"), @Tag("post-deployment")})
class ControllerCoordinatorIntegratedTest implements FluentIntegrationTesting, FluentTraversals,
        VariableReferenceAssertions {

    public static final String NAMESPACE = EntandoOperatorTestConfig.calculateNameSpace("coordinator-test");
    public static final String PLUGIN_NAME = EntandoOperatorTestConfig.calculateName("test-plugin");
    public static final String KEYCLOAK_NAME = EntandoOperatorTestConfig.calculateName("test-keycloak");
    public static final String MY_APP = EntandoOperatorTestConfig.calculateName("my-app");
    private static NamespacedKubernetesClient client;
    private static K8SIntegrationTestHelper helper = new K8SIntegrationTestHelper();

    static {
        /* 
         NB!!! this part is a bit convoluted. The ENTANDO_CA_SECRET_NAME JVM property could potentially be set in the
         constructor K8SIntegrationTestHelper. The init statement TestFixturePreparation.newClient() actually indirectly
         calls CertificateSecretHelper.buildCertificateSecretsFromDirectory() but only if certs could be picked up
         in the source folder src/test/resources/${domain}/, and then it sets the The ENTANDO_CA_SECRET_NAME JVM
         property.

         If it is set, the secret will be created in the TestFixturePreparation#ENTANDO_CONTROLLERS_NAMESPACE.
         Just to make sure, the preview Helm template of this project also declares the relevant secrets:
         test-ca-secret: charts/preview/templates/ca-secret.yaml
         test-tls-secret: charts/preview/templates/tls-secret.yaml

         So if all the planets are aligned and the secret has been created, the following code attempts to initialize the
         current JVM's trust store, but it is essential that it is called before any HTTPS calls are made
         or the new trust store could  initialized without this CA cert.
         */
        if (StringUtils.isNotBlank(System.getProperty(EntandoOperatorConfigProperty.ENTANDO_CA_SECRET_NAME.getJvmSystemProperty()))) {
            final Secret secret = getClient().secrets()
                    .withName(System.getProperty(EntandoOperatorConfigProperty.ENTANDO_CA_SECRET_NAME.getJvmSystemProperty())).get();
            if (secret != null) {
                TlsHelper.trustCertificateAuthoritiesIn(secret);
            }
        }
    }

    private String domainSuffix;

    private static NamespacedKubernetesClient getClient() {
        if (client == null) {
            client = helper.getClient().inNamespace(NAMESPACE);
        }
        return client;
    }

    @AfterEach
    void clearSystemProperty() {
        System.clearProperty(EntandoOperatorConfigProperty.ENTANDO_K8S_OPERATOR_COMPLIANCE_MODE.getJvmSystemProperty());
    }

    @BeforeAll
    public static void prepareCoordinator() {
        NamespacedKubernetesClient client = getClient();
        clearNamespace(client);
        if (EntandoOperatorTestConfig.getTestTarget() == TestTarget.STANDALONE) {
            new EntandoControllerCoordinator(client).onStartup(new StartupEvent());
        } else {
            //Should be installed by helm chart in pipeline. Sometimes pulling the image takes long.
            Awaitility.await().ignoreExceptions().atMost(10, TimeUnit.MINUTES).until(() ->
                    getClient().pods().inNamespace(NAMESPACE).list().getItems().size() > 0
                            && PodResult.of(getClient().pods().inNamespace(NAMESPACE).list().getItems().get(0)).getState()
                            == State.READY);
        }
    }

    @Test
    void testExecuteKeycloakControllerPod() {
        //Given I have a clean namespace
        KubernetesClient client = getClient();
        clearNamespace(client);
        //and the Coordinator observes this namespace

        System.setProperty(EntandoOperatorConfigProperty.ENTANDO_NAMESPACES_TO_OBSERVE.getJvmSystemProperty(),
                client.getNamespace());
        //And I have a config map with the Entando KeycloakController's image information
        final String versionToExpect = ensureKeycloakControllerVersion();
        //When I create a new EntandoKeycloakServer resource
        EntandoKeycloakServer keycloakServer = new EntandoKeycloakServerBuilder()
                .withNewMetadata().withName(KEYCLOAK_NAME).withNamespace(client.getNamespace()).endMetadata()
                .withNewSpec()
                .withDbms(DbmsVendor.POSTGRESQL)
                .withIngressHostName(KEYCLOAK_NAME + "." + getDomainSuffix())
                .endSpec()
                .build();
        EntandoKeycloakServerOperationFactory.produceAllEntandoKeycloakServers(client)
                .inNamespace(client.getNamespace()).create(keycloakServer);

        //Then I expect to see at least one controller pod
        FilterWatchListDeletable<Pod, PodList, Boolean, Watch, Watcher<Pod>> listable = client.pods()
                .inNamespace(client.getNamespace())
                .withLabel(KubeUtils.ENTANDO_RESOURCE_KIND_LABEL_NAME, "EntandoKeycloakServer");
        await().ignoreExceptions().atMost(30, TimeUnit.SECONDS).until(() -> listable.list().getItems().size() > 0);
        Pod theControllerPod = listable.list().getItems().get(0);
        assertThat(theVariableNamed("ENTANDO_RESOURCE_ACTION").on(thePrimaryContainerOn(theControllerPod)), is(Action.ADDED.name()));
        assertThat(theVariableNamed("ENTANDO_RESOURCE_NAME").on(thePrimaryContainerOn(theControllerPod)),
                is(keycloakServer.getMetadata().getName()));
        assertThat(theVariableNamed("ENTANDO_RESOURCE_NAMESPACE").on(thePrimaryContainerOn(theControllerPod)),
                is(keycloakServer.getMetadata().getNamespace()));
        //With the correct version specified
        if (EntandoOperatorConfigBase.lookupProperty("RELATED_IMAGE_ENTANDO_K8S_KEYCLOAK_CONTROLLER").isPresent()) {
            assertThat(thePrimaryContainerOn(theControllerPod).getImage(), containsString("@sha256:"));
        } else {
            assertTrue(thePrimaryContainerOn(theControllerPod).getImage().endsWith(versionToExpect));
        }
        //and the database containers have been created
        helper.keycloak().waitForServicePod(new ServicePodWaiter().limitReadinessTo(Duration.ofSeconds(180)), keycloakServer
                .getMetadata().getNamespace(), keycloakServer.getMetadata().getName() + "-db");
        //and the database preparation has completed
        helper.keycloak().waitForDbJobPod(new JobPodWaiter().limitCompletionTo(Duration.ofSeconds(90)), keycloakServer, "server");
        //and the Keycloak server container has been deployed
        helper.keycloak().waitForServicePod((new ServicePodWaiter()).limitReadinessTo(Duration.ofSeconds(300)),
                keycloakServer.getMetadata().getNamespace(), keycloakServer.getMetadata().getName() + "-server");
        verifyKeycloakDatabaseDeployment(keycloakServer, EntandoOperatorSpiConfig.getComplianceMode());
        StandardKeycloakImage standardServerImage;
        if (EntandoOperatorSpiConfig.getComplianceMode() == EntandoOperatorComplianceMode.COMMUNITY) {
            standardServerImage = StandardKeycloakImage.KEYCLOAK;
        } else {
            standardServerImage = StandardKeycloakImage.REDHAT_SSO;
        }
        verifyKeycloakDeployment(keycloakServer, standardServerImage);
    }

    private void verifyKeycloakDatabaseDeployment(EntandoKeycloakServer keycloakServer, EntandoOperatorComplianceMode complianceMode) {
        Deployment deployment = client.apps().deployments()
                .inNamespace(keycloakServer.getMetadata().getNamespace())
                .withName(keycloakServer.getMetadata().getName() + "-db-deployment")
                .get();
        assertThat(thePortNamed(DB_PORT).on(theContainerNamed("db-container").on(deployment))
                .getContainerPort(), equalTo(5432));
        DbmsDockerVendorStrategy dockerVendorStrategy = null;
        if (complianceMode == EntandoOperatorComplianceMode.COMMUNITY) {
            dockerVendorStrategy = DbmsDockerVendorStrategy.CENTOS_POSTGRESQL;
        } else {
            dockerVendorStrategy = DbmsDockerVendorStrategy.RHEL_POSTGRESQL;
        }
        assertThat(theContainerNamed("db-container").on(deployment).getImage(), containsString(dockerVendorStrategy.getRegistry()));
        assertThat(theContainerNamed("db-container").on(deployment).getImage(), containsString(dockerVendorStrategy.getImageRepository()));
        assertThat(theContainerNamed("db-container").on(deployment).getImage(), containsString(dockerVendorStrategy.getOrganization()));
        Service service = client.services().inNamespace(keycloakServer.getMetadata().getNamespace()).withName(
                keycloakServer.getMetadata().getName() + "-db-service").get();
        assertThat(thePortNamed(DB_PORT).on(service).getPort(), equalTo(5432));
        assertThat(deployment.getStatus().getReadyReplicas(), greaterThanOrEqualTo(1));
        assertThat("It has a db status", helper.keycloak().getOperations()
                .inNamespace(keycloakServer.getMetadata().getNamespace()).withName(keycloakServer.getMetadata().getName())
                .fromServer().get().getStatus().forDbQualifiedBy("db").isPresent());
    }

    protected static void clearNamespace(KubernetesClient client) {
        TestFixturePreparation.prepareTestFixture(client,
                new TestFixtureRequest().deleteAll(EntandoCompositeApp.class).fromNamespace(NAMESPACE)
                        .deleteAll(EntandoDatabaseService.class).fromNamespace(NAMESPACE)
                        .deleteAll(EntandoPlugin.class).fromNamespace(NAMESPACE)
                        .deleteAll(EntandoKeycloakServer.class).fromNamespace(NAMESPACE));
    }

    /**
     * Adding this test as a kind of e2e test to ensure state gets propagate correctly all the way through th container hierarchy.
     */
    //    @Test
    void testExecuteCompositeAppControllerPod() {
        //Given I have a clean namespace
        KubernetesClient client = getClient();
        clearNamespace(client);
        //and the Coordinator observes this namespace
        System.setProperty(EntandoOperatorConfigProperty.ENTANDO_NAMESPACES_TO_OBSERVE.getJvmSystemProperty(),
                client.getNamespace());
        //And I have a config map with the Entando KeycloakController's image information
        final String keycloakControllerVersionToExpect = ensureKeycloakControllerVersion();
        final String pluginControllerVersionToExpect = ensurePluginControllerVersion();
        final String compositeAppControllerVersionToExpect = ensureCompositeAppControllerVersion();
        //When I create a new EntandoCompositeApp with an EntandoKeycloakServer and EntandoPlugin component

        EntandoCompositeApp appToCreate = new EntandoCompositeAppBuilder()
                .withNewMetadata().withName(MY_APP).withNamespace(client.getNamespace()).endMetadata()
                .withNewSpec()
                .addNewEntandoKeycloakServer()
                .withNewMetadata().withName(KEYCLOAK_NAME).withNamespace(client.getNamespace()).endMetadata()
                .withNewSpec()
                .withDefault(true)
                .withDbms(DbmsVendor.NONE)
                .withIngressHostName(KEYCLOAK_NAME + "." + getDomainSuffix())
                .endSpec()
                .endEntandoKeycloakServer()
                .addNewEntandoPlugin()
                .withNewMetadata().withName(PLUGIN_NAME).endMetadata().withNewSpec()
                .withImage("entando/entando-avatar-plugin")
                .withDbms(DbmsVendor.POSTGRESQL)
                .withReplicas(1)
                .withIngressHostName(PLUGIN_NAME + "." + getDomainSuffix())
                .withHealthCheckPath("/management/health")
                .withIngressPath("/avatarPlugin")
                .withSecurityLevel(PluginSecurityLevel.STRICT)
                .endSpec()
                .endEntandoPlugin()
                .endSpec()
                .build();
        EntandoCompositeApp app = EntandoCompositeAppOperationFactory.produceAllEntandoCompositeApps(getClient())
                .inNamespace(NAMESPACE)
                .create(appToCreate);
        //Then I expect to see the keycloak controller pod
        FilterWatchListDeletable<Pod, PodList, Boolean, Watch, Watcher<Pod>> keycloakControllerList = client.pods()
                .inNamespace(client.getNamespace())
                .withLabel(KubeUtils.ENTANDO_RESOURCE_KIND_LABEL_NAME, "EntandoKeycloakServer")
                .withLabel("EntandoKeycloakServer", app.getSpec().getComponents().get(0).getMetadata().getName());
        await().ignoreExceptions().atMost(60, TimeUnit.SECONDS).until(() -> keycloakControllerList.list().getItems().size() > 0);
        Pod theKeycloakControllerPod = keycloakControllerList.list().getItems().get(0);
        //and the EntandoKeycloakServer resource has been saved to K8S under the EntandoCompositeApp
        Resource<EntandoKeycloakServer, DoneableEntandoKeycloakServer> keycloakGettable = EntandoKeycloakServerOperationFactory
                .produceAllEntandoKeycloakServers(getClient()).inNamespace(NAMESPACE).withName(KEYCLOAK_NAME);
        await().ignoreExceptions().atMost(15, TimeUnit.SECONDS).until(
                () -> keycloakGettable.get().getMetadata().getOwnerReferences().get(0).getUid().equals(app.getMetadata().getUid())
        );
        //and the EntandoKeycloakServer resource's identifying information has been passed to the controller Pod
        assertThat(theVariableNamed("ENTANDO_RESOURCE_ACTION").on(thePrimaryContainerOn(theKeycloakControllerPod)),
                is(Action.ADDED.name()));
        assertThat(theVariableNamed("ENTANDO_RESOURCE_NAME").on(thePrimaryContainerOn(theKeycloakControllerPod)),
                is(app.getSpec().getComponents().get(0).getMetadata().getName()));
        assertThat(theVariableNamed("ENTANDO_RESOURCE_NAMESPACE").on(thePrimaryContainerOn(theKeycloakControllerPod)),
                is(app.getMetadata().getNamespace()));
        //With the correct version of the controller image specified
        if (EntandoOperatorConfigBase.lookupProperty("RELATED_IMAGE_ENTANDO_K8S_KEYCLOAK_CONTROLLER").isPresent()) {
            assertThat(thePrimaryContainerOn(theKeycloakControllerPod).getImage(), containsString("@sha256:"));
        } else {
            assertTrue(thePrimaryContainerOn(theKeycloakControllerPod).getImage().endsWith(keycloakControllerVersionToExpect));
        }
        //And its status reflecting on the EntandoCompositeApp
        Resource<EntandoCompositeApp, DoneableEntandoCompositeApp> appGettable =
                EntandoCompositeAppOperationFactory
                        .produceAllEntandoCompositeApps(client)
                        .inNamespace(client.getNamespace()).withName(MY_APP);
        await().ignoreExceptions().atMost(240, TimeUnit.SECONDS).until(
                () -> appGettable.fromServer().get().getStatus().forServerQualifiedBy(KEYCLOAK_NAME).get().getPodStatus() != null
        );
        //And the plugin controller pod
        FilterWatchListDeletable<Pod, PodList, Boolean, Watch, Watcher<Pod>> pluginControllerList = client.pods()
                .inNamespace(client.getNamespace())
                .withLabel(KubeUtils.ENTANDO_RESOURCE_KIND_LABEL_NAME, "EntandoPlugin")
                .withLabel("EntandoPlugin", app.getSpec().getComponents().get(1).getMetadata().getName());
        await().ignoreExceptions().atMost(60, TimeUnit.SECONDS).until(() -> pluginControllerList.list().getItems().size() > 0);
        Pod thePluginControllerPod = pluginControllerList.list().getItems().get(0);
        //and the EntandoKeycloakServer resource has been saved to K8S under the EntandoCompositeApp
        Resource<EntandoPlugin, DoneableEntandoPlugin> pluginGettable = EntandoPluginOperationFactory
                .produceAllEntandoPlugins(getClient()).inNamespace(NAMESPACE).withName(PLUGIN_NAME);
        await().ignoreExceptions().atMost(15, TimeUnit.SECONDS).until(
                () -> pluginGettable.get().getMetadata().getOwnerReferences().get(0).getUid(), is(app.getMetadata().getUid())
        );
        //and the EntandoKeycloakServer resource's identifying information has been passed to the controller Pod
        assertThat(theVariableNamed("ENTANDO_RESOURCE_ACTION").on(thePrimaryContainerOn(thePluginControllerPod)), is(Action.ADDED.name()));
        assertThat(theVariableNamed("ENTANDO_RESOURCE_NAME").on(thePrimaryContainerOn(thePluginControllerPod)),
                is(app.getSpec().getComponents().get(1).getMetadata().getName()));
        assertThat(theVariableNamed("ENTANDO_RESOURCE_NAMESPACE").on(thePrimaryContainerOn(thePluginControllerPod)),
                is(app.getMetadata().getNamespace()));
        //With the correct version specified
        if (EntandoOperatorConfigBase.lookupProperty("RELATED_IMAGE_ENTANDO_K8S_PLUGIN_CONTROLLER").isPresent()) {
            assertThat(thePrimaryContainerOn(thePluginControllerPod).getImage(), containsString("@sha256:"));
        } else {
            assertTrue(thePrimaryContainerOn(thePluginControllerPod).getImage().endsWith(pluginControllerVersionToExpect));
        }
        //And its status reflecting on the EntandoCompositeApp
        await().ignoreExceptions().atMost(240, TimeUnit.SECONDS).until(
                () -> appGettable.fromServer().get().getStatus().forServerQualifiedBy(PLUGIN_NAME).get().getPodStatus() != null
        );
        //And the EntandoCompositeApp is in a finished state
        await().ignoreExceptions().atMost(30, TimeUnit.SECONDS).until(() -> hasFinished(appGettable));
    }

    protected void verifyKeycloakDeployment(EntandoKeycloakServer entandoKeycloakServer, StandardKeycloakImage standardKeycloakImage) {
        await().atMost(60, SECONDS).ignoreExceptions().until(() -> helper.keycloak().getOperations()
                .inNamespace(entandoKeycloakServer.getMetadata().getNamespace())
                .withName(entandoKeycloakServer.getMetadata().getName())
                .fromServer()
                .get()
                .getStatus()
                .getEntandoDeploymentPhase() == EntandoDeploymentPhase.SUCCESSFUL
        );
        Deployment deployment = client.apps().deployments().inNamespace(entandoKeycloakServer.getMetadata().getNamespace())
                .withName(entandoKeycloakServer.getMetadata().getName() + "-server-deployment").get();
        assertThat(thePortNamed("server-port")
                .on(theContainerNamed("server-container").on(deployment))
                .getContainerPort(), is(8080));
        assertThat(theContainerNamed("server-container").on(deployment).getImage(),
                containsString(standardKeycloakImage.name().toLowerCase().replace("_", "-")));
        Service service = client.services().inNamespace(entandoKeycloakServer.getMetadata().getNamespace()).withName(
                entandoKeycloakServer.getMetadata().getName() + "-server-service").get();
        assertThat(thePortNamed("server-port").on(service).getPort(), Is.is(8080));
        assertTrue(deployment.getStatus().getReadyReplicas() >= 1);
        assertTrue(helper.keycloak().getOperations()
                .inNamespace(entandoKeycloakServer.getMetadata().getNamespace()).withName(entandoKeycloakServer.getMetadata().getName())
                .fromServer().get().getStatus().forServerQualifiedBy("server").isPresent());
        System.out.println("#####Printing TLS properties");
        System.out.println(System.getProperty(EntandoOperatorConfigProperty.ENTANDO_CA_SECRET_NAME.getJvmSystemProperty()));
        System.out.println(System.getProperty(EntandoOperatorConfigProperty.ENTANDO_TLS_SECRET_NAME.getJvmSystemProperty()));
        System.out.println(System.getProperty(EntandoOperatorConfigProperty.ENTANDO_USE_AUTO_CERT_GENERATION.getJvmSystemProperty()));
        System.out.println("#####Printing TLS env vars");
        System.out.println(System.getenv(EntandoOperatorConfigProperty.ENTANDO_CA_SECRET_NAME.name()));
        System.out.println(System.getenv(EntandoOperatorConfigProperty.ENTANDO_TLS_SECRET_NAME.name()));
        System.out.println(System.getenv(EntandoOperatorConfigProperty.ENTANDO_USE_AUTO_CERT_GENERATION.name()));
        System.out.println("#####Printing resolved TLS props");
        System.out.println(EntandoOperatorConfigBase.lookupProperty(EntandoOperatorConfigProperty.ENTANDO_CA_SECRET_NAME));
        System.out.println(EntandoOperatorConfigBase.lookupProperty(EntandoOperatorConfigProperty.ENTANDO_TLS_SECRET_NAME));
        System.out.println(EntandoOperatorConfigBase.lookupProperty(EntandoOperatorConfigProperty.ENTANDO_USE_AUTO_CERT_GENERATION));
        System.out.println("#####Printing default protocol");
        System.out.println(HttpTestHelper.getDefaultProtocol());

        String http = HttpTestHelper.getDefaultProtocol();
        System.out.println("#####Printing KC url");
        System.out.println(http + "://" + entandoKeycloakServer.getMetadata().getName() + "." + helper.getDomainSuffix() + "/auth");
        await().atMost(30, TimeUnit.SECONDS).pollInterval(1, TimeUnit.SECONDS).ignoreExceptions().until(() -> HttpTestHelper
                .statusOk(http + "://" + entandoKeycloakServer.getMetadata().getName() + "." + helper.getDomainSuffix() + "/auth"));

        Secret adminSecret = client.secrets()
                .inNamespace(client.getNamespace())
                .withName(KeycloakName.forTheAdminSecret(entandoKeycloakServer))
                .get();
        assertNotNull(adminSecret);
        assertTrue(adminSecret.getData().containsKey(SecretUtils.USERNAME_KEY));
        assertTrue(adminSecret.getData().containsKey(SecretUtils.PASSSWORD_KEY));
        ConfigMap configMap = client.configMaps()
                .inNamespace(client.getNamespace())
                .withName(KeycloakName.forTheConnectionConfigMap(entandoKeycloakServer))
                .get();
        assertNotNull(configMap);
        assertTrue(configMap.getData().containsKey(NameUtils.URL_KEY));
    }

    private String ensureCompositeAppControllerVersion() {
        ImageVersionPreparation imageVersionPreparation = new ImageVersionPreparation(getClient());
        return imageVersionPreparation.ensureImageVersion("entando-k8s-composite-app-controller", "6.0.12");
    }

    private String getDomainSuffix() {
        if (domainSuffix == null) {
            domainSuffix = IngressCreator.determineRoutingSuffix(DefaultIngressClient.resolveMasterHostname(getClient()));
        }
        return domainSuffix;
    }

    protected String ensurePluginControllerVersion() {
        ImageVersionPreparation imageVersionPreparation = new ImageVersionPreparation(getClient());
        return imageVersionPreparation.ensureImageVersion("entando-k8s-plugin-controller", "6.0.2");
    }

    private boolean hasFinished(Resource<EntandoCompositeApp, DoneableEntandoCompositeApp> appGettable) {
        EntandoDeploymentPhase phase = appGettable.fromServer().get().getStatus().getEntandoDeploymentPhase();
        return phase == EntandoDeploymentPhase.SUCCESSFUL || phase == EntandoDeploymentPhase.FAILED;
    }

    protected String ensureKeycloakControllerVersion() {
        return new ImageVersionPreparation(getClient()).ensureImageVersion("entando-k8s-keycloak-controller", "6.0.1");
    }
}
