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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.fabric8.kubernetes.api.model.apiextensions.v1beta1.CustomResourceDefinition;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.entando.kubernetes.controller.spi.common.SecretUtils;
import org.entando.kubernetes.controller.support.client.impl.AbstractK8SIntegrationTest;
import org.entando.kubernetes.controller.support.client.impl.integrationtesthelpers.TestFixturePreparation;
import org.entando.kubernetes.fluentspi.TestResource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;

public abstract class ControllerCoordinatorAdapterTestBase extends AbstractK8SIntegrationTest {

    public static final String MY_POD = "my-pod";

    protected void awaitDefaultToken(String namespace) {
        await().atMost(30, TimeUnit.SECONDS).ignoreExceptions()
                .until(() -> getFabric8Client().secrets().inNamespace(namespace).list()
                        .getItems().stream().anyMatch(secret -> TestFixturePreparation.isValidTokenSecret(secret, "default")));
    }

    @BeforeAll
    static void prepareCrds() {
        try (final DefaultKubernetesClient c = new DefaultKubernetesClient()) {
            registerCrdResource(c, "testresources.test.org.crd.yaml");
            registerCrdResource(c, "mycrds.test.org.crd.yaml");
            await().atMost(20, TimeUnit.SECONDS).ignoreExceptions().until(() -> {
                //Wait for the API to be 100% available
                final TestResource testResource1 = c.customResources(TestResource.class)
                        .inNamespace(new TestResource().withNames(MY_APP_NAMESPACE_1, "my-app").getMetadata().getNamespace())
                        .createOrReplace(new TestResource().withNames(MY_APP_NAMESPACE_1, "my-app"));
                c.secrets().inNamespace(testResource1.getMetadata().getNamespace())
                        .create(SecretUtils.buildSecret(testResource1, "dummy", "koos", "asdfasdf"));
                c.customResources(TestResource.class).inNamespace(testResource1.getMetadata().getNamespace())
                        .delete(testResource1);
                return true;
            });
        }
    }

    @AfterAll
    static void deleteCrds() {
        try (final DefaultKubernetesClient c = new DefaultKubernetesClient()) {
            c.apiextensions().v1beta1().customResourceDefinitions().withName("mycrds.test.org").delete();
            c.apiextensions().v1beta1().customResourceDefinitions().withName("testresources.test.org").delete();
        }
    }

    protected TestResource createTestResource(TestResource testResource1) {
        return getFabric8Client().customResources(TestResource.class).inNamespace(testResource1.getMetadata().getNamespace())
                .createOrReplace(testResource1);
    }

    @Override
    protected String[] getNamespacesToUse() {
        return new String[]{MY_APP_NAMESPACE_1, MY_APP_NAMESPACE_2};
    }

    @AfterEach
    void closeClient() {
        getFabric8Client().close();
    }

    protected static CustomResourceDefinition registerCrdResource(KubernetesClient c, String resourceName) {
        try {
            final CustomResourceDefinition value = new ObjectMapper(new YAMLFactory())
                    .readValue(Thread.currentThread().getContextClassLoader().getResource(resourceName),
                            CustomResourceDefinition.class);
            return c.apiextensions().v1beta1().customResourceDefinitions().createOrReplace(value);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
