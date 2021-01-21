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

package org.entando.kubernetes.controller.coordinator.inprocesstests;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.Watcher.Action;
import io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable;
import io.fabric8.kubernetes.client.dsl.internal.CustomResourceOperationsImpl;
import io.fabric8.kubernetes.client.server.mock.KubernetesServer;
import io.quarkus.runtime.StartupEvent;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.commons.lang3.RandomStringUtils;
import org.awaitility.core.ConditionTimeoutException;
import org.entando.kubernetes.controller.EntandoOperatorConfig;
import org.entando.kubernetes.controller.EntandoOperatorConfigProperty;
import org.entando.kubernetes.controller.KubeUtils;
import org.entando.kubernetes.controller.common.OperatorProcessingInstruction;
import org.entando.kubernetes.controller.coordinator.EntandoControllerCoordinator;
import org.entando.kubernetes.controller.integrationtest.support.EntandoOperatorTestConfig;
import org.entando.kubernetes.controller.integrationtest.support.FluentIntegrationTesting;
import org.entando.kubernetes.controller.integrationtest.support.TestFixturePreparation;
import org.entando.kubernetes.model.DbmsVendor;
import org.entando.kubernetes.model.EntandoDeploymentPhase;
import org.entando.kubernetes.model.compositeapp.EntandoCompositeApp;
import org.entando.kubernetes.model.compositeapp.EntandoCompositeAppOperationFactory;
import org.entando.kubernetes.model.keycloakserver.DoneableEntandoKeycloakServer;
import org.entando.kubernetes.model.keycloakserver.EntandoKeycloakServer;
import org.entando.kubernetes.model.keycloakserver.EntandoKeycloakServerBuilder;
import org.entando.kubernetes.model.keycloakserver.EntandoKeycloakServerList;
import org.entando.kubernetes.model.keycloakserver.EntandoKeycloakServerOperationFactory;
import org.junit.Rule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Tags;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.migrationsupport.rules.EnableRuleMigrationSupport;

@Tags({@Tag("in-process"), @Tag("component"), @Tag("pre-deployment")})
@EnableRuleMigrationSupport
//because Awaitility knows which invocation throws the exception
@SuppressWarnings("java:S5778")
class ControllerCoordinatorEdgeConditionsTest implements FluentIntegrationTesting {

    public static final String NAMESPACE = EntandoOperatorTestConfig.calculateNameSpace("coordinator-test");

    @Rule
    public KubernetesServer server = new KubernetesServer(false, true);
    private EntandoControllerCoordinator coordinator;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    @AfterEach
    void shutdownSchedulers() {
        scheduler.shutdownNow();
    }

    @Test
    void testExistingResourcesProcessed() {
        //Given I have a clean namespace
        TestFixturePreparation.prepareTestFixture(getClient(), deleteAll(EntandoKeycloakServer.class).fromNamespace(NAMESPACE));
        //and the Coordinator observes this namespace
        System.setProperty(EntandoOperatorConfigProperty.ENTANDO_NAMESPACES_TO_OBSERVE.getJvmSystemProperty(),
                getClient().getNamespace());
        //And I have a config map with the Entando KeycloakController's image information
        ensureImageVersionsConfigMap(getClient());

        //And I have created an EntandoKeycloakServer resource
        EntandoKeycloakServer entandoKeycloakServer = createEntandoKeycloakServer("2", 1L);
        //And no Pods have been created for it
        FilterWatchListDeletable<Pod, PodList, Boolean, Watch, Watcher<Pod>> podList = getClient().pods()
                .inNamespace(getClient().getNamespace())
                .withLabel(KubeUtils.ENTANDO_RESOURCE_KIND_LABEL_NAME, "EntandoKeycloakServer");
        assertThrows(ConditionTimeoutException.class,
                () -> await().ignoreExceptions().atMost(5, TimeUnit.SECONDS).until(() -> podList.list().getItems().size() == 1));
        //When I prepare the Coordinator
        prepareCoordinator();

        //A controller pod gets created for the existing resource
        await().ignoreExceptions().atMost(5, TimeUnit.SECONDS).until(() -> podList.list().getItems().size() == 1);
    }

