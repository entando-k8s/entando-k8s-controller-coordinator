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
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.apiextensions.v1beta1.CustomResourceDefinition;
import io.fabric8.kubernetes.api.model.apiextensions.v1beta1.CustomResourceDefinitionBuilder;
import io.fabric8.kubernetes.api.model.apiextensions.v1beta1.CustomResourceDefinitionFluent.MetadataNested;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import io.qameta.allure.Description;
import io.qameta.allure.Feature;
import io.qameta.allure.Issue;
import io.quarkus.runtime.StartupEvent;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.assertj.core.api.Condition;
import org.entando.kubernetes.controller.coordinator.common.CoordinatorTestUtils;
import org.entando.kubernetes.controller.coordinator.common.SimpleKubernetesClientDouble;
import org.entando.kubernetes.controller.spi.client.SerializedEntandoResource;
import org.entando.kubernetes.controller.spi.common.LabelNames;
import org.entando.kubernetes.controller.support.client.doubles.AbstractK8SClientDouble;
import org.entando.kubernetes.model.capability.ProvidedCapability;
import org.entando.kubernetes.model.capability.ProvidedCapabilityBuilder;
import org.entando.kubernetes.model.capability.StandardCapability;
import org.entando.kubernetes.model.capability.StandardCapabilityImplementation;
import org.entando.kubernetes.model.common.EntandoDeploymentPhase;
import org.entando.kubernetes.test.common.ValueHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Tags;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@Tags({@Tag("component"), @Tag("in-process"), @Tag("allure"), @Tag("pre-deployment")})
@Feature("As a controller developer, I want the EntandoOperator to resolve my controller image from my CRD annotations but with the "
        + "flexibility to override it at runtime so that I can easily a deploy and release my controller images")
@Issue("ENG-2284")
class CrdManagementTest {

    public static final String MY_NAMESPACE = "my-namespace";
    final SimpleKubernetesClientDouble client = new SimpleKubernetesClientDouble();
    final EntandoControllerCoordinator entandoControllerCoordinator = new EntandoControllerCoordinator(client);

    @AfterEach
    void clearSystemProperties() {
        System.clearProperty(ControllerCoordinatorProperty.ENTANDO_STORE_LOG_ENTRIES.getJvmSystemProperty());
        entandoControllerCoordinator.shutdownObservers(30, TimeUnit.SECONDS);
    }

    @BeforeEach
    void prepareSystemProperties() {
        //a bit aggressive
        Arrays.stream(ControllerCoordinatorProperty.values()).forEach(p -> System.clearProperty(p.getJvmSystemProperty()));
        System.setProperty(ControllerCoordinatorProperty.ENTANDO_STORE_LOG_ENTRIES.getJvmSystemProperty(), "true");
    }

    @Test
    @Description("All information related to my custom resource, the capabilities it supports and its controller images should be extracted"
            + " from my CustomResourceDefinition's annotations")
    void testCrdRegistration() throws IOException {
        step("Given I have started the Entando Operator", () -> entandoControllerCoordinator.onStartup(new StartupEvent()));
        ObjectMapper objectMapper = new ObjectMapper(new YAMLFactory());
        final CustomResourceDefinition value = objectMapper
                .readValue(Thread.currentThread().getContextClassLoader().getResource("mycrds.test.org.crd.yaml"),
                        CustomResourceDefinition.class);
        final MetadataNested<CustomResourceDefinitionBuilder> builder = new CustomResourceDefinitionBuilder(value)
                .editMetadata();
        step("And I have a CustomResourceDefinition with", () -> {
            step(format("the %s label ", LabelNames.CRD_OF_INTEREST.getName()), () -> {
                builder.addToLabels(LabelNames.CRD_OF_INTEREST.getName(), "MyCRD");

            });
            step(format("and the %s annotation ", AnnotationNames.CONTROLLER_IMAGE), () -> {
                builder.addToAnnotations(AnnotationNames.CONTROLLER_IMAGE.getName(), "test/my-controller");
            });
            attachment("CustomResourceDefinition", objectMapper.writeValueAsString(builder.endMetadata().build()));

        });
        step("When I register my CustomResourceDefinition", () -> {
            final CustomResourceDefinition crd = client.getCluster().putCustomResourceDefinition(builder.endMetadata().build());
            attachment("CustomResourceDefinition", objectMapper.writeValueAsString(crd));
        });

        step("Then my custom resource definition is mapped in the " + CoordinatorUtils.ENTANDO_CRD_NAMES_CONFIGMAP_NAME + " ConfigMap",
                () -> {
                    assertThat(client.findOrCreateControllerConfigMap(CoordinatorUtils.ENTANDO_CRD_NAMES_CONFIGMAP_NAME).getData())
                            .containsEntry("MyCRD.test.org", "mycrds.test.org");
                });
        step("Then my controller image is registered in the EntandoControllerCoordinator", () -> {
            final SerializedEntandoResource resource = new SerializedEntandoResource();
            resource.setDefinition(CustomResourceDefinitionContext.fromCrd(builder.endMetadata().build()));
            assertThat(entandoControllerCoordinator.getControllerImageFor(resource)).isEqualTo("test/my-controller");
        });

    }

