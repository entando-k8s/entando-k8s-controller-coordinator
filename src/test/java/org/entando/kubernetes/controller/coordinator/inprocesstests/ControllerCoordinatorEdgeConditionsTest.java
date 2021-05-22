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
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.Watcher.Action;
import io.quarkus.runtime.StartupEvent;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.commons.lang3.RandomStringUtils;
import org.entando.kubernetes.controller.coordinator.ControllerCoordinatorProperty;
import org.entando.kubernetes.controller.coordinator.CoordinatorUtils;
import org.entando.kubernetes.controller.coordinator.EntandoControllerCoordinator;
import org.entando.kubernetes.controller.coordinator.EntandoOperatorMatcher;
import org.entando.kubernetes.controller.spi.common.ResourceUtils;
import org.entando.kubernetes.controller.support.client.doubles.EntandoResourceClientDouble;
import org.entando.kubernetes.controller.support.client.impl.integrationtesthelpers.FluentIntegrationTesting;
import org.entando.kubernetes.controller.support.common.EntandoOperatorConfigProperty;
import org.entando.kubernetes.controller.support.common.KubeUtils;
import org.entando.kubernetes.controller.support.common.OperatorProcessingInstruction;
import org.entando.kubernetes.model.common.DbmsVendor;
import org.entando.kubernetes.model.common.EntandoDeploymentPhase;
import org.entando.kubernetes.model.compositeapp.EntandoCompositeApp;
import org.entando.kubernetes.model.compositeapp.EntandoCompositeAppBuilder;
import org.entando.kubernetes.model.debundle.EntandoDeBundle;
import org.entando.kubernetes.model.debundle.EntandoDeBundleBuilder;
import org.entando.kubernetes.model.keycloakserver.EntandoKeycloakServer;
import org.entando.kubernetes.model.keycloakserver.EntandoKeycloakServerBuilder;
import org.entando.kubernetes.test.common.CommonLabels;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Tags;
import org.junit.jupiter.api.Test;

@Tags({@Tag("in-process"), @Tag("component"), @Tag("pre-deployment")})
        //because Awaitility knows which invocation throws the exception
class ControllerCoordinatorEdgeConditionsTest implements FluentIntegrationTesting, CommonLabels {

    public static final String CONTROLLER_NAMESPACE = EntandoResourceClientDouble.CONTROLLER_NAMESPACE;
    public static final String OBSERVED_NAMESPACE = "observed-namespace";
    private final SimpleKubernetesClientDouble clientDouble = new SimpleKubernetesClientDouble();
    private final EntandoControllerCoordinator coordinator = new EntandoControllerCoordinator(clientDouble);

    @AfterEach
    void shutdownSchedulers() {
        System.clearProperty(EntandoOperatorConfigProperty.ENTANDO_NAMESPACES_TO_OBSERVE.getJvmSystemProperty());
        System.clearProperty(ControllerCoordinatorProperty.ENTANDO_K8S_OPERATOR_VERSION.getJvmSystemProperty());
        System.clearProperty(ControllerCoordinatorProperty.ENTANDO_K8S_OPERATOR_VERSION_TO_REPLACE.getJvmSystemProperty());
    }

    @Test
    void testExistingResourcesProcessed() throws InterruptedException {
        coordinator.shutdownObservers(5, TimeUnit.SECONDS);
        //Given the Coordinator observes this namespace
        System.setProperty(EntandoOperatorConfigProperty.ENTANDO_NAMESPACES_TO_OBSERVE.getJvmSystemProperty(), OBSERVED_NAMESPACE);

        //And I have created an EntandoKeycloakServer resource
        EntandoKeycloakServer entandoKeycloakServer = createEntandoKeycloakServer("2", 1L);
        //When I prepare the Coordinator
        coordinator.onStartup(new StartupEvent());

        //A controller pod gets created for the existing resource
        await().atMost(3, TimeUnit.SECONDS)
                .until(() -> clientDouble.loadPod(CONTROLLER_NAMESPACE, labelsFromResource(entandoKeycloakServer)) != null);
    }

