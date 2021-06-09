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

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.apiextensions.v1beta1.CustomResourceDefinition;
import io.fabric8.kubernetes.api.model.apiextensions.v1beta1.CustomResourceDefinitionList;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.WatcherException;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import io.qameta.allure.Description;
import io.qameta.allure.Feature;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.entando.kubernetes.controller.spi.client.SerializedEntandoResource;
import org.entando.kubernetes.controller.spi.common.LabelNames;
import org.entando.kubernetes.controller.spi.common.PodResult;
import org.entando.kubernetes.controller.spi.common.PodResult.State;
import org.entando.kubernetes.fluentspi.BasicDeploymentSpecBuilder;
import org.entando.kubernetes.fluentspi.TestResource;
import org.entando.kubernetes.model.common.EntandoDeploymentPhase;
import org.entando.kubernetes.test.common.ValueHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Tags;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.migrationsupport.rules.EnableRuleMigrationSupport;

@Tags({@Tag("adapter"), @Tag("pre-deployment"), @Tag("integration")})
@Feature("As a controller-coordinator developer, I would like to perform common operations against Kubernetes using a simple "
        + "interface to reduce the learning curve")
@EnableRuleMigrationSupport
class DefaultSimpleKubernetesClientTest extends ControllerCoordinatorAdapterTestBase {


    DefaultSimpleKubernetesClient myClient;

    public DefaultSimpleKubernetesClient getMyClient() {
        this.myClient = Objects.requireNonNullElseGet(this.myClient,
                () -> new DefaultSimpleKubernetesClient(new DefaultKubernetesClient().inNamespace(NAMESPACE)));
        return this.myClient;
    }

    @BeforeEach
    void deletePods() {
        super.deletePods();
        final ConfigMap crdMap = getMyClient()
                .findOrCreateControllerConfigMap(CoordinatorUtils.ENTANDO_CRD_NAMES_CONFIGMAP_NAME);
        crdMap.setData(Objects.requireNonNullElseGet(crdMap.getData(), HashMap::new));
        crdMap.getData().put("TestResource.test.org", "testresources.test.org");
        getMyClient().patchControllerConfigMap(crdMap);
    }

    @Test
    @Description("Should delete pods and wait until they have been successfully deleted ")
    void shouldRemovePodsAndWait() {
        step("Given I have started a service pod with the label 'pod-label=123' that will not complete on its own", () -> {
            final Pod startedPod = getMyClient().startPod(new PodBuilder()
                    .withNewMetadata()
                    .withName(MY_POD)
                    .withNamespace(NAMESPACE)
                    .addToLabels("pod-label", "123")
                    .endMetadata()
                    .withNewSpec()
                    .addNewContainer()
                    .withImage("centos/nginx-116-centos7")
                    .withName("nginx")
                    .withCommand("/usr/libexec/s2i/run")
                    .endContainer()
                    .endSpec()
                    .build());
            attachment("Started Pod", objectMapper.writeValueAsString(startedPod));
        });
        step("And I have waited for the pod to be ready", () -> {
            await().ignoreExceptions().atMost(30, TimeUnit.SECONDS).until(() ->
                    PodResult.of(getFabric8Client().pods().inNamespace(NAMESPACE).withName(MY_POD).fromServer().get()).getState()
                            == State.READY);
            attachment("Started Pod", objectMapper
                    .writeValueAsString(getFabric8Client().pods().inNamespace(NAMESPACE).withName(MY_POD).fromServer().get()));
        });
        step("When I delete and wait for pods with the label 'pod-label=123'", () -> {
            getMyClient().removePodsAndWait(NAMESPACE, Map.of("pod-label", "123"));
        });
        step("Then that pod will be absent immediately after the call finished", () -> {
            assertThat(getFabric8Client().pods().inNamespace(NAMESPACE).withName(MY_POD).fromServer().get()).isNull();
        });
    }

