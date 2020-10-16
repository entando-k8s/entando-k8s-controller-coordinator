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

import static org.entando.kubernetes.controller.coordinator.AbstractControllerCoordinatorTest.NAMESPACE;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.Watcher.Action;
import io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable;
import io.fabric8.kubernetes.client.server.mock.KubernetesServer;
import io.quarkus.runtime.StartupEvent;
import java.util.concurrent.TimeUnit;
import org.apache.commons.lang3.RandomStringUtils;
import org.awaitility.core.ConditionTimeoutException;
import org.entando.kubernetes.controller.EntandoOperatorConfig;
import org.entando.kubernetes.controller.EntandoOperatorConfigProperty;
import org.entando.kubernetes.controller.KubeUtils;
import org.entando.kubernetes.controller.coordinator.EntandoControllerCoordinator;
import org.entando.kubernetes.controller.integrationtest.support.FluentIntegrationTesting;
import org.entando.kubernetes.controller.integrationtest.support.TestFixturePreparation;
import org.entando.kubernetes.model.DbmsVendor;
import org.entando.kubernetes.model.EntandoDeploymentPhase;
import org.entando.kubernetes.model.keycloakserver.EntandoKeycloakServer;
import org.entando.kubernetes.model.keycloakserver.EntandoKeycloakServerBuilder;
import org.entando.kubernetes.model.keycloakserver.EntandoKeycloakServerOperationFactory;
import org.junit.Rule;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Tags;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.migrationsupport.rules.EnableRuleMigrationSupport;

@Tags({@Tag("in-process"), @Tag("component"), @Tag("pre-deployment")})
@EnableRuleMigrationSupport
@SuppressWarnings("java:S5778")//because Awaitility knows which invocation throws the exception
class ControllerCoordinatorEdgeConditionsTest implements FluentIntegrationTesting {

    @Rule
    public KubernetesServer server = new KubernetesServer(false, true);
    private EntandoControllerCoordinator coordinator;

    @Test
    void testExistingResourcesProcessed() {
        //Given I have a clean namespace
        TestFixturePreparation.prepareTestFixture(getClient(), deleteAll(EntandoKeycloakServer.class).fromNamespace(NAMESPACE));
        //and the Coordinator observes this namespace
        System.setProperty(EntandoOperatorConfigProperty.ENTANDO_K8S_OPERATOR_NAMESPACE_TO_OBSERVE.getJvmSystemProperty(),
                getClient().getNamespace());
        //And I have a config map with the Entando KeycloakController's image information
        ensureImageVersionsConfigMap(getClient());

        //And I have created an EntandoKeycloakServer resource
        EntandoKeycloakServer entandoKeycloakServer = createEntandoKeycloakServer("2");
        //And no Pods have been created for it
        FilterWatchListDeletable<Pod, PodList, Boolean, Watch, Watcher<Pod>> listable = getClient().pods()
                .inNamespace(getClient().getNamespace())
                .withLabel(KubeUtils.ENTANDO_RESOURCE_KIND_LABEL_NAME, "EntandoKeycloakServer");
        assertThrows(ConditionTimeoutException.class,
                () -> await().ignoreExceptions().atMost(5, TimeUnit.SECONDS).until(() -> listable.list().getItems().size() == 1));
        //And that it will take 500 milliseconds for processing to start on the entandoApp
        new Thread(() -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                throw new IllegalStateException("Thread interrupted");
            }
            EntandoKeycloakServerOperationFactory.produceAllEntandoKeycloakServers(getClient())
                    .inNamespace(entandoKeycloakServer.getMetadata().getNamespace())
                    .withName(entandoKeycloakServer.getMetadata().getName())
                    .edit()
                    .withPhase(EntandoDeploymentPhase.STARTED)
                    .done();
        }).start();

        //When I prepare the Coordinator
        prepareCoordinator();

        //A controller pod gets created for the existing resource
        await().ignoreExceptions().atMost(5, TimeUnit.SECONDS).until(() -> listable.list().getItems().size() == 1);
    }

    @Test
    void testDuplicatesIgnored() {
        //Given I have a clean namespace
        TestFixturePreparation.prepareTestFixture(getClient(), deleteAll(EntandoKeycloakServer.class).fromNamespace(NAMESPACE));
        //and the Coordinator observes this namespace
        System.setProperty(EntandoOperatorConfigProperty.ENTANDO_K8S_OPERATOR_NAMESPACE_TO_OBSERVE.getJvmSystemProperty(),
                getClient().getNamespace());
        prepareCoordinator();
        //And I have a config map with the Entando KeycloakController's image information
        ensureImageVersionsConfigMap(getClient());

        //And I have created an EntandoKeycloakServer resource
        EntandoKeycloakServer entandoKeycloakServer = createEntandoKeycloakServer("2");
        coordinator.getObserver(EntandoKeycloakServer.class).get(0).eventReceived(Action.ADDED, entandoKeycloakServer);

        //And its controller pod has been created
        FilterWatchListDeletable<Pod, PodList, Boolean, Watch, Watcher<Pod>> listable = getClient().pods()
                .inNamespace(getClient().getNamespace())
                .withLabel(KubeUtils.ENTANDO_RESOURCE_KIND_LABEL_NAME, "EntandoKeycloakServer");
        await().ignoreExceptions().atMost(5, TimeUnit.SECONDS).until(() -> listable.list().getItems().size() == 1);
        //when I submit a duplicate event
        EntandoKeycloakServer oldVersionOfKeycloakServer = EntandoKeycloakServerOperationFactory.produceAllEntandoKeycloakServers(
                getClient())
                .inNamespace(getClient().getNamespace()).withName(entandoKeycloakServer.getMetadata().getName()).get();
        oldVersionOfKeycloakServer.getMetadata().setResourceVersion("1");
        coordinator.getObserver(EntandoKeycloakServer.class).get(0).eventReceived(Action.ADDED, oldVersionOfKeycloakServer);
        //no new pod gets created
        assertThrows(ConditionTimeoutException.class,
                () -> await().ignoreExceptions().atMost(5, TimeUnit.SECONDS).until(() -> listable.list().getItems().size() > 1));
    }

    protected void ensureImageVersionsConfigMap(KubernetesClient client) {
        String configMapNamespace = EntandoOperatorConfig.getOperatorConfigMapNamespace().orElse(NAMESPACE);
        String versionsConfigMap = EntandoOperatorConfig.getEntandoDockerImageInfoConfigMap();
        client.configMaps().inNamespace(configMapNamespace).createNew().withNewMetadata().withName(
                versionsConfigMap).endMetadata()
                .addToData("entando-k8s-keycloak-controller", "{\"version\":\"6.0.0\"}").done();
    }

    protected EntandoKeycloakServer createEntandoKeycloakServer(String resourceVersion) {
        EntandoKeycloakServer keycloakServer = new EntandoKeycloakServerBuilder()
                .withNewMetadata()
                .withUid(RandomStringUtils.randomAlphanumeric(12))
                .withResourceVersion(resourceVersion)
                .withName("test-keycloak").withNamespace(getClient().getNamespace()).endMetadata()
                .withNewSpec()
                .withDbms(DbmsVendor.NONE)
                .endSpec()
                .build();
        EntandoKeycloakServerOperationFactory.produceAllEntandoKeycloakServers(getClient())
                .inNamespace(getClient().getNamespace()).create(keycloakServer);
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