    @Test
    void testDuplicatesIgnored() throws InterruptedException {
        //Given the Coordinator observes this namespace
        System.setProperty(EntandoOperatorConfigProperty.ENTANDO_NAMESPACES_TO_OBSERVE.getJvmSystemProperty(), OBSERVED_NAMESPACE);
        coordinator.onStartup(new StartupEvent());
        //And I have created an EntandoKeycloakServer resource
        EntandoKeycloakServer entandoKeycloakServer = createEntandoKeycloakServer("X", 1L);
        coordinator.getObserver(EntandoKeycloakServer.class)
                .eventReceived(Action.ADDED, CoordinatorUtils.toSerializedResource(entandoKeycloakServer));

        //And its controller pod has been created
        await().ignoreExceptions().atMost(3, TimeUnit.SECONDS)
                .until(() -> clientDouble.loadPod(CONTROLLER_NAMESPACE, labelsFromResource(entandoKeycloakServer)) != null);
        Pod oldPod = clientDouble.loadPod(CONTROLLER_NAMESPACE, labelsFromResource(entandoKeycloakServer));
        //when I submit a duplicate event
        EntandoKeycloakServer oldVersionOfKeycloakServer = new EntandoKeycloakServerBuilder(entandoKeycloakServer)
                .editMetadata()
                .withResourceVersion("X")
                .endMetadata()
                .build();
        coordinator.getObserver(EntandoKeycloakServer.class)
                .eventReceived(Action.ADDED, CoordinatorUtils.toSerializedResource(oldVersionOfKeycloakServer));
        coordinator.getObserver(EntandoKeycloakServer.class).shutDownAndWait(1, SECONDS);
        //no new pod gets created
        assertThat(clientDouble.loadPod(CONTROLLER_NAMESPACE, labelsFromResource(entandoKeycloakServer)), is(sameInstance(oldPod)));
    }

    @Test
    void testIgnoreInstruction() throws InterruptedException {
        //Given e Coordinator observes this namespace
        System.setProperty(EntandoOperatorConfigProperty.ENTANDO_NAMESPACES_TO_OBSERVE.getJvmSystemProperty(), OBSERVED_NAMESPACE);

        //And I have created an EntandoKeycloakServer resource
        EntandoKeycloakServer entandoKeycloakServer = createEntandoKeycloakServer("1", 1L);
        entandoKeycloakServer.getMetadata().setAnnotations(new HashMap<>());
        entandoKeycloakServer.getMetadata().getAnnotations().put(KubeUtils.PROCESSING_INSTRUCTION_ANNOTATION_NAME,
                OperatorProcessingInstruction.IGNORE.name().toLowerCase(Locale.ROOT));
        //when I start the Coordinator Controller
        coordinator.onStartup(new StartupEvent());
        //no new pod gets created
        coordinator.getObserver(EntandoKeycloakServer.class).shutDownAndWait(1, SECONDS);
        assertThat(clientDouble.loadPod(CONTROLLER_NAMESPACE, labelsFromResource(entandoKeycloakServer)), is(nullValue()));

    }

    @Test
    void testIgnoredWhenOwnedByCompositeApp() throws InterruptedException {
        //Given  the Coordinator observes this namespace
        System.setProperty(EntandoOperatorConfigProperty.ENTANDO_NAMESPACES_TO_OBSERVE.getJvmSystemProperty(), OBSERVED_NAMESPACE);
        //And I have created an EntandoKeycloakServer resource
        EntandoKeycloakServer entandoKeycloakServer = createEntandoKeycloakServer("1", 1L);
        //But it  has an ignored EntandoCompositeApp as an owner
        EntandoCompositeApp compositeApp = new EntandoCompositeAppBuilder()
                .editMetadata()
                .withNamespace(OBSERVED_NAMESPACE)
                .withName("composite-app")
                .withUid(UUID.randomUUID().toString())
                .withResourceVersion("123")
                .addToAnnotations(KubeUtils.PROCESSING_INSTRUCTION_ANNOTATION_NAME,
                        OperatorProcessingInstruction.IGNORE.name().toLowerCase(Locale.ROOT))
                .endMetadata()
                .withNewSpec()
                .addToEntandoKeycloakServers(entandoKeycloakServer)
                .endSpec()
                .build();
        clientDouble.createOrPatchEntandoResource(compositeApp);
        clientDouble.createOrPatchEntandoResource(new EntandoKeycloakServerBuilder(entandoKeycloakServer)
                .editMetadata()
                .addToOwnerReferences(ResourceUtils.buildOwnerReference(compositeApp))
                .endMetadata()
                .build());
        //when I start the Coordinator Controller
        coordinator.onStartup(new StartupEvent());
        coordinator.getObserver(EntandoKeycloakServer.class).shutDownAndWait(1, SECONDS);
        //no new pod gets created
        assertThat(clientDouble.loadPod(CONTROLLER_NAMESPACE, labelsFromResource(entandoKeycloakServer)), is(nullValue()));
    }

