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
import io.fabric8.kubernetes.api.model.EventBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.ServiceAccount;
import io.fabric8.kubernetes.api.model.ServiceAccountBuilder;
import io.fabric8.kubernetes.api.model.apiextensions.v1beta1.CustomResourceDefinition;
import io.fabric8.kubernetes.api.model.apiextensions.v1beta1.CustomResourceDefinitionList;
import io.fabric8.kubernetes.api.model.rbac.ClusterRole;
import io.fabric8.kubernetes.api.model.rbac.ClusterRoleBinding;
import io.fabric8.kubernetes.api.model.rbac.ClusterRoleBindingBuilder;
import io.fabric8.kubernetes.api.model.rbac.ClusterRoleBuilder;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.NamespacedKubernetesClient;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.WatcherException;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import io.qameta.allure.Description;
import io.qameta.allure.Feature;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.entando.kubernetes.controller.spi.client.SerializedEntandoResource;
import org.entando.kubernetes.controller.spi.common.EntandoOperatorSpiConfig;
import org.entando.kubernetes.controller.spi.common.EntandoOperatorSpiConfigProperty;
import org.entando.kubernetes.controller.spi.common.FormatUtils;
import org.entando.kubernetes.controller.spi.common.LabelNames;
import org.entando.kubernetes.controller.spi.common.NameUtils;
import org.entando.kubernetes.controller.spi.common.PodResult;
import org.entando.kubernetes.controller.spi.common.PodResult.State;
import org.entando.kubernetes.controller.support.client.impl.integrationtesthelpers.TestFixturePreparation;
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

    private static final String MY_APP = "my-app";

    DefaultSimpleKubernetesClient myClient;
    private NamespacedKubernetesClient kubernetesClient;

    public DefaultSimpleKubernetesClient getMyClient() {
        this.kubernetesClient = Objects
                .requireNonNullElseGet(this.kubernetesClient, () -> new DefaultKubernetesClient().inNamespace(MY_APP_NAMESPACE_1));
        this.myClient = Objects.requireNonNullElseGet(this.myClient,
                () -> new DefaultSimpleKubernetesClient(kubernetesClient));
        return this.myClient;
    }

    @BeforeEach
    void createCrdNameMap() {
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
                    .withNamespace(MY_APP_NAMESPACE_1)
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
                    PodResult.of(getFabric8Client().pods().inNamespace(MY_APP_NAMESPACE_1).withName(MY_POD).fromServer().get()).getState()
                            == State.READY);
            attachment("Started Pod", objectMapper
                    .writeValueAsString(getFabric8Client().pods().inNamespace(MY_APP_NAMESPACE_1).withName(MY_POD).fromServer().get()));
        });
        step("When I delete and wait for pods with the label 'pod-label=123'", () -> {
            getMyClient().removePodsAndWait(MY_APP_NAMESPACE_1, Map.of("pod-label", "123"));
        });
        step("Then that pod will be absent immediately after the call finished", () -> {
            assertThat(getFabric8Client().pods().inNamespace(MY_APP_NAMESPACE_1).withName(MY_POD).fromServer().get()).isNull();
        });
    }

    @Test
    @Description("Should track phase updates on the status of opaque custom resources and in Kubernetes events")
    void shouldUpdateStatusOfOpaqueCustomResource() throws IOException {
        ValueHolder<TestResource> testResource = new ValueHolder<>();
        step("Given I have created an instance of the CustomResourceDefinition TestResource", () -> {
            testResource.set(createTestResource(new TestResource()
                    .withNames(MY_APP_NAMESPACE_1, MY_APP)
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
            final TestResource actual = getFabric8Client().customResources(TestResource.class).inNamespace(MY_APP_NAMESPACE_1)
                    .withName(MY_APP)
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
            super.registerCrdResource(getFabric8Client(), "mycrds.test.org.crd.yaml");
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
        step("Given I have removed the CustomResourceDefinition MyCRD", this::deleteMyCrd);
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
    @Description("Should only list configured CustomResourceDefinitions of interest when lacking the 'list' permission")
    void shouldOnlyListConfiguredCrdsOfInterest() {
        step("Given I have created the CustomResourceDefinitions testresources.test.org and mycrds.test.org", () -> {
            registerCrdResource(kubernetesClient, "testresources.test.org.crd.yaml");
            registerCrdResource(kubernetesClient, "mycrds.test.org.crd.yaml");
        });
        step("And I have only configured testresources.test.org as a known CustomResourceDefinitionOfInterest", () -> {
            System.setProperty(ControllerCoordinatorProperty.ENTANDO_CRDS_OF_INTEREST.getJvmSystemProperty(), "testresources.test.org");
        });
        step("And the serviceaccount used to connect to Kubernetes does not have 'list' permissions on CustomResourceDefinitions", () -> {
            ClusterRole crdViewer = this.kubernetesClient.rbac().clusterRoles().withName("entando-crd-viewer").fromServer().get();
            if (crdViewer == null) {
                this.kubernetesClient.rbac().clusterRoles().create(new ClusterRoleBuilder()
                        .withNewMetadata()
                        .withName("entando-crd-viewer")
                        .endMetadata()
                        .addNewRule()
                        .addNewApiGroup("apiextensions.k8s.io")
                        .addNewResource("customresourcedefinitions")
                        .addNewResourceName("testresources.test.org")
                        .endRule()
                        .build());
                this.kubernetesClient.rbac().clusterRoleBindings().create(new ClusterRoleBindingBuilder()
                        .withNewMetadata()
                        .withName("entando-crd-viewer")
                        .endMetadata()
                        .withNewRoleRef("rbac.authorization.k8s.io", "ClusterRole", "entando-crd-viewer")
                        .addNewSubject(null, "SystemGroup", "system:serviceaccounts", null)
                        .build());
            } else {
                if (!crdViewer.getRules().get(0).getResourceNames().contains("testresources.test.org")) {
                    crdViewer.getRules().get(0).getResourceNames().add("testresources.test.org");
                    this.kubernetesClient.rbac().clusterRoles().patch(crdViewer);
                }
            }
            ServiceAccount randomServiceAccount = this.kubernetesClient.serviceAccounts()
                    .inNamespace(this.kubernetesClient.getNamespace())
                    .create(new ServiceAccountBuilder()
                            .withNewMetadata()
                            .withName(NameUtils.shortenTo("random-shortendname", "random".length()))
                            .withNamespace(kubernetesClient.getNamespace())
                            .endMetadata()
                            .build());
            await().atMost(30, TimeUnit.SECONDS).ignoreExceptions()
                    .until(() -> kubernetesClient.secrets().inNamespace(kubernetesClient.getNamespace()).list()
                            .getItems().stream()
                            .anyMatch(secret -> TestFixturePreparation.isValidTokenSecret(secret, randomServiceAccount.getMetadata()
                                    .getName())));
            Secret tokenSecret = kubernetesClient.secrets().inNamespace(kubernetesClient.getNamespace()).list()
                    .getItems().stream()
                    .filter(secret -> TestFixturePreparation.isValidTokenSecret(secret, randomServiceAccount.getMetadata()
                            .getName()))
                    .findFirst().get();
            String token = new String(Base64.getDecoder().decode(tokenSecret.getData().get("token")), StandardCharsets.UTF_8);
            this.kubernetesClient = new DefaultKubernetesClient(new ConfigBuilder(Config.autoConfigure(null))
                    .withOauthToken(token)
                    .build());
            this.myClient = new DefaultSimpleKubernetesClient(kubernetesClient);
        });
        List<CustomResourceDefinition> crds = new ArrayList<>();
        step("When I list the CustomResourceDefinitions of interest", () -> {
            crds.addAll(getMyClient().loadCustomResourceDefinitionsOfInterest());
        });
        step("Then only the CustomResourceDefinition testresources.test.org was inluded in the list", () -> {
            assertThat(crds).hasSize(1);
            assertThat(crds).allMatch(crd -> crd.getMetadata().getName().equals("testresources.test.org"));
        });
    }

    @Test
    @Description("Should list CustomResourceDefinitions with the label 'entando.org/crd-of-interest'")
    void shouldListCustomResourceDefinitionsWithCrdOfInterestLabel() {
        step("Given I have removed the CustomResourceDefinition MyCRD", this::deleteMyCrd);
        step("And I have created the CustomResourceDefinition MyCRD with the label 'entando.org/crd-of-interest'", () -> {
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

    @Test
    @Description("Should issue Death events")
    void shouldIssueDeathEvent() {
        ValueHolder<Pod> startedPod = new ValueHolder<>();
        step("Given I have started a dummy pod with the name 'my-pod'", () -> {
            startedPod.set(getMyClient().startPod(new PodBuilder()
                    .withNewMetadata()
                    .withName(MY_POD)
                    .withNamespace(MY_APP_NAMESPACE_1)
                    .endMetadata()
                    .withNewSpec()
                    .addNewContainer()
                    .withImage("busybox")
                    .withName("busybox")
                    .endContainer()
                    .endSpec()
                    .build()));
            System.setProperty(EntandoOperatorSpiConfigProperty.ENTANDO_CONTROLLER_POD_NAME.getJvmSystemProperty(),
                    startedPod.get().getMetadata().getName());
            attachment("Started Pod", objectMapper.writeValueAsString(startedPod.get()));
        });
        Event event = new EventBuilder()
                .withNewMetadata()
                .withName(EntandoOperatorSpiConfig.getControllerPodName() + "-restart-" + NameUtils.randomNumeric(4))
                .addToLabels("entando-operator-restarted", "true")
                .endMetadata()
                .withCount(1)
                .withFirstTimestamp(FormatUtils.format(LocalDateTime.now()))
                .withLastTimestamp(FormatUtils.format(LocalDateTime.now()))
                .withMessage("blah")
                .build();

        step("When issue a death event against this pod",
                () -> myClient.issueOperatorDeathEvent(event));

        step("It reflects on the cluster", () -> {
            Event actual = kubernetesClient.v1().events()
                    .withName(event.getMetadata().getName())
                    .fromServer().get();
            assertThat(actual).isNotNull();
            assertThat(actual.getInvolvedObject().getUid()).isEqualTo(startedPod.get().getMetadata().getUid());

        });

    }

}
