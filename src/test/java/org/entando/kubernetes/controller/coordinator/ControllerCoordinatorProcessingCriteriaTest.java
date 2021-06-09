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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.apiextensions.v1beta1.CustomResourceDefinition;
import io.fabric8.kubernetes.api.model.apiextensions.v1beta1.CustomResourceDefinitionBuilder;
import io.fabric8.kubernetes.client.Watcher.Action;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import io.qameta.allure.Description;
import io.qameta.allure.Feature;
import io.qameta.allure.Issue;
import io.quarkus.runtime.StartupEvent;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.apache.commons.lang3.RandomStringUtils;
import org.entando.kubernetes.controller.coordinator.common.CoordinatorTestUtils;
import org.entando.kubernetes.controller.coordinator.common.SimpleKubernetesClientDouble;
import org.entando.kubernetes.controller.spi.client.SerializedEntandoResource;
import org.entando.kubernetes.controller.spi.common.LabelNames;
import org.entando.kubernetes.controller.spi.common.ResourceUtils;
import org.entando.kubernetes.controller.support.client.doubles.EntandoResourceClientDouble;
import org.entando.kubernetes.controller.support.client.impl.integrationtesthelpers.FluentIntegrationTesting;
import org.entando.kubernetes.fluentspi.BasicDeploymentSpecBuilder;
import org.entando.kubernetes.fluentspi.TestResource;
import org.entando.kubernetes.model.capability.ProvidedCapabilityBuilder;
import org.entando.kubernetes.model.capability.StandardCapability;
import org.entando.kubernetes.model.common.DbmsVendor;
import org.entando.kubernetes.model.common.EntandoDeploymentPhase;
import org.entando.kubernetes.test.common.CommonLabels;
import org.entando.kubernetes.test.common.LogInterceptor;
import org.entando.kubernetes.test.common.ValueHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Tags;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@Tags({@Tag("component"), @Tag("in-process"), @Tag("allure"), @Tag("inner-hexagon"), @Tag("pre-deployment")})
@Feature("As a controller developer, I want my controller to be triggered when my custom resources are encountered but only when the "
        + "underlying conditions require it so that they are not run unnecessarily")
@Issue("ENG-2284")
class ControllerCoordinatorProcessingCriteriaTest implements FluentIntegrationTesting, CommonLabels {

    public static final String CONTROLLER_NAMESPACE = EntandoResourceClientDouble.CONTROLLER_NAMESPACE;
    public static final String OBSERVED_NAMESPACE = "observed-namespace";
    private final SimpleKubernetesClientDouble clientDouble = new SimpleKubernetesClientDouble();
    private final EntandoControllerCoordinator coordinator = new EntandoControllerCoordinator(clientDouble);
    ObjectMapper objectMapper = new ObjectMapper(new YAMLFactory());

    @AfterEach
    void shutdownSchedulers() {
        coordinator.shutdownObservers(5, TimeUnit.SECONDS);
        LogInterceptor.getLogEntries().clear();
        System.clearProperty(ControllerCoordinatorProperty.ENTANDO_NAMESPACES_TO_OBSERVE.getJvmSystemProperty());
        System.clearProperty(ControllerCoordinatorProperty.ENTANDO_K8S_OPERATOR_VERSION.getJvmSystemProperty());
        System.clearProperty(ControllerCoordinatorProperty.ENTANDO_K8S_OPERATOR_VERSION_TO_REPLACE.getJvmSystemProperty());
        System.clearProperty(ControllerCoordinatorProperty.ENTANDO_STORE_LOG_ENTRIES.getJvmSystemProperty());
        LogInterceptor.reset();
    }

    @BeforeEach
    void prepareCrds() throws IOException {
        System.setProperty(ControllerCoordinatorProperty.ENTANDO_STORE_LOG_ENTRIES.getJvmSystemProperty(), "true");
        System.clearProperty(ControllerCoordinatorProperty.ENTANDO_NAMESPACES_TO_OBSERVE.getJvmSystemProperty());
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
        final CustomResourceDefinition value = objectMapper
                .readValue(Thread.currentThread().getContextClassLoader().getResource("crd/providedcapabilities.entando.org.crd.yaml"),
                        CustomResourceDefinition.class);
        clientDouble.getCluster().putCustomResourceDefinition(new CustomResourceDefinitionBuilder(value)
                .editMetadata().addToLabels(LabelNames.CRD_OF_INTEREST.getName(), "ProvidedCapability")
                .addToAnnotations(AnnotationNames.CONTROLLER_IMAGE.getName(), "test/my-capability-controller").endMetadata().build()
        );
        LogInterceptor.listenToClass(EntandoResourceObserver.class);
        LogInterceptor.listenToClass(EntandoControllerCoordinator.class);

    }

