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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.base.Strings;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.Watcher.Action;
import io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable;
import io.fabric8.kubernetes.client.server.mock.KubernetesServer;
import io.quarkus.runtime.StartupEvent;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;
import org.apache.commons.lang3.RandomStringUtils;
import org.entando.kubernetes.controller.EntandoOperatorConfigProperty;
import org.entando.kubernetes.controller.KubeUtils;
import org.entando.kubernetes.controller.coordinator.AbstractControllerCoordinatorTest;
import org.entando.kubernetes.controller.coordinator.EntandoControllerCoordinator;
import org.entando.kubernetes.model.DbmsVendor;
import org.entando.kubernetes.model.EntandoBaseCustomResource;
import org.entando.kubernetes.model.EntandoDeploymentPhase;
import org.entando.kubernetes.model.keycloakserver.EntandoKeycloakServer;
import org.entando.kubernetes.model.keycloakserver.EntandoKeycloakServerBuilder;
import org.entando.kubernetes.model.keycloakserver.EntandoKeycloakServerOperationFactory;
import org.junit.Rule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.migrationsupport.rules.EnableRuleMigrationSupport;

@Tag("in-process")
@EnableRuleMigrationSupport
public class ControllerCoordinatorMockedTest extends AbstractControllerCoordinatorTest {

    @Rule
    public KubernetesServer server = new KubernetesServer(false, true);
    private EntandoControllerCoordinator coordinator;

    @BeforeEach
    public void prepareCoordinator() {
        this.coordinator = new EntandoControllerCoordinator(getClient());
        coordinator.onStartup(new StartupEvent());
    }

    @Override
    protected KubernetesClient getClient() {
        return server.getClient().inNamespace(NAMESPACE);
    }

    @Override
    @SuppressWarnings("unchecked")
    protected <T extends EntandoBaseCustomResource> void afterCreate(T resource) {
        if (resource.getMetadata().getUid() == null) {
            resource.getMetadata().setUid(RandomStringUtils.randomAlphanumeric(8));
        }
        if (Strings.isNullOrEmpty(resource.getMetadata().getResourceVersion())) {
            resource.getMetadata().setResourceVersion(Integer.toString(1));
        }
        coordinator.getObserver((Class<T>) resource.getClass()).get(0).eventReceived(Action.ADDED, resource);
    }

    @Override
    protected <T extends EntandoBaseCustomResource> void afterModified(T resource) {
        if (resource.getMetadata().getUid() == null) {
            resource.getMetadata().setUid(RandomStringUtils.randomAlphanumeric(8));
        }
        coordinator.getObserver((Class<T>) resource.getClass()).get(0).eventReceived(Action.MODIFIED, resource);
    }

    @Test
    public void testRunControllerWithDeleteAction() throws JsonProcessingException {
        //Given I have a clear namespace
        KubernetesClient client = getClient();
        clearNamespace(client);
        String namespace = client.getNamespace();
        System.setProperty(EntandoOperatorConfigProperty.ENTANDO_K8S_OPERATOR_NAMESPACE_TO_OBSERVE.getJvmSystemProperty(),
                namespace);
        //When I create a new EntandoDatabaseService resource
        final String versionToExpect = ensureKeycloakControllerVersion();
        //When I create a new EntandoKeycloakServer resource
        EntandoKeycloakServer keycloakServer = new EntandoKeycloakServerBuilder()
                .withNewMetadata()
                .withName("test-keycloak").withNamespace(namespace)
                .endMetadata()
                .withNewSpec()
                .withDbms(DbmsVendor.NONE)
                .endSpec()
                .build();
        EntandoKeycloakServerOperationFactory.produceAllEntandoKeycloakServers(client)
                .inNamespace(client.getNamespace()).create(keycloakServer);
        afterCreate(keycloakServer);

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
        assertTrue(thePrimaryContainerOn(theControllerPod).getImage().endsWith(versionToExpect));

        String resourceVersion = keycloakServer.getMetadata().getResourceVersion();
        keycloakServer.getMetadata().setDeletionTimestamp(LocalDateTime.now().toString());
        keycloakServer.getMetadata().setResourceVersion(Integer.toString(Integer.parseInt(resourceVersion) + 1));
        keycloakServer.getStatus().setEntandoDeploymentPhase(EntandoDeploymentPhase.SUCCESSFUL);
        afterModified(keycloakServer);

        //Then I expect to see a keycloak deployer with delete event
        FilterWatchListDeletable<Pod, PodList, Boolean, Watch, Watcher<Pod>> newListable = client.pods()
                .inNamespace(client.getNamespace())
                .withLabel(KubeUtils.ENTANDO_RESOURCE_KIND_LABEL_NAME, "EntandoKeycloakServer");
        await().ignoreExceptions().atMost(30, TimeUnit.SECONDS).until(() -> newListable.list().getItems().stream()
                .anyMatch(p -> theVariableNamed("ENTANDO_RESOURCE_ACTION").on(thePrimaryContainerOn(p)).equals(Action.DELETED.name())));

        theControllerPod = newListable.list().getItems().stream()
                .filter(p -> theVariableNamed("ENTANDO_RESOURCE_ACTION").on(thePrimaryContainerOn(p)).equals(Action.DELETED.name()))
                .findFirst().get();
        assertThat(theVariableNamed("ENTANDO_RESOURCE_ACTION").on(thePrimaryContainerOn(theControllerPod)), is(Action.DELETED.name()));
        assertThat(theVariableNamed("ENTANDO_RESOURCE_NAME").on(thePrimaryContainerOn(theControllerPod)),
                is(keycloakServer.getMetadata().getName()));
        assertThat(theVariableNamed("ENTANDO_RESOURCE_NAMESPACE").on(thePrimaryContainerOn(theControllerPod)),
                is(keycloakServer.getMetadata().getNamespace()));
        //With the correct version specified
        assertTrue(thePrimaryContainerOn(theControllerPod).getImage().endsWith(versionToExpect));
    }

}