    @Test
    void testDuplicatesIgnored() {
        //Given I have a clean namespace
        TestFixturePreparation.prepareTestFixture(getClient(), deleteAll(EntandoKeycloakServer.class).fromNamespace(NAMESPACE));
        //and the Coordinator observes this namespace
        System.setProperty(EntandoOperatorConfigProperty.ENTANDO_NAMESPACES_TO_OBSERVE.getJvmSystemProperty(),
                getClient().getNamespace());
        prepareCoordinator();
        //And I have a config map with the Entando KeycloakController's image information
        ensureImageVersionsConfigMap(getClient());

        //And I have created an EntandoKeycloakServer resource
        EntandoKeycloakServer entandoKeycloakServer = createEntandoKeycloakServer("2", 1L);
        coordinator.getObserver(EntandoKeycloakServer.class).get(0).eventReceived(Action.ADDED, entandoKeycloakServer);

        //And its controller pod has been created
        FilterWatchListDeletable<Pod, PodList, Boolean, Watch, Watcher<Pod>> listable = getClient().pods()
                .inNamespace(getClient().getNamespace())
                .withLabel(KubeUtils.ENTANDO_RESOURCE_KIND_LABEL_NAME, "EntandoKeycloakServer");
        await().ignoreExceptions().atMost(5, TimeUnit.SECONDS).until(() -> listable.list().getItems().size() == 1);
        //when I submit a duplicate event
        EntandoKeycloakServer oldVersionOfKeycloakServer = keycloaks()
                .inNamespace(getClient().getNamespace()).withName(entandoKeycloakServer.getMetadata().getName()).get();
        oldVersionOfKeycloakServer.getMetadata().setResourceVersion("1");
        coordinator.getObserver(EntandoKeycloakServer.class).get(0).eventReceived(Action.ADDED, oldVersionOfKeycloakServer);
        //no new pod gets created
        assertThrows(ConditionTimeoutException.class,
                () -> await().ignoreExceptions().atMost(5, TimeUnit.SECONDS).until(() -> listable.list().getItems().size() > 1));
    }

    @Test
    void testIgnoreInstruction() {
        //Given I have a clean namespace
        TestFixturePreparation.prepareTestFixture(getClient(), deleteAll(EntandoKeycloakServer.class).fromNamespace(NAMESPACE));
        //and the Coordinator observes this namespace
        System.setProperty(EntandoOperatorConfigProperty.ENTANDO_NAMESPACES_TO_OBSERVE.getJvmSystemProperty(),
                getClient().getNamespace());
        //And I have a config map with the Entando KeycloakController's image information
        ensureImageVersionsConfigMap(getClient());

        //And I have created an EntandoKeycloakServer resource
        EntandoKeycloakServer entandoKeycloakServer = createEntandoKeycloakServer("1", 1L);
        //But I indicate it should be ignored
        keycloaks()
                .inNamespace(entandoKeycloakServer.getMetadata().getNamespace())
                .withName(entandoKeycloakServer.getMetadata().getName())
                .edit()
                .editMetadata()
                .addToAnnotations(KubeUtils.PROCESSING_INSTRUCTION_ANNOTATION_NAME,
                        OperatorProcessingInstruction.IGNORE.name().toLowerCase(Locale.ROOT))
                .endMetadata()
                .done();
        //when I start the Coordinator Controller
        prepareCoordinator();
        //no new pod gets created
        assertThrows(ConditionTimeoutException.class,
                () -> await().ignoreExceptions().atMost(8, TimeUnit.SECONDS).until(() ->
                        getClient().pods().inNamespace(entandoKeycloakServer.getMetadata().getNamespace()).list().getItems().size() > 0));
    }