    @Test
    @Description("New instances of my CustomResources should result in my controller image to be executed against the resource")
    void testCustomResourceEvent() throws IOException {
        step("Given I have prepared a cluster scoped deployment of the EntandoOperator",
                () -> System.setProperty(ControllerCoordinatorProperty.ENTANDO_NAMESPACES_TO_OBSERVE
                        .getJvmSystemProperty(), "*"));
        step("And I have started the Entando Operator", () -> entandoControllerCoordinator.onStartup(new StartupEvent()));
        ObjectMapper objectMapper = new ObjectMapper(new YAMLFactory());
        final CustomResourceDefinition value = objectMapper
                .readValue(Thread.currentThread().getContextClassLoader().getResource("mycrds.test.org.crd.yaml"),
                        CustomResourceDefinition.class);
        final MetadataNested<CustomResourceDefinitionBuilder> builder = new CustomResourceDefinitionBuilder(value)
                .editMetadata();
        step("And I have a CustomResourceDefinition with", () -> {
            step(format("the %s label ", LabelNames.CRD_OF_INTEREST.getName()), () -> {
                builder.addToLabels(LabelNames.CRD_OF_INTEREST.getName(), "MyCRD");

            });
            step(format("and the %s annotation ", AnnotationNames.CONTROLLER_IMAGE.getName()), () -> {
                builder.addToAnnotations(AnnotationNames.CONTROLLER_IMAGE.getName(), "test/my-controller");
            });
        });
        step("And I have registered my custom resource definition", () -> {
            client.getCluster().putCustomResourceDefinition(builder.endMetadata().build());
        });
        final SerializedEntandoResource resource = new SerializedEntandoResource();
        resource.setMetadata(new ObjectMetaBuilder().withName("my-resource").withNamespace(MY_NAMESPACE).build());
        resource.setDefinition(CustomResourceDefinitionContext.fromCrd(builder.endMetadata().build()));
        step("When I create a new custom resource based on my CustomResourceDefinition my controller image is used to execute a "
                        + "Controller pod that runs to completion",
                () -> client.createOrPatchEntandoResource(resource));
        step(format("Then a controller pod has been created with the image that I specified in the %s annotation",
                AnnotationNames.CONTROLLER_IMAGE.getName()),
                () -> {
                    await().atMost(10, TimeUnit.SECONDS).ignoreExceptions().until(() ->
                            client.loadPod(AbstractK8SClientDouble.CONTROLLER_NAMESPACE, CoordinatorUtils.podLabelsFor(resource)) != null);
                    assertThat(
                            client.loadPod(AbstractK8SClientDouble.CONTROLLER_NAMESPACE, CoordinatorUtils.podLabelsFor(resource)).getSpec()
                                    .getContainers().get(0).getImage())
                            .contains("test/my-controller");
                });

    }

