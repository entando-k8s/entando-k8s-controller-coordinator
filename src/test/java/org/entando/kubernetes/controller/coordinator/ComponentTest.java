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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.fabric8.kubernetes.api.model.apiextensions.v1beta1.CustomResourceDefinition;
import io.fabric8.kubernetes.api.model.apiextensions.v1beta1.CustomResourceDefinitionBuilder;
import io.fabric8.kubernetes.api.model.apiextensions.v1beta1.CustomResourceDefinitionFluent.MetadataNested;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import io.qameta.allure.Feature;
import io.qameta.allure.Issue;
import io.quarkus.runtime.StartupEvent;
import java.io.IOException;
import org.entando.kubernetes.controller.coordinator.inprocesstests.SimpleKubernetesClientDouble;
import org.entando.kubernetes.controller.spi.client.SerializedEntandoResource;
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

    final SimpleKubernetesClientDouble client = new SimpleKubernetesClientDouble();
    final EntandoControllerCoordinator entandoControllerCoordinator = new EntandoControllerCoordinator(client);

    @Test
    void testIt() throws IOException {
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
        step("When I register my custom resource", () -> {
            client.getCluster().putCustomResourceDefinition(builder.endMetadata().build());
        });

        step("Then my custom resource definition is mapped in the " + CoordinatorUtils.ENTANDO_CRD_NAMES_CONFIGMAP_NAME + " ConfigMap", () -> {
            assertThat(client.findOrCreateControllerConfigMap(CoordinatorUtils.ENTANDO_CRD_NAMES_CONFIGMAP_NAME).getData())
                    .containsEntry("MyCRD.test.org", "mycrds.test.org");
        });
        step("Then my controller image is registered in the EntandoControllerCoordinator", () -> {
            final SerializedEntandoResource resource = new SerializedEntandoResource();
            resource.setDefinition(CustomResourceDefinitionContext.fromCrd(builder.endMetadata().build()));
            assertThat(entandoControllerCoordinator.getControllerImageFor(resource)).isEqualTo("test/my-controller");
        });

    }
}
