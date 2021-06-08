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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.apiextensions.v1beta1.CustomResourceDefinition;
import io.fabric8.kubernetes.api.model.apiextensions.v1beta1.CustomResourceDefinitionBuilder;
import io.qameta.allure.Description;
import io.qameta.allure.Feature;
import io.qameta.allure.Issue;
import io.quarkus.runtime.StartupEvent;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.entando.kubernetes.controller.coordinator.common.CoordinatorTestUtils;
import org.entando.kubernetes.controller.coordinator.common.SimpleKubernetesClientDouble;
import org.entando.kubernetes.controller.spi.client.SerializedEntandoResource;
import org.entando.kubernetes.controller.spi.common.EntandoOperatorSpiConfigProperty;
import org.entando.kubernetes.controller.spi.common.LabelNames;
import org.entando.kubernetes.controller.support.client.impl.integrationtesthelpers.FluentIntegrationTesting;
import org.entando.kubernetes.controller.support.common.EntandoOperatorConfigProperty;
import org.entando.kubernetes.fluentspi.BasicDeploymentSpecBuilder;
import org.entando.kubernetes.fluentspi.TestResource;
import org.entando.kubernetes.model.common.DbmsVendor;
import org.entando.kubernetes.model.common.EntandoDeploymentPhase;
import org.entando.kubernetes.test.common.CommonLabels;
import org.entando.kubernetes.test.common.FluentTraversals;
import org.entando.kubernetes.test.common.LogInterceptor;
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
@Feature("As a controller developer, I only want one of my controller pods to execute against a given resource at a time and have the "
        + "option to have successful pods garbage collected so that their behaviour is consistent and predictable")
