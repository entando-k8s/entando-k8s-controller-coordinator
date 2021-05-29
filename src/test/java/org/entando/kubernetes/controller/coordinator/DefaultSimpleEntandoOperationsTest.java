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

import static io.qameta.allure.Allure.attachment;
import static io.qameta.allure.Allure.step;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.client.NamespacedKubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import io.qameta.allure.Description;
import io.qameta.allure.Feature;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.entando.kubernetes.controller.coordinator.common.CoordinatorTestUtils;
import org.entando.kubernetes.controller.spi.common.PodResult;
import org.entando.kubernetes.controller.spi.common.PodResult.State;
import org.entando.kubernetes.fluentspi.TestResource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Tags;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.migrationsupport.rules.EnableRuleMigrationSupport;

@Tags({@Tag("adapter"), @Tag("pre-deployment"), @Tag("integration")})
@Feature("As a controller-coordinator developer, I would like perform common operations on a specific resource using a simple "
        + "interface to reduce the learning curve")
@EnableRuleMigrationSupport
class DefaultSimpleEntandoOperationsTest extends ControllerCoordinatorAdapterTestBase {

    DefaultSimpleEntandoOperations myClient;

    public DefaultSimpleEntandoOperations getMyOperations() {
        this.myClient = Objects.requireNonNullElseGet(this.myClient,
                () -> {
                    final NamespacedKubernetesClient client = getNamespacedKubernetesClient();
                    return new DefaultSimpleEntandoOperations(
                            client,
                            client.customResource(CustomResourceDefinitionContext.fromCustomResourceType(
                                    TestResource.class)));
                });
        return this.myClient;
    }

    @BeforeEach
    void deletePods() {
        super.deleteAll(getFabric8Client().pods());
    }

    @Test
    @Description("Should delete pods and wait until they have been successfully deleted ")
    void shouldRemoveSuccessfullyCompletedPods() {
        final TestResource testResource = new TestResource().withNames(NAMESPACE, "my-test-resource");
        step("Given I have a TestResource", () -> attachment("TestResource", objectMapper.writeValueAsString(testResource)));
        step("And I have started a service pod with the label associated with this resource that will complete after 1 second", () -> {
            final Pod startedPod = getFabric8Client().pods().inNamespace(NAMESPACE).create(new PodBuilder()
                    .withNewMetadata()
                    .withName("my-pod")
                    .withNamespace(NAMESPACE)
                    .addToLabels(CoordinatorUtils.podLabelsFor(testResource))
                    .endMetadata()
                    .withNewSpec()
                    .addNewContainer()
                    .withImage("busybox")
                    .withName("busybox")
                    .withArgs("/bin/sh", "-c", "sleep 3")
                    .endContainer()
                    .withRestartPolicy("Never")
                    .endSpec()
                    .build());
            attachment("Started Pod", objectMapper.writeValueAsString(startedPod));
        });
        step("And I have waited for the pod to be ready", () -> {
            await().ignoreExceptions().atMost(30, TimeUnit.SECONDS).until(() ->
                    PodResult.of(getFabric8Client().pods().inNamespace(NAMESPACE).withName("my-pod").fromServer().get()).getState()
                            != State.CREATING);
            attachment("Started Pod", objectMapper
                    .writeValueAsString(getFabric8Client().pods().inNamespace(NAMESPACE).withName("my-pod").fromServer().get()));
        });
        step("When I remove all the successfully completed pods", () -> {
            getMyOperations().removeSuccessfullyCompletedPods(CoordinatorTestUtils.toSerializedResource(testResource));
        });
        step("Then that pod will be absent immediately after the call finished", () -> {
            assertThat(getFabric8Client().pods().inNamespace(NAMESPACE).withName("my-pod").fromServer().get()).isNull();
        });
    }

}
