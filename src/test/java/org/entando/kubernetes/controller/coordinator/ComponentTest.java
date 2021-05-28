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

import static io.qameta.allure.Allure.step;
import static java.lang.String.format;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.apiextensions.v1beta1.CustomResourceDefinition;
import io.fabric8.kubernetes.api.model.apiextensions.v1beta1.CustomResourceDefinitionBuilder;
import io.fabric8.kubernetes.api.model.apiextensions.v1beta1.CustomResourceDefinitionFluent.MetadataNested;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import io.qameta.allure.Feature;
import io.qameta.allure.Issue;
import io.quarkus.runtime.StartupEvent;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import org.entando.kubernetes.controller.coordinator.inprocesstests.CoordinatorTestUtil;
import org.entando.kubernetes.controller.coordinator.inprocesstests.SimpleKubernetesClientDouble;
import org.entando.kubernetes.controller.spi.client.SerializedEntandoResource;
import org.entando.kubernetes.controller.support.client.doubles.AbstractK8SClientDouble;
import org.entando.kubernetes.model.capability.ProvidedCapability;
import org.entando.kubernetes.model.capability.ProvidedCapabilityBuilder;
import org.entando.kubernetes.model.capability.StandardCapability;
import org.entando.kubernetes.model.capability.StandardCapabilityImplementation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Tags;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@Tags({@Tag("component"), @Tag("in-process"), @Tag("allure")})
@Feature("As a controller developer, when I register a CRD with the entando.org/crd-of-interest label, I want the EntandoOperator to "
        + "launch controller pods in response to state changes made against the custom resource")
@Issue("ENG-2284")
class ComponentTest {

    public static final String MY_NAMESPACE = "my-namespace";
    final SimpleKubernetesClientDouble client = new SimpleKubernetesClientDouble();
    final EntandoControllerCoordinator entandoControllerCoordinator = new EntandoControllerCoordinator(client);