    @Test
    void testGenerationObservedIsCurrent() throws InterruptedException {
        //Given the Coordinator observes this namespace
        System.setProperty(EntandoOperatorConfigProperty.ENTANDO_NAMESPACES_TO_OBSERVE.getJvmSystemProperty(), OBSERVED_NAMESPACE);
        //And I have created an EntandoKeycloakServer resource
        final EntandoKeycloakServer entandoKeycloakServer = createEntandoKeycloakServer("1", 10L);
        //But the generation in the metadata is the same as the observedGeneration in the status
        entandoKeycloakServer.getStatus().updateDeploymentPhase(EntandoDeploymentPhase.STARTED, 10L);
        //when I start the Coordinator Controller
        coordinator.onStartup(new StartupEvent());
        coordinator.getObserver(EntandoKeycloakServer.class).shutDownAndWait(1, SECONDS);
        //no new pod gets created
        assertThat(clientDouble.loadPod(CONTROLLER_NAMESPACE, labelsFromResource(entandoKeycloakServer)), is(nullValue()));
    }

    @Test
    void testGenerationObservedIsCurrentButForceInstructed() throws InterruptedException {
        //Given the Coordinator observes this namespace
        System.setProperty(EntandoOperatorConfigProperty.ENTANDO_NAMESPACES_TO_OBSERVE.getJvmSystemProperty(), OBSERVED_NAMESPACE);
        //And I have created an EntandoKeycloakServer resource
        final EntandoKeycloakServer entandoKeycloakServer = createEntandoKeycloakServer("1", 10L);
        //But the generation in the metadata is the same as the observedGeneration in the status
        entandoKeycloakServer.getStatus().updateDeploymentPhase(EntandoDeploymentPhase.STARTED, 10L);
        //but the FORCE ProcessingInstruction is issued

        entandoKeycloakServer.getMetadata().setAnnotations(new HashMap<>());
        entandoKeycloakServer.getMetadata().getAnnotations().put(KubeUtils.PROCESSING_INSTRUCTION_ANNOTATION_NAME,
                OperatorProcessingInstruction.FORCE.name().toLowerCase(Locale.ROOT));
        clientDouble.createOrPatchEntandoResource(entandoKeycloakServer);
        //when I start the Coordinator Controller
        coordinator.onStartup(new StartupEvent());
        coordinator.getObserver(EntandoKeycloakServer.class).shutDownAndWait(1, SECONDS);

        //a new pod gets created
        await().ignoreExceptions().atMost(3, TimeUnit.SECONDS)
                .until(() -> {
                    final Map<String, String> labels = labelsFromResource(entandoKeycloakServer);
                    return clientDouble.loadPod(CONTROLLER_NAMESPACE, labels) != null;
                });
        //and the FORCED instruction has been removed
        final EntandoKeycloakServer latestKeycloakServer = clientDouble
                .load(EntandoKeycloakServer.class, entandoKeycloakServer.getMetadata().getNamespace(),
                        entandoKeycloakServer.getMetadata().getName());
        assertThat(KubeUtils.resolveProcessingInstruction(latestKeycloakServer), is(OperatorProcessingInstruction.NONE));
    }