    @Test
    @Description("Should run existing resources when starting up the ControllerCoordinator")
    void testExistingResourcesProcessed() {
        step("Given the Coordinator observes this namespace", () ->
                System.setProperty(ControllerCoordinatorProperty.ENTANDO_NAMESPACES_TO_OBSERVE.getJvmSystemProperty(), OBSERVED_NAMESPACE));
        final ValueHolder<SerializedEntandoResource> testResource = new ValueHolder<>();
        step("And I have created an TestResource resource", () ->
                testResource.set(createTestResource(1L, Collections.emptyMap())));
        step("When I strartup the ControllerCoordinator", () ->
                coordinator.onStartup(new StartupEvent()));
        step("Then a controller pod gets created for the existing resource", () -> {
            await().atMost(3, TimeUnit.SECONDS)
                    .until(() -> clientDouble.loadPod(CONTROLLER_NAMESPACE, labelsFromResource(testResource.get())) != null);
            attachment("Controller Pod",
                    objectMapper.writeValueAsString(clientDouble.loadPod(CONTROLLER_NAMESPACE, labelsFromResource(testResource.get()))));

        });
    }

    @Test
    @Description("Resource modification events should be ignored when Kubernetes it twice with the same resource version")
    void testDuplicatesIgnored() {
        step("Given the Coordinator observes this namespace", () -> {
            System.setProperty(ControllerCoordinatorProperty.ENTANDO_NAMESPACES_TO_OBSERVE.getJvmSystemProperty(), OBSERVED_NAMESPACE);
            coordinator.onStartup(new StartupEvent());
        });
        final ValueHolder<SerializedEntandoResource> testResource = new ValueHolder<>();
        step("And I have created an TestResource resource", () -> {
            testResource.set(createTestResource(1L, Collections.emptyMap()));
            attachment("TestResource", objectMapper.writeValueAsString(testResource.get()));
        });
        final ValueHolder<Pod> oldPod = new ValueHolder<>();
        step("And its controller pod has been created", () -> {
            await().ignoreExceptions().atMost(3, TimeUnit.SECONDS)
                    .until(() -> clientDouble.loadPod(CONTROLLER_NAMESPACE, labelsFromResource(testResource.get())) != null);
            oldPod.set(clientDouble.loadPod(CONTROLLER_NAMESPACE, labelsFromResource(testResource.get())));
            attachment("Controller Pod", objectMapper.writeValueAsString(oldPod.get()));
        });
        step("When a duplicate event is fired", () -> {
            //NB we have to invoke the observer directly as our test double doesn't generate duplicate events
            coordinator.getObserver(CustomResourceDefinitionContext.fromCustomResourceType(TestResource.class))
                    .eventReceived(Action.ADDED, testResource.get());
            coordinator.shutdownObservers(10, TimeUnit.SECONDS);
            attachment("Duplicate Resource", objectMapper.writeValueAsString(testResource.get()));
        });
        step("Then no new pod gets created", () -> {
            //NB This assertion assumes the ClientDouble is storing the objects without cloning/serialization
            assertThat(clientDouble.loadPod(CONTROLLER_NAMESPACE, labelsFromResource(testResource.get()))).isSameAs(oldPod.get());
        });
        step("And the duplicate event was logged", () -> {
            final Optional<String> logEntry = LogInterceptor.getLogEntries().stream()
                    .filter(s -> s.startsWith("Duplicate event")).findFirst();
            assertThat(logEntry).isPresent();
        });
    }