    @Test
    @Description("Updates to my CustomResourceDefinitions should result in their observers being restarted")
    void crdUpdatesShouldRestartObservers() throws IOException {
        step("Given I have started the Entando Operator", () -> entandoControllerCoordinator.onStartup(new StartupEvent()));
        ObjectMapper objectMapper = new ObjectMapper(new YAMLFactory());
        final ValueHolder<EntandoResourceObserver> observer = new ValueHolder<>();
        final ValueHolder<CustomResourceDefinition> crd = new ValueHolder<>();
        crd.set(objectMapper
                .readValue(Thread.currentThread().getContextClassLoader().getResource("mycrds.test.org.crd.yaml"),
                        CustomResourceDefinition.class));
        final MetadataNested<CustomResourceDefinitionBuilder> builder = new CustomResourceDefinitionBuilder(crd.get())
                .editMetadata();
        step("And I have registered a CustomResourceDefinition with", () -> {
            step(format("the %s label ", LabelNames.CRD_OF_INTEREST.getName()), () -> {
                builder.addToLabels(LabelNames.CRD_OF_INTEREST.getName(), "MyCRD");

            });
            step(format("and the %s annotation ", AnnotationNames.CONTROLLER_IMAGE), () -> {
                builder.addToAnnotations(AnnotationNames.CONTROLLER_IMAGE.getName(), "test/my-controller");
            });
            step("and I have registered my CustomResourceDefinition", () -> {
                crd.set(client.getCluster().putCustomResourceDefinition(builder.withGeneration(1L).endMetadata().build()));
            });
            attachment("CustomResourceDefinition", objectMapper.writeValueAsString(crd.get()));
        });
        step("And I have waited for the Operator to start observing state changes against the related CustomResources", () -> {
            await().atMost(10, TimeUnit.SECONDS).ignoreExceptions()
                    .until(() ->
                            entandoControllerCoordinator.getObserver(CustomResourceDefinitionContext.fromCrd(crd.get())).getCrdGeneration()
                                    == 1L);
            observer.set(entandoControllerCoordinator.getObserver(CustomResourceDefinitionContext.fromCrd(crd.get())));
        });
        step("When I apply an updated version of my CustomResourceDefinition ", () -> {
            crd.get().getMetadata().setGeneration(2L);
            client.getCluster().putCustomResourceDefinition(crd.get());
        });
        step("Then the Operator has started a new state change observer against the CustomResources", () -> {
            await().atMost(10, TimeUnit.SECONDS).ignoreExceptions()
                    .until(() ->
                            entandoControllerCoordinator.getObserver(CustomResourceDefinitionContext.fromCrd(crd.get()))
                                    .getCrdGeneration()
                                    == 2L);
            assertThat(entandoControllerCoordinator.getObserver(CustomResourceDefinitionContext.fromCrd(crd.get())))
                    .isNotSameAs(observer.get());
        });
    }

    @Test
    @Description(
            "The operator should automatically mark new instances of my CustomResourceDefinitions to 'successful' when I don't have "
                    + "a controller image for it (yet)")
    void testCustomResourceEventWithNoControllerImage() throws IOException {
        step("Given I have prepared a cluster scoped deployment of the EntandoOperator",
                () -> System.setProperty(ControllerCoordinatorProperty.ENTANDO_NAMESPACES_TO_OBSERVE
                        .getJvmSystemProperty(), "*"));
        step("And I have started the Entando Operator", () -> entandoControllerCoordinator.onStartup(new StartupEvent()));
        ObjectMapper objectMapper = new ObjectMapper(new YAMLFactory());
        final CustomResourceDefinition value = objectMapper
                .readValue(Thread.currentThread().getContextClassLoader().getResource("mycrds.test.org.crd.yaml"),
                        CustomResourceDefinition.class);
        final MetadataNested<CustomResourceDefinitionBuilder> builder = new CustomResourceDefinitionBuilder(value)
                .editMetadata();
        step("And I have a CustomResourceDefinition with", () -> {
            step(format("the %s label ", LabelNames.CRD_OF_INTEREST.getName()), () -> {
                builder.addToLabels(LabelNames.CRD_OF_INTEREST.getName(), "MyCRD");

            });
            step(format("but no %s annotation ", AnnotationNames.CONTROLLER_IMAGE.getName()), () -> {
            });
        });
        step("And I have registered my custom resource definition", () -> {
            client.getCluster().putCustomResourceDefinition(builder.endMetadata().build());
        });
        final SerializedEntandoResource resource = new SerializedEntandoResource();
        resource.setMetadata(new ObjectMetaBuilder().withName("my-resource").withNamespace(MY_NAMESPACE).build());
        resource.setDefinition(CustomResourceDefinitionContext.fromCrd(builder.endMetadata().build()));
        step("When I create a new custom resource based on my CustomResourceDefinition my controller image is used to execute a "
                        + "Controller pod that runs to completion",
                () -> client.createOrPatchEntandoResource(resource));
        step("The phase on the resource status was updated to 'successful", () -> {
            await().atMost(3, TimeUnit.SECONDS).ignoreExceptions().until(() -> client.reload(resource).getStatus().getPhase().equals(
                    EntandoDeploymentPhase.SUCCESSFUL));
        });
        step("But no controller pod has been created", () -> assertThat(
                client.loadPod(AbstractK8SClientDouble.CONTROLLER_NAMESPACE, CoordinatorUtils.podLabelsFor(resource))).isNull());
        Condition<? super List<? extends String>> asfd;
        step("And the fact that no image was found was logged",
                () -> assertThat(LogDelegator.getLogEntries().stream().anyMatch(s -> s.contains(
                        "has neither the entando.org/controller-image annotation, nor is there an entry in the configmap "
                                + "entando-controller-image-overrides"))))
                .isTrue();

    }