    @Test
    void testIgnoredWhenOwnedByCompositeApp() {
        //Given I have a clean namespace
        TestFixturePreparation.prepareTestFixture(getClient(), deleteAll(EntandoKeycloakServer.class).fromNamespace(NAMESPACE));
        //and the Coordinator observes this namespace
        System.setProperty(EntandoOperatorConfigProperty.ENTANDO_NAMESPACES_TO_OBSERVE.getJvmSystemProperty(),
                getClient().getNamespace());
        //And I have a config map with the Entando KeycloakController's image information
        ensureImageVersionsConfigMap(getClient());
        //And I have created an EntandoKeycloakServer resource
        EntandoKeycloakServer entandoKeycloakServer = createEntandoKeycloakServer("1", 1L);
        //But it  has an ignored EntandoCompositeApp as an owner
        EntandoCompositeApp compositeApp = EntandoCompositeAppOperationFactory.produceAllEntandoCompositeApps(getClient())
                .inNamespace(NAMESPACE)
                .createNew()
                .editMetadata()
                .withName("composite-app")
                .withUid(UUID.randomUUID().toString())
                .withResourceVersion("123")
                .addToAnnotations(KubeUtils.PROCESSING_INSTRUCTION_ANNOTATION_NAME,
                        OperatorProcessingInstruction.IGNORE.name().toLowerCase(Locale.ROOT))
                .endMetadata()
                .withNewSpec()
                .addToEntandoKeycloakServers(entandoKeycloakServer)
                .endSpec()
                .done();
        keycloaks()
                .inNamespace(entandoKeycloakServer.getMetadata().getNamespace())
                .withName(entandoKeycloakServer.getMetadata().getName())
                .edit()
                .editMetadata()
                .addToOwnerReferences(KubeUtils.buildOwnerReference(compositeApp))
                .endMetadata()
                .done();
        //when I start the Coordinator Controller
        prepareCoordinator();
        //no new pod gets created
        assertThrows(ConditionTimeoutException.class,
                () -> await().ignoreExceptions().atMost(8, TimeUnit.SECONDS).until(() ->
                        getClient().pods().inNamespace(entandoKeycloakServer.getMetadata().getNamespace()).list().getItems().size() > 0));
    }

    @Test
    void testGenerationObservedIsCurrent() {
        //Given I have a clean namespace
        TestFixturePreparation.prepareTestFixture(getClient(), deleteAll(EntandoKeycloakServer.class).fromNamespace(NAMESPACE));
        //and the Coordinator observes this namespace
        System.setProperty(EntandoOperatorConfigProperty.ENTANDO_NAMESPACES_TO_OBSERVE.getJvmSystemProperty(),
                getClient().getNamespace());
        //And I have a config map with the Entando KeycloakController's image information
        ensureImageVersionsConfigMap(getClient());

        //And I have created an EntandoKeycloakServer resource
        final EntandoKeycloakServer entandoKeycloakServer = createEntandoKeycloakServer("1", 10L);
        //But the generation in the metadata is the same as the observedGeneration in the status
        entandoKeycloakServer.getStatus().updateDeploymentPhase(EntandoDeploymentPhase.STARTED, 10L);
        keycloaks()
                .inNamespace(entandoKeycloakServer.getMetadata().getNamespace())
                .withName(entandoKeycloakServer.getMetadata().getName())
                .updateStatus(entandoKeycloakServer);
        //when I start the Coordinator Controller
        prepareCoordinator();
        //no new pod gets created
        assertThrows(ConditionTimeoutException.class,
                () -> await().ignoreExceptions().atMost(8, TimeUnit.SECONDS).until(() ->
                        getClient().pods().inNamespace(entandoKeycloakServer.getMetadata().getNamespace()).list().getItems().size() > 0));
    }

    @Test
    void testGenerationObservedIsCurrentButForceInstructed() {
        //Given I have a clean namespace
        TestFixturePreparation.prepareTestFixture(getClient(), deleteAll(EntandoKeycloakServer.class).fromNamespace(NAMESPACE));
        //and the Coordinator observes this namespace
        System.setProperty(EntandoOperatorConfigProperty.ENTANDO_NAMESPACES_TO_OBSERVE.getJvmSystemProperty(),
                getClient().getNamespace());
        //And I have a config map with the Entando KeycloakController's image information
        ensureImageVersionsConfigMap(getClient());

        //And I have created an EntandoKeycloakServer resource
        final EntandoKeycloakServer entandoKeycloakServer = createEntandoKeycloakServer("1", 10L);
        //But the generation in the metadata is the same as the observedGeneration in the status
        entandoKeycloakServer.getStatus().updateDeploymentPhase(EntandoDeploymentPhase.STARTED, 10L);
        keycloaks()
                .inNamespace(entandoKeycloakServer.getMetadata().getNamespace())
                .withName(entandoKeycloakServer.getMetadata().getName())
                .updateStatus(entandoKeycloakServer);
        //but the FORCE ProcessingInstruction is issued
        keycloaks()
                .inNamespace(entandoKeycloakServer.getMetadata().getNamespace())
                .withName(entandoKeycloakServer.getMetadata().getName())
                .edit()
                .editMetadata()
                .addToAnnotations(KubeUtils.PROCESSING_INSTRUCTION_ANNOTATION_NAME,
                        OperatorProcessingInstruction.FORCE.name().toLowerCase(Locale.ROOT))
                .endMetadata()
                .done();
        //when I start the Coordinator Controller
        prepareCoordinator();
        //a new pod gets created
        await().ignoreExceptions().atMost(8, TimeUnit.SECONDS).until(() ->
                getClient().pods().inNamespace(entandoKeycloakServer.getMetadata().getNamespace()).list().getItems().size() > 0);
        //and the FORCED instruction has been removed
        assertThat(KubeUtils.resolveProcessingInstruction(keycloaks()
                .inNamespace(entandoKeycloakServer.getMetadata().getNamespace())
                .withName(entandoKeycloakServer.getMetadata().getName())
                .get()), is(OperatorProcessingInstruction.NONE));
    }