    @Test
    @Description("Should track phase updates on the status of opaque custom resources and in Kubernetes events")
    void shouldUpdateStatusOfOpaqueCustomResource() throws IOException {
        ValueHolder<TestResource> testResource = new ValueHolder<>();
        step("Given I have created an instance of the CustomResourceDefinition TestResource", () -> {
            testResource.set(getFabric8Client().customResources(TestResource.class).inNamespace(NAMESPACE).create(new TestResource()
                    .withNames(NAMESPACE, MY_APP)
                    .withSpec(new BasicDeploymentSpecBuilder()
                            .withReplicas(1)
                            .build())));
            attachResource("TestResource", testResource.get());
        });
        SerializedEntandoResource serializedEntandoResource = objectMapper
                .readValue(objectMapper.writeValueAsBytes(testResource.get()), SerializedEntandoResource.class);
        step("And it is represented in an opaque format using the SerializedEntandoResource class", () -> {
            serializedEntandoResource.setDefinition(
                    CustomResourceDefinitionContext.fromCustomResourceType(TestResource.class));
            attachResource("Opaque Resource", serializedEntandoResource);
        });
        step("When I update its phase to 'successful'", () ->
                getMyClient().updatePhase(serializedEntandoResource, EntandoDeploymentPhase.SUCCESSFUL));
        step("Then the updated status reflects on the TestResource", () -> {
            final TestResource actual = getFabric8Client().customResources(TestResource.class).inNamespace(NAMESPACE).withName(MY_APP)
                    .get();
            assertThat(actual.getStatus().getPhase()).isEqualTo(EntandoDeploymentPhase.SUCCESSFUL);
            attachResource("TestResource", actual);
        });
        step("And a PHASE_CHANGE event has been issued to Kubernetes", () -> {
            final List<Event> events = getMyClient().listEventsFor(testResource.get());
            attachResources("Events", events);
            assertThat(events).allMatch(event -> event.getInvolvedObject().getName().equals(testResource.get().getMetadata().getName()));
            assertThat(events).anyMatch(event -> event.getAction().equals("PHASE_CHANGE"));
        });

    }

    @Test
    @Description("Should ignore CustomResourceDefinitions without the label 'entando.org/crd-of-interest'")
    void shouldIgnoreCustomResourceDefinitionsWithoutCrdOfInterestLabel() {
        step("Given I have removed the CustomResourceDefinition MyCRD", () -> {
            deleteMyCrd();
        });
        Map<String, CustomResourceDefinition> crds = new ConcurrentHashMap<>();
        step("And I have started watching for changes against CustomResourceDefinitions", () -> {
            myClient.watchCustomResourceDefinitions(new Watcher<>() {
                @Override
                public void eventReceived(Action action, CustomResourceDefinition customResourceDefinition) {
                    crds.put(customResourceDefinition.getMetadata().getName(), customResourceDefinition);
                }

                @Override
                public void onClose(WatcherException e) {

                }
            });
        });
        step("When I create the CustomResourceDefinition MyCRD without the label 'entando.org/crd-of-interest'", () -> {
            final CustomResourceDefinition value = objectMapper
                    .readValue(Thread.currentThread().getContextClassLoader().getResource("mycrds.test.org.crd.yaml"),
                            CustomResourceDefinition.class);
            getFabric8Client().apiextensions().v1beta1().customResourceDefinitions().create(value);
        });
        step("Then the CustomResourceDefinition was ignored", () -> {
            assertThat(crds).doesNotContainKey("mycrds.test.org");
        });
    }

    @Test
    @Description("Should watch CustomResourceDefinitions with the label 'entando.org/crd-of-interest'")
    void shouldWatchCustomResourceDefinitionsWithCrdOfInterestLabel() {
        step("Given I have removed the CustomResourceDefinition MyCRD", () -> {
            deleteMyCrd();
        });
        Map<String, CustomResourceDefinition> crds = new ConcurrentHashMap<>();
        step("And I have started watching for changes against CustomResourceDefinitions", () -> {
            myClient.watchCustomResourceDefinitions(new Watcher<>() {
                @Override
                public void eventReceived(Action action, CustomResourceDefinition customResourceDefinition) {
                    crds.put(customResourceDefinition.getMetadata().getName(), customResourceDefinition);
                }

                @Override
                public void onClose(WatcherException e) {

                }
            });
        });
        step("When I create the CustomResourceDefinition MyCRD without the label 'entando.org/crd-of-interest'", () -> {
            final CustomResourceDefinition value = objectMapper
                    .readValue(Thread.currentThread().getContextClassLoader().getResource("mycrds.test.org.crd.yaml"),
                            CustomResourceDefinition.class);
            value.getMetadata().setLabels(Map.of(LabelNames.CRD_OF_INTEREST.getName(), "MyCRD"));
            getFabric8Client().apiextensions().v1beta1().customResourceDefinitions().create(value);

        });
        step("Then the CustomResourceDefinition was ignored", () -> {
            await().atMost(10, TimeUnit.SECONDS).ignoreExceptions().until(() -> crds.containsKey("mycrds.test.org"));
            assertThat(crds).containsKey("mycrds.test.org");
        });
    }

    @Test
    @Description("Should not list CustomResourceDefinitions without the label 'entando.org/crd-of-interest'")
    void shouldNotlistCustomResourceDefintionsWithoutCrdOfInterestLabel() {
        step("Given I have removed the CustomResourceDefinition MyCRD", () -> {
            deleteMyCrd();
        });
        step("And I have created the CustomResourceDefinition MyCRD without the label 'entando.org/crd-of-interest'", () -> {
            final CustomResourceDefinition value = objectMapper
                    .readValue(Thread.currentThread().getContextClassLoader().getResource("mycrds.test.org.crd.yaml"),
                            CustomResourceDefinition.class);
            getFabric8Client().apiextensions().v1beta1().customResourceDefinitions().create(value);
        });
        List<CustomResourceDefinition> crds = new ArrayList<>();
        step("When I list the CustomResourceDefinitions of interest", () -> {
            crds.addAll(myClient.loadCustomResourceDefinitionsOfInterest());
        });
        step("Then the CustomResourceDefinition without the entando.org/crd-of-interest label was not inluded in the list", () -> {
            assertThat(crds).noneMatch(crd -> crd.getMetadata().getName().equals("mycrds.test.org"));
        });
    }