    @Test
    void testCrdRegistration() throws IOException {
        step("Given I have started the Entando Operator", () -> entandoControllerCoordinator.onStartup(new StartupEvent()));
        ObjectMapper objectMapper = new ObjectMapper(new YAMLFactory());
        final CustomResourceDefinition value = objectMapper
                .readValue(Thread.currentThread().getContextClassLoader().getResource("mycrd.test.org.crd.yaml"),
                        CustomResourceDefinition.class);
        final MetadataNested<CustomResourceDefinitionBuilder> builder = new CustomResourceDefinitionBuilder(value)
                .editMetadata();
        step("And I have a CustomResourceDefinition with", () -> {
            step(format("the %s label ", CoordinatorUtils.ENTANDO_CRD_OF_INTEREST_LABEL_NAME), () -> {
                builder.addToLabels(CoordinatorUtils.ENTANDO_CRD_OF_INTEREST_LABEL_NAME, "MyCRD");

            });
            step(format("and the %s annotation ", CoordinatorUtils.CONTROLLER_IMAGE_ANNOTATION_NAME), () -> {
                builder.addToAnnotations(CoordinatorUtils.CONTROLLER_IMAGE_ANNOTATION_NAME, "test/my-controller");
            });
        });
        step("When I register my CustomResourceDefinition", () -> {
            client.getCluster().putCustomResourceDefinition(builder.endMetadata().build());
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

    @BeforeEach
    void clearSystemProperties() {
        Arrays.stream(ControllerCoordinatorProperty.values()).forEach(p -> System.clearProperty(p.getJvmSystemProperty()));

    }

    @Test
    void testCustomResourceEvent() throws IOException {
        step("Given I have prepared a cluster scoped deployment of the EntandoOperator",
                () -> System.setProperty(ControllerCoordinatorProperty.ENTANDO_NAMESPACES_TO_OBSERVE
                        .getJvmSystemProperty(), "*"));
        step("And I have started the Entando Operator", () -> entandoControllerCoordinator.onStartup(new StartupEvent()));
        ObjectMapper objectMapper = new ObjectMapper(new YAMLFactory());
        final CustomResourceDefinition value = objectMapper
                .readValue(Thread.currentThread().getContextClassLoader().getResource("mycrd.test.org.crd.yaml"),
                        CustomResourceDefinition.class);
        final MetadataNested<CustomResourceDefinitionBuilder> builder = new CustomResourceDefinitionBuilder(value)
                .editMetadata();
        step("And I have a CustomResourceDefinition with", () -> {
            step(format("the %s label ", CoordinatorUtils.ENTANDO_CRD_OF_INTEREST_LABEL_NAME), () -> {
                builder.addToLabels(CoordinatorUtils.ENTANDO_CRD_OF_INTEREST_LABEL_NAME, "MyCRD");

            });
            step(format("and the %s annotation ", CoordinatorUtils.CONTROLLER_IMAGE_ANNOTATION_NAME), () -> {
                builder.addToAnnotations(CoordinatorUtils.CONTROLLER_IMAGE_ANNOTATION_NAME, "test/my-controller");
            });
        });
        step("And I have registered my custom resource definition", () -> {
            client.getCluster().putCustomResourceDefinition(builder.endMetadata().build());
        });
        final SerializedEntandoResource resource = new SerializedEntandoResource();
        resource.setMetadata(new ObjectMetaBuilder().withName("my-resource").withNamespace(MY_NAMESPACE).build());
        resource.setDefinition(CustomResourceDefinitionContext.fromCrd(builder.endMetadata().build()));
        step("When I create a new custom resource based on my CustomResourceDefinition my controller image is used to execute a Controller pod that runs to completion",
                () -> client.createOrPatchEntandoResource(resource));
        step(format("Then a controller pod has been created with the image that I specified in the %s annotation",
                CoordinatorUtils.CONTROLLER_IMAGE_ANNOTATION_NAME),
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
    void testCapabilityEvent() throws IOException {
        step("Given I prepared a namespace scoped deployment of the EntandoOperator",
                () -> System.setProperty(ControllerCoordinatorProperty.ENTANDO_NAMESPACES_TO_OBSERVE
                        .getJvmSystemProperty(), MY_NAMESPACE));
        step("Given I have started the Entando Operator", () -> entandoControllerCoordinator.onStartup(new StartupEvent()));
        ObjectMapper objectMapper = new ObjectMapper(new YAMLFactory());
        final CustomResourceDefinition value = objectMapper
                .readValue(Thread.currentThread().getContextClassLoader().getResource("mycrd.test.org.crd.yaml"),
                        CustomResourceDefinition.class);
        final MetadataNested<CustomResourceDefinitionBuilder> builder = new CustomResourceDefinitionBuilder(value)
                .editMetadata();
        step("And I have a CustomResourceDefinition with", () -> {
            step(format("the %s label ", CoordinatorUtils.ENTANDO_CRD_OF_INTEREST_LABEL_NAME), () -> {
                builder.addToLabels(CoordinatorUtils.ENTANDO_CRD_OF_INTEREST_LABEL_NAME, "MyCRD");

            });
            step(format("and the %s annotation ", CoordinatorUtils.CONTROLLER_IMAGE_ANNOTATION_NAME), () -> {
                builder.addToAnnotations(CoordinatorUtils.CONTROLLER_IMAGE_ANNOTATION_NAME, "test/my-controller");
            });
            step(format("and the %s annotation ", CoordinatorUtils.SUPPORTED_CAPABILITIES_ANNOTATION), () -> {
                builder.addToAnnotations(CoordinatorUtils.SUPPORTED_CAPABILITIES_ANNOTATION, "mysql.dbms");
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
                () -> client.createOrPatchEntandoResource(CoordinatorTestUtil.toSerializedResource(providedCapability)));
        step(format("Then a controller pod has been created with the image that I specified in the %s annotation",
                CoordinatorUtils.CONTROLLER_IMAGE_ANNOTATION_NAME),
                () -> {
                    await().atMost(10, TimeUnit.SECONDS).ignoreExceptions().until(() ->
                            client.loadPod(AbstractK8SClientDouble.CONTROLLER_NAMESPACE, CoordinatorUtils.podLabelsFor(providedCapability))
                                    != null);
                    assertThat(
                            client.loadPod(AbstractK8SClientDouble.CONTROLLER_NAMESPACE, CoordinatorUtils.podLabelsFor(providedCapability))
                                    .getSpec()
                                    .getContainers().get(0).getImage())
                            .contains("test/my-controller");
                });

    }
}