    @Test
    void testGenerationObservedIsBehind() throws InterruptedException {
        //Given Ihe Coordinator observes this namespace
        System.setProperty(EntandoOperatorConfigProperty.ENTANDO_NAMESPACES_TO_OBSERVE.getJvmSystemProperty(), OBSERVED_NAMESPACE);
        //And I have created an EntandoKeycloakServer resource
        final EntandoKeycloakServer entandoKeycloakServer = createEntandoKeycloakServer("1", 10L);
        //But the  observedGeneration in the status is behind generation in the metadata
        entandoKeycloakServer.getStatus().updateDeploymentPhase(EntandoDeploymentPhase.SUCCESSFUL, 9L);
        clientDouble.createOrPatchEntandoResource(entandoKeycloakServer);
        //when I start the Coordinator Controller
        coordinator.onStartup(new StartupEvent());
        coordinator.getObserver(EntandoKeycloakServer.class).shutDownAndWait(1, SECONDS);
        //then a new controller pod gets created
        await().ignoreExceptions().atMost(3, TimeUnit.SECONDS).until(() ->
                clientDouble.loadPod(CONTROLLER_NAMESPACE, labelsFromResource(entandoKeycloakServer)) != null);
    }

    @Test
    void testProcessedByVersion() throws InterruptedException {
        //Given the Coordinator observes this namespace
        System.setProperty(EntandoOperatorConfigProperty.ENTANDO_NAMESPACES_TO_OBSERVE.getJvmSystemProperty(), OBSERVED_NAMESPACE);
        //And the current version of the operator is 6,3,1
        System.setProperty(ControllerCoordinatorProperty.ENTANDO_K8S_OPERATOR_VERSION.getJvmSystemProperty(), "6.3.1");
        //And I have created an EntandoKeycloakServer resource
        final EntandoKeycloakServer entandoKeycloakServer = createEntandoKeycloakServer("1", 10L);
        clientDouble.createOrPatchEntandoResource(entandoKeycloakServer);
        //and the Controller Coordinator has started
        coordinator.onStartup(new StartupEvent());
        coordinator.getObserver(EntandoKeycloakServer.class).shutDownAndWait(1, SECONDS);
        //then a new controller pod gets created
        await().ignoreExceptions().atMost(3, TimeUnit.SECONDS).until(() ->
                clientDouble.loadPod(CONTROLLER_NAMESPACE, labelsFromResource(entandoKeycloakServer)) != null);
        entandoKeycloakServer.getMetadata().setResourceVersion("5");
        entandoKeycloakServer.getStatus().updateDeploymentPhase(EntandoDeploymentPhase.SUCCESSFUL, 10L);
        coordinator.getObserver(EntandoKeycloakServer.class)
                .eventReceived(Action.MODIFIED, CoordinatorUtils.toSerializedResource(entandoKeycloakServer));
        // Then  the EntandoKeycloakServer has been annotated with the version 6.3.1
        EntandoKeycloakServer latestKeycloakServer = clientDouble.load(EntandoKeycloakServer.class,
                entandoKeycloakServer.getMetadata().getNamespace(),
                entandoKeycloakServer.getMetadata().getName());
        assertThat(
                KubeUtils.resolveAnnotation(latestKeycloakServer, EntandoOperatorMatcher.ENTANDO_K8S_PROCESSED_BY_OPERATOR_VERSION).get(),
                is("6.3.1"));
    }

