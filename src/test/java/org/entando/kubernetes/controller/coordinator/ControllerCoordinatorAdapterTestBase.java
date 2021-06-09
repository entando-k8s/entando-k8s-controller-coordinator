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

import static org.awaitility.Awaitility.await;

import io.fabric8.kubernetes.api.model.apiextensions.v1beta1.CustomResourceDefinition;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.NamespacedKubernetesClient;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.entando.kubernetes.controller.support.client.impl.AbstractK8SIntegrationTest;
import org.entando.kubernetes.controller.support.client.impl.EntandoOperatorTestConfig;
import org.entando.kubernetes.fluentspi.TestResource;
import org.entando.kubernetes.model.app.EntandoApp;
import org.entando.kubernetes.model.externaldatabase.EntandoDatabaseService;
import org.entando.kubernetes.model.keycloakserver.EntandoKeycloakServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

public abstract class ControllerCoordinatorAdapterTestBase extends AbstractK8SIntegrationTest {

    public static final String MY_POD = "my-pod";
    public static final String NAMESPACE = EntandoOperatorTestConfig.calculateNameSpace("coordinator-namespace");

    @Override
    protected String[] getNamespacesToUse() {
        return new String[]{NAMESPACE, NAMESPACE + "2"};
    }

    protected NamespacedKubernetesClient getNamespacedKubernetesClient() {
        final NamespacedKubernetesClient c = new DefaultKubernetesClient().inNamespace(NAMESPACE);
        registerCrd(c);
        return c;
    }

    @BeforeEach
    void deletePods() {
        super.deleteAll(getFabric8Client().customResources(TestResource.class));
        super.deleteAll(getFabric8Client().customResources(EntandoApp.class));
        super.deleteAll(getFabric8Client().customResources(EntandoKeycloakServer.class));
        super.deleteAll(getFabric8Client().customResources(EntandoDatabaseService.class));
        await().atMost(1, TimeUnit.MINUTES).ignoreExceptions().until(() -> {
            if (getFabric8Client().pods().inNamespace(NAMESPACE).withName(MY_POD).fromServer().get() == null) {
                return true;
            } else {
                getFabric8Client().pods().inNamespace(NAMESPACE).withName(MY_POD).delete();
                return false;
            }
        });
    }

    @AfterEach
    void closeClient() {

        getFabric8Client().close();
    }

    protected void registerCrd(NamespacedKubernetesClient c) {
        try {
            final CustomResourceDefinition value = objectMapper
                    .readValue(Thread.currentThread().getContextClassLoader().getResource("mycrds.test.org.crd.yaml"),
                            CustomResourceDefinition.class);
            c.apiextensions().v1beta1().customResourceDefinitions().createOrReplace(value);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
