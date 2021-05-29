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
import static java.lang.String.format;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.fabric8.kubernetes.api.model.apiextensions.v1beta1.CustomResourceDefinition;
import io.fabric8.kubernetes.api.model.apiextensions.v1beta1.CustomResourceDefinitionBuilder;
import io.fabric8.kubernetes.client.WatcherException;
import io.qameta.allure.Description;
import io.qameta.allure.Feature;
import io.qameta.allure.Issue;
import io.quarkus.runtime.StartupEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;
import org.entando.kubernetes.controller.coordinator.common.CoordinatorTestUtils;
import org.entando.kubernetes.controller.coordinator.common.SimpleKubernetesClientDouble;
import org.entando.kubernetes.controller.spi.client.SerializedEntandoResource;
import org.entando.kubernetes.controller.spi.common.LabelNames;
import org.entando.kubernetes.controller.support.client.impl.integrationtesthelpers.FluentIntegrationTesting;
import org.entando.kubernetes.controller.support.common.EntandoOperatorConfigProperty;
import org.entando.kubernetes.fluentspi.BasicDeploymentSpecBuilder;
import org.entando.kubernetes.fluentspi.TestResource;
import org.entando.kubernetes.model.common.DbmsVendor;
import org.entando.kubernetes.test.common.CommonLabels;
import org.entando.kubernetes.test.common.FluentTraversals;
import org.entando.kubernetes.test.common.PodBehavior;
import org.entando.kubernetes.test.common.ValueHolder;
import org.entando.kubernetes.test.common.VariableReferenceAssertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Tags;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@Tags({@Tag("component"), @Tag("in-process"), @Tag("allure"), @Tag("inner-hexagon"), @Tag("pre-deployment")})
@Feature("As a user of the Entando Operator, I want the operator automatically restart when its web socket clients lose connectivity with"
        + " Kubernetes service so that it can start processing my new resources  as soon as possible")
@Issue("ENG-2284")
class LivenessTests implements FluentIntegrationTesting, FluentTraversals,
        VariableReferenceAssertions, PodBehavior, CommonLabels {

    private final SimpleKubernetesClientDouble clientDouble = new SimpleKubernetesClientDouble();
    private final EntandoControllerCoordinator coordinator = new EntandoControllerCoordinator(clientDouble);
    private final ObjectMapper objectMapper = new ObjectMapper(new YAMLFactory());

    @BeforeEach
    void prepareCrds() throws IOException {
        System.setProperty(ControllerCoordinatorProperty.ENTANDO_STORE_LOG_ENTRIES.getJvmSystemProperty(), "true");
        System.clearProperty(EntandoOperatorConfigProperty.ENTANDO_NAMESPACES_TO_OBSERVE.getJvmSystemProperty());
        System.clearProperty(ControllerCoordinatorProperty.ENTANDO_K8S_OPERATOR_VERSION.getJvmSystemProperty());
        System.clearProperty(ControllerCoordinatorProperty.ENTANDO_K8S_OPERATOR_VERSION_TO_REPLACE.getJvmSystemProperty());
        final CustomResourceDefinition testResourceDefinition = objectMapper
                .readValue(Thread.currentThread().getContextClassLoader().getResource("testrources.test.org.crd.yaml"),
                        CustomResourceDefinition.class);
        clientDouble.getCluster().putCustomResourceDefinition(new CustomResourceDefinitionBuilder(testResourceDefinition)
                .editMetadata().addToLabels(LabelNames.CRD_OF_INTEREST.getName(), "TestResource")
                .addToAnnotations(AnnotationNames.CONTROLLER_IMAGE.getName(), "test/my-controller")
                .addToAnnotations(AnnotationNames.SUPPORTED_CAPABILITIES.getName(), "dbms")
                .endMetadata().build()
        );
    }

    @AfterEach
    void clearProperties() {
        System.clearProperty(EntandoOperatorConfigProperty.ENTANDO_K8S_OPERATOR_GC_CONTROLLER_PODS.getJvmSystemProperty());
        System.clearProperty(ControllerCoordinatorProperty.ENTANDO_K8S_CONTROLLER_REMOVAL_DELAY.getJvmSystemProperty());
        coordinator.shutdownObservers(5, TimeUnit.SECONDS);
        LogDelegator.getLogEntries().clear();
        System.clearProperty(EntandoOperatorConfigProperty.ENTANDO_NAMESPACES_TO_OBSERVE.getJvmSystemProperty());
        System.clearProperty(ControllerCoordinatorProperty.ENTANDO_STORE_LOG_ENTRIES.getJvmSystemProperty());

    }

    @Test
    @Description("Should create a  controller pod that points to the newly created resource")
    void shouldCreateControllerPodPointingToResource() {
        step("Given the Coordinator observes its own namespace", () -> {
            System.setProperty(EntandoOperatorConfigProperty.ENTANDO_NAMESPACES_TO_OBSERVE.getJvmSystemProperty(),
                    clientDouble.getNamespace());
            coordinator.onStartup(new StartupEvent());
        });
        ValueHolder<SerializedEntandoResource> testResource = new ValueHolder<>();
        step("And I have created a new TestResource resource", () -> {
            final TestResource r = new TestResource().withNames(clientDouble.getNamespace(), "test-keycloak")
                    .withSpec(new BasicDeploymentSpecBuilder().withDbms(DbmsVendor.EMBEDDED).build());
            r.getMetadata().setGeneration(1L);
            testResource.set(clientDouble.createOrPatchEntandoResource(CoordinatorTestUtils.toSerializedResource(
                    r)));
            attachment("TestResource", objectMapper.writeValueAsString(testResource.get()));
        });
        final File file = Paths.get("/tmp/EntandoControllerCoordinator.ready").toFile();
        clientDouble.getCluster().getResourceProcessor().getAllWatchers().forEach(watcher -> {
            Liveness.alive();
            step(format("When the watcher %s is closed", watcher.getClass().getSimpleName()), () -> {
                watcher.onClose(new WatcherException("Closed"));
            });
            step("Then the liveness probe will fail", () -> {
                assertThat(file).doesNotExist();
            });
        });

    }
}