@Issue("ENG-2284")
class PodManagementTests implements FluentIntegrationTesting, FluentTraversals,
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
                .readValue(Thread.currentThread().getContextClassLoader().getResource("testresources.test.org.crd.yaml"),
                        CustomResourceDefinition.class);
        clientDouble.getCluster().putCustomResourceDefinition(new CustomResourceDefinitionBuilder(testResourceDefinition)
                .editMetadata().addToLabels(LabelNames.CRD_OF_INTEREST.getName(), "TestResource")
                .addToAnnotations(AnnotationNames.CONTROLLER_IMAGE.getName(), "test/my-controller")
                .addToAnnotations(AnnotationNames.SUPPORTED_CAPABILITIES.getName(), "dbms")
                .endMetadata().build()
        );
        LogInterceptor.listenToClass(EntandoResourceObserver.class);
    }

    @AfterEach
    void clearProperties() {
        LogInterceptor.reset();
        System.clearProperty(EntandoOperatorConfigProperty.ENTANDO_K8S_OPERATOR_GC_CONTROLLER_PODS.getJvmSystemProperty());
        System.clearProperty(ControllerCoordinatorProperty.ENTANDO_K8S_CONTROLLER_REMOVAL_DELAY.getJvmSystemProperty());
        coordinator.shutdownObservers(5, TimeUnit.SECONDS);
        LogInterceptor.getLogEntries().clear();
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
        ValueHolder<Pod> firstPod = new ValueHolder<>();
        step("Then one controller pod gets created", () -> {
            await().ignoreExceptions().atMost(5, TimeUnit.SECONDS)
                    .until(() -> clientDouble.loadPod(clientDouble.getNamespace(), labelsFromResource(testResource.get())) != null);
            firstPod.set(clientDouble.loadPod(clientDouble.getNamespace(), labelsFromResource(testResource.get())));
            attachment("Controller Pod", objectMapper.writeValueAsString(firstPod.get()));
        });
        step("And the pod is given the necessary information to resolve the resource being processed", () -> {
            assertThat(theVariableNamed(EntandoOperatorSpiConfigProperty.ENTANDO_RESOURCE_NAME.name())
                    .on(thePrimaryContainerOn(firstPod.get()))).isEqualTo(testResource.get().getMetadata().getName());
            assertThat(theVariableNamed(EntandoOperatorSpiConfigProperty.ENTANDO_RESOURCE_NAMESPACE.name())
                    .on(thePrimaryContainerOn(firstPod.get()))).isEqualTo(testResource.get().getMetadata().getNamespace());

        });
        step("And this pod remains present even if I complete the deployment of the TestResource successfully", () -> {
            clientDouble.updatePodStatus(
                    podWithSucceededStatus(clientDouble.loadPod(clientDouble.getNamespace(), labelsFromResource(testResource.get()))));
            attachment("Successful Resource",
                    objectMapper.writeValueAsString(clientDouble.updatePhase(testResource.get(), EntandoDeploymentPhase.SUCCESSFUL)));
            await().ignoreExceptions().atMost(2, TimeUnit.SECONDS).ignoreExceptions()
                    .until(() -> LogInterceptor.getLogEntries().stream().anyMatch(s -> s.contains("was processed successfully")));
            assertThat(clientDouble.loadPod(clientDouble.getNamespace(), labelsFromResource(testResource.get()))).isNotNull();
        });

    }

    @Test
    @Description("Should always kill existing pods running against the same resource to avoid racing conditions")
    void shouldKillAllPreviousPods() {
        step("Given the Coordinator observes its own namespace", () -> {
            System.setProperty(EntandoOperatorConfigProperty.ENTANDO_NAMESPACES_TO_OBSERVE.getJvmSystemProperty(),
                    clientDouble.getNamespace());
            coordinator.onStartup(new StartupEvent());
        });
        step("And automatic controller pod garbage collections has been switched off", () ->
                System.setProperty(EntandoOperatorConfigProperty.ENTANDO_K8S_OPERATOR_GC_CONTROLLER_PODS.getJvmSystemProperty(),
                        "false"));
        ValueHolder<SerializedEntandoResource> testResource = new ValueHolder<>();
        step("And I have created a new TestResource resource", () -> {
            testResource.set(clientDouble.createOrPatchEntandoResource(CoordinatorTestUtils.toSerializedResource(
                    new TestResource().withNames(clientDouble.getNamespace(), "test-keycloak")
                            .withSpec(new BasicDeploymentSpecBuilder().withDbms(DbmsVendor.EMBEDDED).build()))));
            attachment("TestResource", objectMapper.writeValueAsString(testResource.get()));
        });
        ValueHolder<Pod> firstPod = new ValueHolder<>();
        step("And I have waited to see at least one controller pod", () -> {
            await().ignoreExceptions().atMost(5, TimeUnit.SECONDS)
                    .until(() -> clientDouble.loadPod(clientDouble.getNamespace(), labelsFromResource(testResource.get())) != null);
            firstPod.set(clientDouble.loadPod(clientDouble.getNamespace(), labelsFromResource(testResource.get())));
            attachment("FirstPod", objectMapper.writeValueAsString(firstPod.get()));
        });
        step("When I force a second attempt at processing the resource", () -> {
            final SerializedEntandoResource reloaded = clientDouble.reload(testResource.get());
            reloaded.getMetadata().setAnnotations(Map.of(AnnotationNames.PROCESSING_INSTRUCTION.name(), "force"));
            clientDouble.createOrPatchEntandoResource(reloaded);
        });
        ValueHolder<Pod> secondPod = new ValueHolder<>();
        step("Then a new pod was created", () -> {
            await().atMost(4, TimeUnit.SECONDS).ignoreExceptions()
                    .until(() -> {
                        final List<Pod> pods = clientDouble
                                .loadPods(clientDouble.getNamespace(), labelsFromResource(testResource.get()));
                        return !pods.get(pods.size() - 1).getMetadata().getUid().equals(firstPod.get().getMetadata().getUid());
                    });
            final List<Pod> pods = clientDouble.loadPods(clientDouble.getNamespace(), labelsFromResource(testResource.get()));
            attachment("Second Pod", objectMapper.writeValueAsString(pods.get(pods.size() - 1)));
        });
        step("And the old pod was removed", () -> {
            final List<Pod> pods = clientDouble.loadPods(clientDouble.getNamespace(), labelsFromResource(testResource.get()));
            assertThat(pods.size()).isOne();
        });
        //        //And the pod is given the necessary information to resolve the resource being processed
        //        assertThat(theVariableNamed(EntandoOperatorSpiConfigProperty.ENTANDO_RESOURCE_NAME.name())
        //                        .on(thePrimaryContainerOn(theControllerPod.get())),
        //                is(testResource.getMetadata().getName()));
        //        assertThat(theVariableNamed(EntandoOperatorSpiConfigProperty.ENTANDO_RESOURCE_NAMESPACE.name())
        //                        .on(thePrimaryContainerOn(theControllerPod.get())),
        //                is(testResource.getMetadata().getNamespace()));

    }

    @Test
    @Description("Should automatically remove successful controller pods once the resource has progress to the 'successful' phase")
    void shouldGarbageCollectSuccessulPods() {
        step("Given  I have activated controller Pod garbage collection with a removal delay of 1 second", () -> {
            System.setProperty(ControllerCoordinatorProperty.ENTANDO_K8S_CONTROLLER_REMOVAL_DELAY.getJvmSystemProperty(), "1");
            System.setProperty(EntandoOperatorConfigProperty.ENTANDO_K8S_OPERATOR_GC_CONTROLLER_PODS.getJvmSystemProperty(), "true");
        });
        step("And the Coordinator observes its own namespace", () -> {
            System.setProperty(EntandoOperatorConfigProperty.ENTANDO_NAMESPACES_TO_OBSERVE.getJvmSystemProperty(),
                    clientDouble.getNamespace());
            coordinator.onStartup(new StartupEvent());
        });
        ValueHolder<SerializedEntandoResource> testResource = new ValueHolder<>();
        step("And I have created a new TestResource resource", () -> {
            TestResource f = new TestResource().withNames(clientDouble.getNamespace(), "test-keycloak")
                    .withSpec(new BasicDeploymentSpecBuilder().withDbms(DbmsVendor.EMBEDDED).build());
            f.getMetadata().setGeneration(1L);
            testResource.set(clientDouble.createOrPatchEntandoResource(CoordinatorTestUtils.toSerializedResource(f)));
            attachment("TestResource", objectMapper.writeValueAsString(testResource.get()));
        });
        step("And I have waited for the controller pod to be created", () -> {
            await().ignoreExceptions().atMost(3, TimeUnit.SECONDS)
                    .until(() -> clientDouble.loadPod(clientDouble.getNamespace(), labelsFromResource(testResource.get())) != null);
            attachment("Controller Pod", objectMapper
                    .writeValueAsString(clientDouble.loadPod(clientDouble.getNamespace(), labelsFromResource(testResource.get()))));
        });
        step("When I complete the deployment of the TestResource successfully", () -> {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            executor.submit(() -> {
                clientDouble.updatePodStatus(
                        podWithSucceededStatus(clientDouble.loadPod(clientDouble.getNamespace(), labelsFromResource(testResource.get()))));
            });
            attachment("Suffessful Resource",
                    objectMapper.writeValueAsString(clientDouble.updatePhase(testResource.get(), EntandoDeploymentPhase.SUCCESSFUL)));
        });
        step("Then the previously created, but successfully completed controller pod will be removed", () ->
                await().ignoreExceptions().atMost(3, TimeUnit.SECONDS)
                        .until(() -> clientDouble.loadPod(clientDouble.getNamespace(), labelsFromResource(testResource.get())) == null));

    }

}