    @Test
    @Description("Capabilities that my controller image supports should also result in my controller image to be executed against the "
            + "ProvidedCapability")
    void testCapabilityEvent() throws IOException {
        step("Given I prepared a namespace scoped deployment of the EntandoOperator",
                () -> System.setProperty(ControllerCoordinatorProperty.ENTANDO_NAMESPACES_TO_OBSERVE
                        .getJvmSystemProperty(), MY_NAMESPACE));
        step("Given I have started the Entando Operator", () -> entandoControllerCoordinator.onStartup(new StartupEvent()));
        ObjectMapper objectMapper = new ObjectMapper(new YAMLFactory());
        final CustomResourceDefinition value = objectMapper
                .readValue(Thread.currentThread().getContextClassLoader().getResource("mycrds.test.org.crd.yaml"),
                        CustomResourceDefinition.class);
        final MetadataNested<CustomResourceDefinitionBuilder> builder = new CustomResourceDefinitionBuilder(value)
                .editMetadata();
        step("And I have a CustomResourceDefinition with", () -> {
            step(format("the %s label ", LabelNames.CRD_OF_INTEREST.getName()), () -> {
                builder.addToLabels(LabelNames.CRD_OF_INTEREST.getName(), "MyCRD");

            });
            step(format("and the %s annotation ", AnnotationNames.CONTROLLER_IMAGE.getName()), () -> {
                builder.addToAnnotations(AnnotationNames.CONTROLLER_IMAGE.getName(), "test/my-controller");
            });
            step(format("and the %s annotation ", AnnotationNames.SUPPORTED_CAPABILITIES), () -> {
                builder.addToAnnotations(AnnotationNames.SUPPORTED_CAPABILITIES.getName(), "mysql.dbms");
            });
        });
        step("And I have registered my custom resource definition", () -> {
            client.getCluster().putCustomResourceDefinition(builder.endMetadata().build());
        });
        final ProvidedCapability providedCapability = new ProvidedCapabilityBuilder()
                .withNewMetadata()
                .withName("my-capability")
                .withNamespace(MY_NAMESPACE)
                .endMetadata()
                .withNewSpec()
                .withCapability(StandardCapability.DBMS)
                .withImplementation(StandardCapabilityImplementation.MYSQL)
                .endSpec()
                .build();
        step("When I create a new ProvidedCapability requiring the capability associated with my CustomResourceDefinition ",
                () -> client.createOrPatchEntandoResource(CoordinatorTestUtils.toSerializedResource(providedCapability)));
        step(format("Then a controller pod has been created with the image that I specified in the %s annotation",
                AnnotationNames.CONTROLLER_IMAGE.getName()),
                () -> {
                    await().atMost(10, TimeUnit.SECONDS).ignoreExceptions().until(() ->
                            client.loadPod(AbstractK8SClientDouble.CONTROLLER_NAMESPACE,
                                    CoordinatorUtils.podLabelsFor(providedCapability))
                                    != null);
                    assertThat(
                            client.loadPod(AbstractK8SClientDouble.CONTROLLER_NAMESPACE,
                                    CoordinatorUtils.podLabelsFor(providedCapability))
                                    .getSpec()
                                    .getContainers().get(0).getImage())
                            .contains("test/my-controller");
                });

    }

