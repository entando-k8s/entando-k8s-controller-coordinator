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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.base.Strings;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watcher.Action;
import io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import io.quarkus.runtime.StartupEvent;
import java.io.Serializable;
import java.util.concurrent.TimeUnit;
import org.apache.commons.lang3.RandomStringUtils;
import org.entando.kubernetes.controller.coordinator.ControllerCoordinatorProperty;
import org.entando.kubernetes.controller.coordinator.EntandoControllerCoordinator;
import org.entando.kubernetes.controller.coordinator.ImageVersionPreparation;
import org.entando.kubernetes.controller.spi.common.EntandoOperatorConfigBase;
import org.entando.kubernetes.controller.spi.common.ResourceUtils;
import org.entando.kubernetes.controller.support.client.impl.EntandoOperatorTestConfig;
import org.entando.kubernetes.controller.support.client.impl.integrationtesthelpers.FluentIntegrationTesting;
import org.entando.kubernetes.controller.support.client.impl.integrationtesthelpers.TestFixturePreparation;
import org.entando.kubernetes.controller.support.client.impl.integrationtesthelpers.TestFixtureRequest;
import org.entando.kubernetes.controller.support.common.EntandoOperatorConfigProperty;
import org.entando.kubernetes.model.common.DbmsVendor;
import org.entando.kubernetes.model.common.EntandoBaseCustomResource;
import org.entando.kubernetes.model.common.EntandoCustomResourceStatus;
import org.entando.kubernetes.model.common.EntandoDeploymentPhase;
import org.entando.kubernetes.model.compositeapp.EntandoCompositeApp;
import org.entando.kubernetes.model.externaldatabase.EntandoDatabaseService;
import org.entando.kubernetes.model.keycloakserver.EntandoKeycloakServer;
import org.entando.kubernetes.model.keycloakserver.EntandoKeycloakServerBuilder;
import org.entando.kubernetes.model.plugin.EntandoPlugin;
import org.entando.kubernetes.test.common.FluentTraversals;
import org.entando.kubernetes.test.common.PodBehavior;
import org.entando.kubernetes.test.common.VariableReferenceAssertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Tags;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.migrationsupport.rules.EnableRuleMigrationSupport;