    @Test
    @Description("Resource modification events should be ignored when the resource carries the annotation 'entando"
            + ".org/processing-instruction=ignore'")
    void testIgnoreInstruction() {
        step("Given e Coordinator observes this namespace", () ->
                System.setProperty(ControllerCoordinatorProperty.ENTANDO_NAMESPACES_TO_OBSERVE.getJvmSystemProperty(), OBSERVED_NAMESPACE));

        final ValueHolder<SerializedEntandoResource> testResource = new ValueHolder<>();
        step("And I have created an TestResource resource with the 'ignore' processing instruction", () -> {
            testResource.set(createTestResource(1L,
                    Collections.singletonMap(AnnotationNames.PROCESSING_INSTRUCTION.getName(), "ignore")));
            attachment("TestResource", objectMapper.writeValueAsString(testResource.get()));
        });
        step("When I start the ControllerCoordinator", () ->
                coordinator.onStartup(new StartupEvent()));
        step("Then no new pod gets created", () -> {
            coordinator.shutdownObservers(10, TimeUnit.SECONDS);
            assertThat(clientDouble.loadPod(CONTROLLER_NAMESPACE, labelsFromResource(testResource.get()))).isNull();
        });
        step("And the ignored event was logged", () -> {
            final Optional<String> logEntry = LogInterceptor.getLogEntries().stream()
                    .filter(s -> s.contains("has been deferred or ignored ")).findFirst();
            assertThat(logEntry).isPresent();
        });

    }

    @Test
    @Description("Resources should be ignored when they are owned by other resources of interest")
    void testIgnoredWhenOwnedByResourceOfInterest() {
        step("Given  the Coordinator observes this namespace", () ->
                System.setProperty(ControllerCoordinatorProperty.ENTANDO_NAMESPACES_TO_OBSERVE.getJvmSystemProperty(), OBSERVED_NAMESPACE));
        ValueHolder<SerializedEntandoResource> owningResource = new ValueHolder<>();
        step("And I have created another resource of interest, a ProvidedCapability", () -> {
            owningResource.set(clientDouble.createOrPatchEntandoResource(CoordinatorTestUtils.toSerializedResource(
                    new ProvidedCapabilityBuilder().withNewMetadata().withName("my-capability").withNamespace(OBSERVED_NAMESPACE)
                            .endMetadata()
                            .withNewSpec().withCapability(StandardCapability.DBMS).endSpec().build())));
            attachment("CapabilityResource", objectMapper.writeValueAsString(owningResource.get()));

        });
        ValueHolder<SerializedEntandoResource> testResource = new ValueHolder<>();
        step("And I have created an TestResource resource that is owned by another resource of interest", () -> {
            testResource.set(createTestResource(1L, Collections.emptyMap()));
            testResource.get().getMetadata().getOwnerReferences().add(ResourceUtils.buildOwnerReference(owningResource.get()));
            clientDouble.createOrPatchEntandoResource(testResource.get());
        });
        step("when I start the ControllerCoordinator", () ->
                coordinator.onStartup(new StartupEvent()));
        step("Then a new pod gets created for the owning resource", () -> {
            await().ignoreExceptions().atMost(10, SECONDS)
                    .until(() -> clientDouble.loadPod(CONTROLLER_NAMESPACE, labelsFromResource(owningResource.get())) != null);
            attachment("Controller Pod",
                    objectMapper.writeValueAsString(clientDouble.loadPod(CONTROLLER_NAMESPACE, labelsFromResource(owningResource.get()))));
        });
        step("Then no new pod gets created for the owned resource", () -> {
            assertThat(clientDouble.loadPod(CONTROLLER_NAMESPACE, labelsFromResource(testResource.get()))).isNull();
        });
        step("And the ignored event was logged", () -> {
            final Optional<String> logEntry = LogInterceptor.getLogEntries().stream()
                    .filter(s -> s
                            .contains("is ignored because it is not a top level resource"))
                    .findFirst();
            assertThat(logEntry).isPresent();
        });
    }

