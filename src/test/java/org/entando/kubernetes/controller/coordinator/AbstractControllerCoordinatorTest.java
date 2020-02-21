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

package org.entando.kubernetes.controller.coordinator;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.Watcher.Action;
import io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable;
import java.util.concurrent.TimeUnit;
import org.entando.kubernetes.controller.EntandoOperatorConfigProperty;
import org.entando.kubernetes.controller.KubeUtils;
import org.entando.kubernetes.controller.integrationtest.support.EntandoOperatorTestConfig;
import org.entando.kubernetes.controller.integrationtest.support.FluentIntegrationTesting;
import org.entando.kubernetes.controller.integrationtest.support.TestFixturePreparation;
import org.entando.kubernetes.controller.integrationtest.support.TestFixtureRequest;
import org.entando.kubernetes.controller.test.support.FluentTraversals;
import org.entando.kubernetes.controller.test.support.VariableReferenceAssertions;
import org.entando.kubernetes.model.DbmsVendor;
import org.entando.kubernetes.model.EntandoBaseCustomResource;
import org.entando.kubernetes.model.compositeapp.EntandoCompositeApp;
import org.entando.kubernetes.model.externaldatabase.EntandoDatabaseService;
import org.entando.kubernetes.model.externaldatabase.EntandoDatabaseServiceBuilder;
import org.entando.kubernetes.model.externaldatabase.EntandoDatabaseServiceOperationFactory;
import org.entando.kubernetes.model.keycloakserver.EntandoKeycloakServer;
import org.entando.kubernetes.model.keycloakserver.EntandoKeycloakServerBuilder;
import org.entando.kubernetes.model.keycloakserver.EntandoKeycloakServerOperationFactory;
import org.entando.kubernetes.model.plugin.EntandoPlugin;
import org.junit.jupiter.api.Test;

public abstract class AbstractControllerCoordinatorTest implements FluentIntegrationTesting, FluentTraversals, VariableReferenceAssertions {

    public static final String NAMESPACE = EntandoOperatorTestConfig.calculateNameSpace("coordinator-test");
    public static final String PLUGIN_NAME = EntandoOperatorTestConfig.calculateName("test-plugin");
    public static final String KEYCLOAK_NAME = EntandoOperatorTestConfig.calculateName("test-keycloak");
    public static final String MY_APP = EntandoOperatorTestConfig.calculateName("my-app");

    protected static void clearNamespace(KubernetesClient client) {
        TestFixturePreparation.prepareTestFixture(client,
                new TestFixtureRequest().deleteAll(EntandoCompositeApp.class).fromNamespace(NAMESPACE)
                        .deleteAll(EntandoDatabaseService.class).fromNamespace(NAMESPACE)
                        .deleteAll(EntandoPlugin.class).fromNamespace(NAMESPACE)
                        .deleteAll(EntandoKeycloakServer.class).fromNamespace(NAMESPACE));
    }

    protected abstract KubernetesClient getClient();

    @SuppressWarnings("unchecked")
    protected abstract <T extends EntandoBaseCustomResource> void afterCreate(T resource);

    @Test
    public void testExecuteKeycloakControllerPod() throws JsonProcessingException {
        //Given I have a clean namespace
        KubernetesClient client = getClient();
        clearNamespace(client);
        //and the Coordinator observes this namespace
        System.setProperty(EntandoOperatorConfigProperty.ENTANDO_K8S_OPERATOR_NAMESPACE_TO_OBSERVE.getJvmSystemProperty(),
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
    }

    @Test
    public void testExecuteControllerObject() {
        //Given I have a clear namespace
        clearNamespace(getClient());
        System.setProperty(EntandoOperatorConfigProperty.ENTANDO_K8S_OPERATOR_NAMESPACE_TO_OBSERVE.getJvmSystemProperty(),
                getClient().getNamespace());
        //When I create a new EntandoDatabaseService resource
        EntandoDatabaseService database = new EntandoDatabaseServiceBuilder()
                .withNewMetadata().withName("test-database").withNamespace(getClient().getNamespace()).endMetadata()
                .withNewSpec()
                .withDbms(DbmsVendor.ORACLE)
                .withHost("somedatabase.com")
                .withPort(5050)
                .withSecretName("oracle-secret")
                .endSpec()
                .build();
        EntandoDatabaseServiceOperationFactory.produceAllEntandoDatabaseServices(getClient())
                .inNamespace(getClient().getNamespace()).create(database);
        afterCreate(database);
        //Then I expect to see its Kubernetes service
        FilterWatchListDeletable<Service, ServiceList, Boolean, Watch, Watcher<Service>> listable = getClient()
                .services()
                .inNamespace(getClient().getNamespace()).withLabel("EntandoDatabaseService", database.getMetadata().getName());
        await().ignoreExceptions().atMost(30, TimeUnit.SECONDS).until(() -> listable.list().getItems().size() > 0);
        Service service = listable.list().getItems().get(0);
        assertThat(service.getSpec().getExternalName(), is("somedatabase.com"));
    }

    protected String ensureKeycloakControllerVersion() throws JsonProcessingException {
        ImageVersionPreparation imageVersionPreparation = new ImageVersionPreparation(getClient());
        return imageVersionPreparation.ensureImageVersion("entando-k8s-keycloak-controller", "6.0.1");
    }

}