    @Test
    void testGenerationObservedIsBehind() {
        //Given I have a clean namespace
        TestFixturePreparation.prepareTestFixture(getClient(), deleteAll(EntandoKeycloakServer.class).fromNamespace(NAMESPACE));
        //and the Coordinator observes this namespace
        System.setProperty(EntandoOperatorConfigProperty.ENTANDO_NAMESPACES_TO_OBSERVE.getJvmSystemProperty(),
                getClient().getNamespace());
        //And I have a config map with the Entando KeycloakController's image information
        ensureImageVersionsConfigMap(getClient());

        //And I have created an EntandoKeycloakServer resource
        final EntandoKeycloakServer entandoKeycloakServer = createEntandoKeycloakServer("1", 10L);
        //But the generation in the metadata is the same as the observedGeneration in the status
        entandoKeycloakServer.getStatus().updateDeploymentPhase(EntandoDeploymentPhase.FAILED, 9L);
        keycloaks()
                .inNamespace(entandoKeycloakServer.getMetadata().getNamespace())
                .withName(entandoKeycloakServer.getMetadata().getName())
                .updateStatus(entandoKeycloakServer);
        //when I start the Coordinator Controller
        prepareCoordinator();
        //no new pod gets created
        await().ignoreExceptions().atMost(8, TimeUnit.SECONDS).until(() ->
                getClient().pods().inNamespace(entandoKeycloakServer.getMetadata().getNamespace()).list().getItems().size() > 0);
    }

    private CustomResourceOperationsImpl<EntandoKeycloakServer, EntandoKeycloakServerList, DoneableEntandoKeycloakServer> keycloaks() {
        return EntandoKeycloakServerOperationFactory.produceAllEntandoKeycloakServers(getClient());
    }

    protected void ensureImageVersionsConfigMap(KubernetesClient client) {
        String configMapNamespace = EntandoOperatorConfig.getOperatorConfigMapNamespace().orElse(NAMESPACE);
        String versionsConfigMap = EntandoOperatorConfig.getEntandoDockerImageInfoConfigMap();
        client.configMaps().inNamespace(configMapNamespace).createNew().withNewMetadata().withName(
                versionsConfigMap).endMetadata()
                .addToData("entando-k8s-keycloak-controller", "{\"version\":\"6.0.0\"}").done();
    }

    protected EntandoKeycloakServer createEntandoKeycloakServer(String resourceVersion, Long generation) {
        EntandoKeycloakServer keycloakServer = new EntandoKeycloakServerBuilder()
                .withNewMetadata()
                .withGeneration(generation)
                .withUid(RandomStringUtils.randomAlphanumeric(12))
                .withResourceVersion(resourceVersion)
                .withName("test-keycloak").withNamespace(getClient().getNamespace()).endMetadata()
                .withNewSpec()
                .withDbms(DbmsVendor.NONE)
                .endSpec()
                .build();
        keycloaks().inNamespace(getClient().getNamespace()).create(keycloakServer);
        return keycloakServer;
    }

    private void prepareCoordinator() {
        this.coordinator = new EntandoControllerCoordinator(getClient());
        coordinator.onStartup(new StartupEvent());
    }

    private KubernetesClient getClient() {
        return server.getClient().inNamespace(NAMESPACE);
    }

}