    @Test
    @Description("New instances of my CustomResourceDefinitions should result in the image specified in the "
            + CoordinatorUtils.CONTROLLER_IMAGE_OVERRIDES_CONFIGMAP
            + " ConfigMap to be executed against the resource rather than the one on my CRD's annotation")
    void testCustomResourceEventWithControllerImageOverride() throws IOException {
        step("Given I have prepared a cluster scoped deployment of the EntandoOperator",
                () -> System.setProperty(ControllerCoordinatorProperty.ENTANDO_NAMESPACES_TO_OBSERVE
                        .getJvmSystemProperty(), "*"));
        step("And I have started the Entando Operator", () -> entandoControllerCoordinator.onStartup(new StartupEvent()));
        ObjectMapper objectMapper = new ObjectMapper(new YAMLFactory());
        final CustomResourceDefinition value = objectMapper
                .readValue(Thread.currentThread().getContextClassLoader().getResource("mycrds.test.org.crd.yaml"),
                        CustomResourceDefinition.class);
        final MetadataNested<CustomResourceDefinitionBuilder> builder = new CustomResourceDefinitionBuilder(value)
                .editMetadata();
        step("And I have a CustomResourceDefinition with", () -> {
            step(format("the %s label ", LabelNames.CRD_OF_INTEREST.getName()), () -> {
                builder.addToLabels(LabelNames.CRD_OF_INTEREST.getName(), "MyCRD");

            });
            step(format("and the %s annotation ", AnnotationNames.CONTROLLER_IMAGE.getName()), () -> {
                builder.addToAnnotations(AnnotationNames.CONTROLLER_IMAGE.getName(), "test/my-controller");
            });
        });
        step("And I have registered my custom resource definition", () -> {
            client.getCluster().putCustomResourceDefinition(builder.endMetadata().build());
        });
        step(format("But I have overridden the image to 'test/image-override' in the %s ConfigMap",
                CoordinatorUtils.CONTROLLER_IMAGE_OVERRIDES_CONFIGMAP),
                () -> {
                    client.patchControllerConfigMap(
                            new ConfigMapBuilder(
                                    client.findOrCreateControllerConfigMap(CoordinatorUtils.CONTROLLER_IMAGE_OVERRIDES_CONFIGMAP))
                                    .addToData("MyCRD.test.org", "test/image-override").build());
                });
        final SerializedEntandoResource resource = new SerializedEntandoResource();
        resource.setMetadata(new ObjectMetaBuilder().withName("my-resource").withNamespace(MY_NAMESPACE).build());
        resource.setDefinition(CustomResourceDefinitionContext.fromCrd(builder.endMetadata().build()));
        step("When I create a new custom resource based on my CustomResourceDefinition the overriding controller image is used to "
                        + "execute"
                        + " a "
                        + "Controller pod that runs to completion",
                () -> client.createOrPatchEntandoResource(resource));
        step(format("Then a controller pod has been created with the image that I specified in the %s ConfigMap",
                CoordinatorUtils.CONTROLLER_IMAGE_OVERRIDES_CONFIGMAP),
                () -> {
                    await().atMost(10, TimeUnit.SECONDS).ignoreExceptions().until(() ->
                            client.loadPod(AbstractK8SClientDouble.CONTROLLER_NAMESPACE, CoordinatorUtils.podLabelsFor(resource))
                                    != null);
                    assertThat(
                            client.loadPod(AbstractK8SClientDouble.CONTROLLER_NAMESPACE, CoordinatorUtils.podLabelsFor(resource))
                                    .getSpec()
                                    .getContainers().get(0).getImage())
                            .contains("test/image-override");
                });

    }