    @Test
    @Description("Resource modification events should be ignored when the 'generation' property on the metadata of the resource is the "
            + "same as the 'observedGeneration' property on its status")
    void testGenerationObservedIsSameAsCurrent() {
        step("Given the Coordinator observes this namespace", () ->
                System.setProperty(ControllerCoordinatorProperty.ENTANDO_NAMESPACES_TO_OBSERVE.getJvmSystemProperty(), OBSERVED_NAMESPACE));
        final ValueHolder<SerializedEntandoResource> testResource = new ValueHolder<>();
        step("And I have created an TestResource resource", () -> {
            testResource.set(createTestResource(10L, Collections.emptyMap()));
            attachment("TestResource", objectMapper.writeValueAsString(testResource.get()));
        });
        step("But the generation in the metadata is the same as the observedGeneration in the status", () -> {
            //NB This step assumes that the ClientDouble is holding this instance of the resource
            testResource.get().getStatus().updateDeploymentPhase(EntandoDeploymentPhase.STARTED, 10L);
            attachment("TestResource", objectMapper.writeValueAsString(testResource.get()));
        });
        step("When I start the ControllerCoordinator", () ->
                coordinator.onStartup(new StartupEvent()));
        step("Then no new pod gets created", () -> {
            coordinator.shutdownObservers(10, TimeUnit.SECONDS);
            assertThat(clientDouble.loadPod(CONTROLLER_NAMESPACE, labelsFromResource(testResource.get()))).isNull();
        });
        step("And the ignored event was logged", () -> {
            final Optional<String> logEntry = LogInterceptor.getLogEntries().stream()
                    .filter(s -> s
                            .contains("was ignored because its metadata.generation is still the same as the status.observedGeneration"))
                    .findFirst();
            assertThat(logEntry).isPresent();
        });

    }

    @Test
    @Description(
            "Resource modification events should be processed when using the annotation 'entando.org/processing-instruction=force' even "
                    + "when the 'generation' property on the metadata of the resource is the same as the 'observedGeneration' property on"
                    + " its status")
    void testGenerationObservedIsCurrentButForceInstructed() {
        step("Given the Coordinator observes this namespace", () ->
                System.setProperty(ControllerCoordinatorProperty.ENTANDO_NAMESPACES_TO_OBSERVE.getJvmSystemProperty(), OBSERVED_NAMESPACE));
        final ValueHolder<SerializedEntandoResource> testResource = new ValueHolder<>();

        step("And I have created an TestResource resource with the 'force' processing instruction", () -> {
            testResource.set(createTestResource(10L, Collections.singletonMap(AnnotationNames.PROCESSING_INSTRUCTION.getName(), "force")));
            attachment("TestResource", objectMapper.writeValueAsString(testResource.get()));
        });
        step("And the generation in the metadata is the same as the observedGeneration in the status", () -> {
            //NB This step assumes that the ClientDouble is holding this exact instance of the resource when the ControllerCoordinator
            // starts
            testResource.get().getStatus().updateDeploymentPhase(EntandoDeploymentPhase.STARTED, 10L);
            attachment("TestResource", objectMapper.writeValueAsString(testResource.get()));
        });
        step("When I start the ControllerCoordinator", () ->
                coordinator.onStartup(new StartupEvent()));
        step("Then a new pod gets created", () -> {
            coordinator.getObserver(CustomResourceDefinitionContext.fromCustomResourceType(TestResource.class))
                    .shutDownAndWait(1, SECONDS);
            await().ignoreExceptions().atMost(3, TimeUnit.SECONDS)
                    .until(() -> {
                        final Map<String, String> labels = labelsFromResource(testResource.get());
                        return clientDouble.loadPod(CONTROLLER_NAMESPACE, labels) != null;
                    });
        });
        step("And the 'force' processing instruction has been removed to avoid recursive processing", () -> {
            final SerializedEntandoResource latestKeycloakServer = clientDouble
                    .load(TestResource.class, testResource.get().getMetadata().getNamespace(),
                            testResource.get().getMetadata().getName());
            assertThat(CoordinatorUtils.resolveProcessingInstruction(latestKeycloakServer)).isEqualTo(OperatorProcessingInstruction.NONE);
            attachment("TestResource", objectMapper.writeValueAsString(testResource.get()));
        });
        step("And the forced event was logged", () -> {
            final Optional<String> logEntry = LogInterceptor.getLogEntries().stream()
                    .filter(s -> s.contains("has been forced"))
                    .findFirst();
            assertThat(logEntry).isPresent();
        });
    }

