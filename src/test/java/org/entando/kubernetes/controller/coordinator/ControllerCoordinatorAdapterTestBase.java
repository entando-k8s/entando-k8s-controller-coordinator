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
import org.junit.jupiter.api.AfterEach;

public abstract class ControllerCoordinatorAdapterTestBase extends AbstractK8SIntegrationTest {

    public static final String MY_POD = "my-pod";

    protected void awaitDefaultToken(String namespace) {
        await().atMost(30, TimeUnit.SECONDS).ignoreExceptions()
                .until(() -> getFabric8Client().secrets().inNamespace(namespace).list()
                        .getItems().stream().anyMatch(secret -> isValidTokenSecret(secret, "default")));
    }

    @Override
    protected String[] getNamespacesToUse() {
        return new String[]{MY_APP_NAMESPACE_1, MY_APP_NAMESPACE_2};
    }

    protected NamespacedKubernetesClient getNamespacedKubernetesClient() {
        final NamespacedKubernetesClient c = new DefaultKubernetesClient().inNamespace(MY_APP_NAMESPACE_1);
        registerCrd(c);
        return c;
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