    @Test
    @Description("Should list CustomResourceDefinitions with the label 'entando.org/crd-of-interest'")
    void shouldListCustomResourceDefinitionsWithCrdOfInterestLabel() {
        ValueHolder<TestResource> testResource = new ValueHolder<>();
        step("Given I have removed the CustomResourceDefinition MyCRD", () -> {
            deleteMyCrd();
        });
        step("And I have created the CustomResourceDefinition MyCRD without the label 'entando.org/crd-of-interest'", () -> {
            final CustomResourceDefinition value = objectMapper
                    .readValue(Thread.currentThread().getContextClassLoader().getResource("mycrds.test.org.crd.yaml"),
                            CustomResourceDefinition.class);
            value.getMetadata().setLabels(Map.of(LabelNames.CRD_OF_INTEREST.getName(), "MyCRD"));
            getFabric8Client().apiextensions().v1beta1().customResourceDefinitions().create(value);

        });
        List<CustomResourceDefinition> crds = new ArrayList<>();
        step("When I list the CustomResourceDefinitions of interest", () -> {
            crds.addAll(myClient.loadCustomResourceDefinitionsOfInterest());
        });
        step("Then the CustomResourceDefinition without the entando.org/crd-of-interest label was not inluded in the list", () -> {
            assertThat(crds).anyMatch(crd -> crd.getMetadata().getName().equals("mycrds.test.org"));
        });
    }

    private void deleteMyCrd() throws InterruptedException {
        NonNamespaceOperation<CustomResourceDefinition, CustomResourceDefinitionList, Resource<CustomResourceDefinition>> crdResource =
                getFabric8Client().apiextensions().v1beta1().customResourceDefinitions();
        crdResource.withName("mycrds.test.org").delete();
        crdResource.waitUntilCondition(crd -> crdResource.withName("mycrds.test.org").fromServer().get() == null, 20, TimeUnit.SECONDS);
    }

    @Test
    @Description("Should overwrite controller secrets")
    void shouldCreateControllerSecrets() {
        step("Given I have delete the controller secret 'my-secret'", () -> {
            getFabric8Client().secrets().inNamespace(myClient.getControllerNamespace()).withName("my-secret").delete();
        });
        step("And I have recreated the controller secret 'my-secret' with no data", () -> {
            final Secret secret = myClient.overwriteControllerSecret(
                    new SecretBuilder().withNewMetadata().withNamespace(myClient.getControllerNamespace()).withName("my-secret")
                            .endMetadata().build());
            attachment("Secret", objectMapper.writeValueAsString(secret));
        });
        step("When I overwrite it with a secret with the key 'username'", () -> {
            final Secret secret = myClient.overwriteControllerSecret(
                    new SecretBuilder().withNewMetadata().withNamespace(myClient.getControllerNamespace()).withName("my-secret")
                            .endMetadata().addToStringData("username", "john").build());
            attachment("Secret", objectMapper.writeValueAsString(secret));
        });
        step("Then the secret with the key 'username' is available", () -> {
            final Secret secret = myClient.loadControllerSecret("my-secret");
            assertThat(secret.getData()).containsKeys("username");
            attachment("Secret", objectMapper.writeValueAsString(secret));
        });
    }

    @Test
    @Description("Should watch ConfigMaps")
    void shouldWatchConfigMaps() {
        Map<String, ConfigMap> configMaps = new ConcurrentHashMap<>();
        step("Given I am watcing a ConfigMap my-configmap",
                () -> myClient.watchControllerConfigMap("my-configmap", new Watcher<>() {
                    @Override
                    public void eventReceived(Action action, ConfigMap configMap) {
                        configMaps.put(configMap.getMetadata().getName(), configMap);
                    }

                    @Override
                    public void onClose(WatcherException e) {

                    }
                }));
        step("When I create the configmap", () -> {
            final ConfigMap cm = myClient.findOrCreateControllerConfigMap("my-configmap");
            attachment("ConfigMap", objectMapper.writeValueAsString(cm));
        });
        step("Then I have received an event for the ConfigMap", () -> {
            await().atMost(10, TimeUnit.SECONDS).ignoreExceptions().until(() -> configMaps.containsKey("my-configmap"));
            assertThat(configMaps).containsKeys("my-configmap");
        });

    }

}