    @Test
    void testUpgrade() throws InterruptedException {
        //Given the Coordinator observes this namespace
        System.setProperty(EntandoOperatorConfigProperty.ENTANDO_NAMESPACES_TO_OBSERVE.getJvmSystemProperty(), OBSERVED_NAMESPACE);
        //And the current version of the operator is 6,3,1
        System.setProperty(ControllerCoordinatorProperty.ENTANDO_K8S_OPERATOR_VERSION.getJvmSystemProperty(), "6.3.1");
        //And the version of the operator to replace 6,3,1
        System.setProperty(ControllerCoordinatorProperty.ENTANDO_K8S_OPERATOR_VERSION_TO_REPLACE.getJvmSystemProperty(), "6.3.0");
        //And I have created an EntandoKeycloakServer resource
        final EntandoKeycloakServer entandoKeycloakServer = createEntandoKeycloakServer("1", 10L);
        entandoKeycloakServer.getMetadata().setAnnotations(new HashMap<>());
        //That has been annotated as processed by version 6.3.0
        entandoKeycloakServer.getMetadata().getAnnotations().put(EntandoOperatorMatcher.ENTANDO_K8S_PROCESSED_BY_OPERATOR_VERSION, "6.3.0");
        //And it has been successfully synced previously
        entandoKeycloakServer.getStatus().updateDeploymentPhase(EntandoDeploymentPhase.SUCCESSFUL, 10L);
        clientDouble.createOrPatchEntandoResource(entandoKeycloakServer);
        //And I have started the Coordinator Controller
        coordinator.onStartup(new StartupEvent());
        coordinator.getObserver(EntandoKeycloakServer.class).shutDownAndWait(1, SECONDS);
        //then a new controller pod gets created
        await().ignoreExceptions().atMost(3, TimeUnit.SECONDS).until(() ->
                clientDouble.loadPod(CONTROLLER_NAMESPACE, labelsFromResource(entandoKeycloakServer)) != null);
        entandoKeycloakServer.getMetadata().setResourceVersion("5");
        entandoKeycloakServer.getStatus().updateDeploymentPhase(EntandoDeploymentPhase.SUCCESSFUL, 10L);
        coordinator.getObserver(EntandoKeycloakServer.class)
                .eventReceived(Action.MODIFIED, CoordinatorUtils.toSerializedResource(entandoKeycloakServer));
        //Then the EntandoKeycloakServer has been annotated with the version 6.3.1
        EntandoKeycloakServer latestKeycloakServer = clientDouble.load(EntandoKeycloakServer.class,
                entandoKeycloakServer.getMetadata().getNamespace(),
                entandoKeycloakServer.getMetadata().getName());
        assertThat(
                KubeUtils.resolveAnnotation(latestKeycloakServer, EntandoOperatorMatcher.ENTANDO_K8S_PROCESSED_BY_OPERATOR_VERSION).get(),
                is("6.3.1"));
    }

    @Test
    void testEntandoDeBundle() {
        //Given the Coordinator observes this namespace
        System.setProperty(EntandoOperatorConfigProperty.ENTANDO_NAMESPACES_TO_OBSERVE.getJvmSystemProperty(), OBSERVED_NAMESPACE);
        //And I have created an EntandoDeBundle resource
        clientDouble.createOrPatchEntandoResource(new EntandoDeBundleBuilder().withNewMetadata()
                .withNamespace(OBSERVED_NAMESPACE)
                .withResourceVersion("123")
                .withGeneration(1L)
                .withUid(UUID.randomUUID().toString())
                .withName("my-de-bundle")
                .endMetadata()
                .build());

        //when I start the Coordinator Controller
        coordinator.onStartup(new StartupEvent());
        //THe status is updated
        await().ignoreExceptions().atMost(3, TimeUnit.SECONDS).until(() ->
                clientDouble.load(EntandoDeBundle.class, OBSERVED_NAMESPACE, "my-de-bundle").getStatus()
                        .getPhase() == EntandoDeploymentPhase.SUCCESSFUL);
    }

    protected EntandoKeycloakServer createEntandoKeycloakServer(String resourceVersion, Long generation) {
        EntandoKeycloakServer keycloakServer = new EntandoKeycloakServerBuilder()
                .withNewMetadata()
                .withGeneration(generation)
                .withUid(RandomStringUtils.randomAlphanumeric(12))
                .withResourceVersion(resourceVersion)
                .withName("test-keycloak")
                .withNamespace(OBSERVED_NAMESPACE)
                .endMetadata()
                .withNewSpec()
                .withDbms(DbmsVendor.NONE)
                .endSpec()
                .build();
        clientDouble.createOrPatchEntandoResource(keycloakServer);
        return keycloakServer;
    }

}