    @Test
    @Description("Resource modification events should be processed when the 'generation' property on the metadata of the "
            + "resource is higher than the 'observedGeneration' property on its status")
    void testGenerationObservedIsBehind() {
        step("Given Ihe Coordinator observes this namespace", () ->
                System.setProperty(ControllerCoordinatorProperty.ENTANDO_NAMESPACES_TO_OBSERVE.getJvmSystemProperty(), OBSERVED_NAMESPACE));
        final ValueHolder<SerializedEntandoResource> testResource = new ValueHolder<>();
        step("And I have created a TestResource resource", () -> {
            testResource.set(createTestResource(10L, Collections.emptyMap()));
            attachment("TestResource", objectMapper.writeValueAsString(testResource.get()));
        });
        step("But the  observedGeneration in the status is behind generation in the metadata", () -> {
            //NB This step assumes that the ClientDouble is holding this exact instance of the resource when the ControllerCoordinator
            // starts
            testResource.get().getStatus().updateDeploymentPhase(EntandoDeploymentPhase.SUCCESSFUL, 9L);
        });
        step("When I start the ControllerCoordinator", () ->
                coordinator.onStartup(new StartupEvent()));
        step("Then a new controller pod gets created", () -> {
            coordinator.shutdownObservers(10, TimeUnit.SECONDS);
            await().ignoreExceptions().atMost(3, TimeUnit.SECONDS).until(() ->
                    clientDouble.loadPod(CONTROLLER_NAMESPACE, labelsFromResource(testResource.get())) != null);
        });
        step("And the generation increment event was logged", () -> {
            final Optional<String> logEntry = LogInterceptor.getLogEntries().stream()
                    .filter(s -> s.contains("is processed after a metadata.generation increment"))
                    .findFirst();
            assertThat(logEntry).isPresent();
        });
    }

    @Test
    @Description("When a resource is processed successfully, the annotation 'entando.org/processed-by-version' should reflect the current"
            + " version of the operator")
    void testProcessedByVersion() {
        step("Given the Coordinator observes this namespace", () ->
                System.setProperty(ControllerCoordinatorProperty.ENTANDO_NAMESPACES_TO_OBSERVE.getJvmSystemProperty(), OBSERVED_NAMESPACE));
        step("And the current version of the operator is 6.3.1", () ->
                System.setProperty(ControllerCoordinatorProperty.ENTANDO_K8S_OPERATOR_VERSION.getJvmSystemProperty(), "6.3.1"));
        final ValueHolder<SerializedEntandoResource> testResource = new ValueHolder<>();
        step("And I have created an TestResource resource with the version annotation set to 6.3.0", () -> {
            testResource.set(createTestResource(10L,
                    Collections.singletonMap(AnnotationNames.PROCESSED_BY_OPERATOR_VERSION.getName(), "6.3.0")));
            attachment("TestResource", objectMapper.writeValueAsString(testResource.get()));
        });
        step("And I have start the ControllerCoordinator", () ->
                coordinator.onStartup(new StartupEvent()));
        step("And a new controller pod was created", () -> {
            await().ignoreExceptions().atMost(3, TimeUnit.SECONDS).until(() ->
                    clientDouble.loadPod(CONTROLLER_NAMESPACE, labelsFromResource(testResource.get())) != null);
            attachment("Controller Pod",
                    objectMapper.writeValueAsString(clientDouble.loadPod(CONTROLLER_NAMESPACE, labelsFromResource(testResource.get()))));
        });
        step("When I update the deployment Phase of the resource to 'successful'", () -> {
            SerializedEntandoResource latestKeycloakServer = clientDouble
                    .updatePhase(testResource.get(), EntandoDeploymentPhase.SUCCESSFUL);
            attachment("Successful Resource", objectMapper.writeValueAsString(latestKeycloakServer));
        });
        step("Then  the TestResource has been annotated with the version 6.3.1", () -> {
            coordinator.shutdownObservers(10, TimeUnit.SECONDS);
            SerializedEntandoResource latestKeycloakServer = clientDouble.load(TestResource.class,
                    testResource.get().getMetadata().getNamespace(),
                    testResource.get().getMetadata().getName());
            assertThat(CoordinatorUtils.resolveAnnotation(latestKeycloakServer, AnnotationNames.PROCESSED_BY_OPERATOR_VERSION))
                    .contains("6.3.1");
        });
    }