@Tags({@Tag("in-process"), @Tag("component"), @Tag("pre-deployment")})
@EnableRuleMigrationSupport
class ControllerCoordinatorMockedTest implements FluentIntegrationTesting, FluentTraversals,
        VariableReferenceAssertions, PodBehavior {

    public static final String NAMESPACE = EntandoOperatorTestConfig.calculateNameSpace("coordinator-test");
    private EntandoControllerCoordinator coordinator;

    protected static void clearNamespace(KubernetesClient client) {
        TestFixturePreparation.prepareTestFixture(client,
                new TestFixtureRequest().deleteAll(EntandoCompositeApp.class).fromNamespace(NAMESPACE)
                        .deleteAll(EntandoDatabaseService.class).fromNamespace(NAMESPACE)
                        .deleteAll(EntandoPlugin.class).fromNamespace(NAMESPACE)
                        .deleteAll(EntandoKeycloakServer.class).fromNamespace(NAMESPACE));
    }

    @AfterEach
    void clearProperties() {
        System.clearProperty(EntandoOperatorConfigProperty.ENTANDO_K8S_OPERATOR_GC_CONTROLLER_PODS.getJvmSystemProperty());
        System.clearProperty(ControllerCoordinatorProperty.ENTANDO_K8S_CONTROLLER_REMOVAL_DELAY.getJvmSystemProperty());
    }

    @BeforeEach
    public void prepareCoordinator() {
        this.coordinator = new EntandoControllerCoordinator(getFabric8Client());
        coordinator.onStartup(new StartupEvent());
        System.setProperty(EntandoOperatorConfigProperty.ENTANDO_K8S_OPERATOR_GC_CONTROLLER_PODS.getJvmSystemProperty(), "true");
    }

    public KubernetesClient getFabric8Client() {
        return null;
    }

    @SuppressWarnings("unchecked")
    protected <S extends Serializable, R extends EntandoBaseCustomResource<S, EntandoCustomResourceStatus>> void afterCreate(R resource) {
        if (resource.getMetadata().getUid() == null) {
            resource.getMetadata().setUid(RandomStringUtils.randomAlphanumeric(8));
        }
        if (Strings.isNullOrEmpty(resource.getMetadata().getResourceVersion())) {
            resource.getMetadata().setResourceVersion(Integer.toString(1));
        }
        coordinator.getObserver(CustomResourceDefinitionContext.fromCustomResourceType((Class<R>) resource.getClass()))
                .eventReceived(Action.ADDED, CoordinatorTestUtil.toSerializedResource(resource));
    }

    @SuppressWarnings("unchecked")
    protected <S extends Serializable, R extends EntandoBaseCustomResource<S, EntandoCustomResourceStatus>> void afterSuccess(R resource,
            Pod pod) {
        getFabric8Client().pods().inNamespace(pod.getMetadata().getNamespace()).withName(pod.getMetadata().getName())
                .patch(podWithSucceededStatus(pod));
        resource.getMetadata().setGeneration(1L);
        resource.getStatus().updateDeploymentPhase(EntandoDeploymentPhase.SUCCESSFUL, resource.getMetadata().getGeneration());
        coordinator.getObserver(CustomResourceDefinitionContext.fromCustomResourceType((Class<R>) resource.getClass()))
                .eventReceived(Action.ADDED, CoordinatorTestUtil.toSerializedResource(resource));
    }

    @Test
    void testExecuteKeycloakControllerPod() {
        //Given I have a clean namespace
        KubernetesClient client = getFabric8Client();
        clearNamespace(client);
        //and the Coordinator observes this namespace
        System.setProperty(EntandoOperatorConfigProperty.ENTANDO_NAMESPACES_TO_OBSERVE.getJvmSystemProperty(),
                client.getNamespace());
        //And I have a config map with the Entando KeycloakController's image information
        final String versionToExpect = ensureKeycloakControllerVersion();
        //When I create a new EntandoKeycloakServer resource
        EntandoKeycloakServer keycloakServer = new EntandoKeycloakServerBuilder()
                .withNewMetadata().withName("test-keycloak").withNamespace(client.getNamespace()).endMetadata()
                .withNewSpec()
                .withDbms(DbmsVendor.NONE)
                .endSpec()
                .build();
        client.customResources(EntandoKeycloakServer.class).inNamespace(client.getNamespace()).create(keycloakServer);
        afterCreate(keycloakServer);
        //Then I expect to see at least one controller pod
        FilterWatchListDeletable<Pod, PodList> listable = client.pods()
                .inNamespace(client.getNamespace())
                .withLabel(ResourceUtils.ENTANDO_RESOURCE_KIND_LABEL_NAME, "EntandoKeycloakServer");
        await().ignoreExceptions().atMost(30, TimeUnit.SECONDS).until(() -> listable.list().getItems().size() > 0);
        Pod theControllerPod = listable.list().getItems().get(0);
        assertThat(theVariableNamed("ENTANDO_RESOURCE_ACTION").on(thePrimaryContainerOn(theControllerPod)), is(Action.ADDED.name()));
        assertThat(theVariableNamed("ENTANDO_RESOURCE_NAME").on(thePrimaryContainerOn(theControllerPod)),
                is(keycloakServer.getMetadata().getName()));
        assertThat(theVariableNamed("ENTANDO_RESOURCE_NAMESPACE").on(thePrimaryContainerOn(theControllerPod)),
                is(keycloakServer.getMetadata().getNamespace()));
        //With the correct version specified
        assertTrue(thePrimaryContainerOn(theControllerPod).getImage().endsWith(versionToExpect));

    }

    @Test
    void testExecuteKeycloakControllerRemoval() {
        //Given I have a clean namespace
        KubernetesClient client = getFabric8Client();
        clearNamespace(client);
        //And I have activated Pod GC with a removal delay of 1 second
        System.setProperty(ControllerCoordinatorProperty.ENTANDO_K8S_CONTROLLER_REMOVAL_DELAY.getJvmSystemProperty(), "1");
        System.setProperty(EntandoOperatorConfigProperty.ENTANDO_K8S_OPERATOR_GC_CONTROLLER_PODS.getJvmSystemProperty(), "true");
        //and the Coordinator observes this namespace
        System.setProperty(EntandoOperatorConfigProperty.ENTANDO_NAMESPACES_TO_OBSERVE.getJvmSystemProperty(),
                client.getNamespace());
        //And I have a config map with the Entando KeycloakController's image information
        final String versionToExpect = ensureKeycloakControllerVersion();
        //And I have created a new EntandoKeycloakServer resource
        EntandoKeycloakServer keycloakServer = new EntandoKeycloakServerBuilder()
                .withNewMetadata().withName("test-keycloak").withNamespace(client.getNamespace()).endMetadata()
                .withNewSpec()
                .withDbms(DbmsVendor.NONE)
                .endSpec()
                .build();
        client.customResources(EntandoKeycloakServer.class).inNamespace(client.getNamespace()).create(keycloakServer);
        afterCreate(keycloakServer);
        //And I the controller pod is created.
        FilterWatchListDeletable<Pod, PodList> listable = client.pods()
                .inNamespace(client.getNamespace())
                .withLabel(ResourceUtils.ENTANDO_RESOURCE_KIND_LABEL_NAME, "EntandoKeycloakServer");
        await().ignoreExceptions().atMost(30, TimeUnit.SECONDS).until(() -> listable.list().getItems().size() > 0);
        //When I complete the Keycloak installation successfully
        afterSuccess(keycloakServer, listable.list().getItems().get(0));
        //THen the previously created controller pod will be removed
        await().ignoreExceptions().atMost(30, TimeUnit.SECONDS).until(() -> listable.list().getItems().isEmpty());

    }

    protected String ensureKeycloakControllerVersion() {
        return EntandoOperatorConfigBase.lookupProperty("RELATED_IMAGE_ENTANDO_K8S_KEYCLOAK_CONTROLLER")
                .map(s -> s.substring(s.indexOf("@")))
                .orElseGet(() -> new ImageVersionPreparation(getFabric8Client())
                        .ensureImageVersion("entando-k8s-keycloak-controller", "6.0.1"));
    }

}