    @Test
    @Description("Capabilities that my controller image supports should also result in the image specified in the "
            + CoordinatorUtils.CONTROLLER_IMAGE_OVERRIDES_CONFIGMAP + " ConfigMap to be executed against the ProvidedCapability")
    void testCapabilityEventWithControllerImageOverride() throws IOException {
        step("Given I prepared a namespace scoped deployment of the EntandoOperator",
                () -> System.setProperty(ControllerCoordinatorProperty.ENTANDO_NAMESPACES_TO_OBSERVE
                        .getJvmSystemProperty(), MY_NAMESPACE));
        step("Given I have started the Entando Operator", () -> entandoControllerCoordinator.onStartup(new StartupEvent()));
        ObjectMapper objectMapper = new ObjectMapper(new YAMLFactory());
        final CustomResourceDefinition value = objectMapper
                .readValue(Thread.currentThread().getContextClassLoader().getResource("mycrds.test.org.crd.yaml"),
                        CustomResourceDefinition.class);
        final MetadataNested<CustomResourceDefinitionBuilder> builder = new CustomResourceDefinitionBuilder(value)
                .editMetadata();

        step("And I have a CustomResourceDefinition with", () -> {
            step(format("the %s label ", LabelNames.CRD_OF_INTEREST.getName()), () -> {
                builder.addToLabels(LabelNames.CRD_OF_INTEREST.getName(), "MyCRD");

            });
            step(format("and the %s annotation ", AnnotationNames.CONTROLLER_IMAGE.getName()), () -> {
                builder.addToAnnotations(AnnotationNames.CONTROLLER_IMAGE.getName(), "test/my-controller");
            });
            step(format("and the %s annotation ", AnnotationNames.SUPPORTED_CAPABILITIES), () -> {
                builder.addToAnnotations(AnnotationNames.SUPPORTED_CAPABILITIES.getName(), "mysql.dbms");
            });
        });

        step("And I have registered my custom resource definition", () -> {
            client.getCluster().putCustomResourceDefinition(builder.endMetadata().build());
        });
        final ProvidedCapability providedCapability = new ProvidedCapabilityBuilder()
                .withNewMetadata()
                .withName("my-capability")
                .withNamespace(MY_NAMESPACE)
                .endMetadata()
                .withNewSpec()
                .withCapability(StandardCapability.DBMS)
                .withImplementation(StandardCapabilityImplementation.MYSQL)
                .endSpec()
                .build();
        step(format("But I have overridden the DBMS capability's image to 'test/image-override' in the %s ConfigMap",
                CoordinatorUtils.CONTROLLER_IMAGE_OVERRIDES_CONFIGMAP),
                () -> {
                    client.patchControllerConfigMap(
                            new ConfigMapBuilder(
                                    client.findOrCreateControllerConfigMap(CoordinatorUtils.CONTROLLER_IMAGE_OVERRIDES_CONFIGMAP))
                                    .addToData("dbms.capability.org", "test/image-override").build());
                });

        step("When I create a new ProvidedCapability requiring the capability associated with my CustomResourceDefinition ",
                () -> client.createOrPatchEntandoResource(CoordinatorTestUtils.toSerializedResource(providedCapability)));

        step(format("Then a controller pod has been created with the overriding image that I specified in the %s ConfigMap",
                CoordinatorUtils.CONTROLLER_IMAGE_OVERRIDES_CONFIGMAP),
                () -> {
                    await().atMost(10, TimeUnit.SECONDS).ignoreExceptions().until(() ->
                            client.loadPod(AbstractK8SClientDouble.CONTROLLER_NAMESPACE,
                                    CoordinatorUtils.podLabelsFor(providedCapability))
                                    != null);
                    assertThat(
                            client.loadPod(AbstractK8SClientDouble.CONTROLLER_NAMESPACE,
                                    CoordinatorUtils.podLabelsFor(providedCapability))
                                    .getSpec()
                                    .getContainers().get(0).getImage())
                            .contains("test/image-override");
                });

    }
}