    @Test
    @Description("A resource should be processed again when the ControllerCoordinator starts up if it was processed with a version of the"
            + " Operator that is now being replaced")
    void testUpgrade() {
        step("Given the Coordinator observes this namespace", () ->
                System.setProperty(ControllerCoordinatorProperty.ENTANDO_NAMESPACES_TO_OBSERVE.getJvmSystemProperty(), OBSERVED_NAMESPACE));
        step("And the current version of the operator is 6.3.1", () ->
                System.setProperty(ControllerCoordinatorProperty.ENTANDO_K8S_OPERATOR_VERSION.getJvmSystemProperty(), "6.3.1"));
        step("And the version of the operator to replace was 6.3.0", () ->
                System.setProperty(ControllerCoordinatorProperty.ENTANDO_K8S_OPERATOR_VERSION_TO_REPLACE.getJvmSystemProperty(),
                        "6.3.0"));
        final ValueHolder<SerializedEntandoResource> testResource = new ValueHolder<>();
        step("And I have created an TestResource resource that was processed by version 6.3.0", () ->
                testResource.set(createTestResource(10L,
                        Collections.singletonMap(AnnotationNames.PROCESSED_BY_OPERATOR_VERSION.getName(), "6.3.0"))));
        step("And it has been successfully deployed previously", () -> {
            testResource.set(clientDouble.updatePhase(testResource.get(), EntandoDeploymentPhase.SUCCESSFUL));
            attachment("Processed Resource", objectMapper.writeValueAsString(testResource.get()));
        });
        step("When I start the ControllerCoordinator", () ->
                coordinator.onStartup(new StartupEvent()));

        step("Then a new controller pod is created", () -> {
            await().ignoreExceptions().atMost(3, TimeUnit.SECONDS).until(() ->
                    clientDouble.loadPod(CONTROLLER_NAMESPACE, labelsFromResource(testResource.get())) != null);
            attachment("Controller Pod",
                    objectMapper.writeValueAsString(clientDouble.loadPod(CONTROLLER_NAMESPACE, labelsFromResource(testResource.get()))));
        });
        step("And the resource is marked as having been processed by version 6.3.1 when its deployment succeeds", () -> {
            clientDouble.updatePhase(testResource.get(), EntandoDeploymentPhase.SUCCESSFUL);
            SerializedEntandoResource latestKeycloakServer = clientDouble.load(TestResource.class,
                    testResource.get().getMetadata().getNamespace(),
                    testResource.get().getMetadata().getName());
            assertThat(
                    CoordinatorUtils.resolveAnnotation(latestKeycloakServer, AnnotationNames.PROCESSED_BY_OPERATOR_VERSION))
                    .contains("6.3.1");
        });
        step("And the upgrade event was logged", () -> {
            final Optional<String> logEntry = LogInterceptor.getLogEntries().stream()
                    .filter(s -> s.contains("needs to be processed as part of the upgrade to the version"))
                    .findFirst();
            assertThat(logEntry).isPresent();
        });
    }

    protected SerializedEntandoResource createTestResource(Long generation, Map<String, String> annotations)
            throws JsonProcessingException {
        TestResource keycloakServer = new TestResource().withSpec(new BasicDeploymentSpecBuilder().withDbms(DbmsVendor.MYSQL).build());
        keycloakServer.setMetadata(
                new ObjectMetaBuilder()
                        .withGeneration(generation)
                        .withUid(RandomStringUtils.randomAlphanumeric(12))
                        .withName("test-keycloak")
                        .withNamespace(OBSERVED_NAMESPACE)
                        .withAnnotations(annotations)
                        .build());
        final SerializedEntandoResource serializedResource = CoordinatorTestUtils.toSerializedResource(keycloakServer);
        serializedResource.setDefinition(CustomResourceDefinitionContext.fromCustomResourceType(TestResource.class));
        final SerializedEntandoResource createdResource = clientDouble
                .createOrPatchEntandoResource(serializedResource);
        attachment("TestResource", objectMapper.writeValueAsString(createdResource));
        return createdResource;
    }

}
