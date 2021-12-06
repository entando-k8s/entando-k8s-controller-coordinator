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
import static java.util.Optional.ofNullable;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.extensions.Ingress;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.qameta.allure.Description;
import io.qameta.allure.Feature;
import io.qameta.allure.Issue;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.entando.kubernetes.controller.spi.common.EntandoOperatorSpiConfigProperty;
import org.entando.kubernetes.controller.spi.common.TrustStoreHelper;
import org.entando.kubernetes.controller.support.client.impl.DefaultSimpleK8SClient;
import org.entando.kubernetes.controller.support.client.impl.EntandoOperatorTestConfig;
import org.entando.kubernetes.controller.support.client.impl.SupportProducer;
import org.entando.kubernetes.controller.support.client.impl.integrationtesthelpers.HttpTestHelper;
import org.entando.kubernetes.controller.support.common.EntandoOperatorConfig;
import org.entando.kubernetes.controller.support.common.EntandoOperatorConfigProperty;
import org.entando.kubernetes.model.app.EntandoApp;
import org.entando.kubernetes.model.app.EntandoAppBuilder;
import org.entando.kubernetes.model.capability.ProvidedCapability;
import org.entando.kubernetes.model.capability.StandardCapabilityImplementation;
import org.entando.kubernetes.model.common.DbmsVendor;
import org.entando.kubernetes.model.common.EntandoDeploymentPhase;
import org.entando.kubernetes.model.externaldatabase.EntandoDatabaseService;
import org.entando.kubernetes.model.keycloakserver.EntandoKeycloakServer;
import org.entando.kubernetes.test.common.KeycloakTestCapabilityProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Tags;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@Tags({@Tag("smoke"), @Tag("inter-process"), @Tag("allure"), @Tag("post-deployment")})
@Feature(
        "As an Entando Operator users, I want to use a deploy the Entando Controller Coordinator as a Docker container so that "
                + "I don't need to know any of its implementation details to use it.")
@Issue("ENG-2284")
class ControllerCoordinatorSmokeTest {

    private static final String NAMESPACE;

    static {
        String pipelinesRecommendedNamespace = System.getenv("ENTANDO_OPT_TEST_NAMESPACE");
        NAMESPACE = EntandoOperatorTestConfig.calculateNameSpace((pipelinesRecommendedNamespace != null)
                ? pipelinesRecommendedNamespace
                : "test-entando-controller-coordination"
        );
    }

    private static final String MY_APP = EntandoOperatorTestConfig.calculateName("my-app");
    private final KubernetesClient fabric8Client = ((DefaultKubernetesClient) new SupportProducer().getKubernetesClient())
            .inNamespace("jx");
    private final SimpleKubernetesClient client = new DefaultSimpleKubernetesClient(fabric8Client);
    private final ObjectMapper objectMapper = new ObjectMapper(new YAMLFactory());

    @BeforeEach
    void deleteEntandoApps() {
        ofNullable(fabric8Client.secrets().inNamespace(NAMESPACE).withName("test-ca-secret").get())
                .ifPresent(TrustStoreHelper::trustCertificateAuthoritiesIn);
        ofNullable(fabric8Client.secrets().inNamespace(NAMESPACE).withName("test-tls-secret").get())
                .ifPresent(s -> System.setProperty(EntandoOperatorConfigProperty.ENTANDO_TLS_SECRET_NAME.getJvmSystemProperty(),
                        s.getMetadata().getName()));
        ofNullable(fabric8Client.secrets().inNamespace(NAMESPACE).withName("test-ca-secret").get())
                .ifPresent(s -> System.setProperty(EntandoOperatorSpiConfigProperty.ENTANDO_CA_SECRET_NAME.getJvmSystemProperty(),
                        s.getMetadata().getName()));
        Arrays.asList(ProvidedCapability.class,
                EntandoApp.class,
                EntandoDatabaseService.class,
                EntandoKeycloakServer.class)
                .forEach(resourceType -> await().atMost(3, TimeUnit.MINUTES).ignoreExceptions().until(() -> {
                    if (fabric8Client.customResources(resourceType).inNamespace(NAMESPACE).list().getItems().isEmpty()) {
                        return true;
                    } else {
                        fabric8Client.customResources(resourceType).inNamespace(NAMESPACE).delete();
                        return false;
                    }
                }));
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "ENTANDO_OPT_PREVIEW_TESTS", matches = "true")
    @Description("Should deploy all the capabilities required for an EntandoApp")
    void smokeTest() {
        //NB!!! Wildcard certs can't have more than 1 segment before the defaultRoutingSuffix: https://datatracker.ietf.org/doc/html/rfc2818#section-3.1
        String ingressHostname = MY_APP + "-" + NAMESPACE + "." + EntandoOperatorConfig.getDefaultRoutingSuffix()
                .orElse("apps.ent64azure.com");
        //TODO migrate this to TestResource and create a really simple Controller for it to execute. However, keep in mind
        //that the operator service account doesn't have access to TestResources
        step("Given that the entando-k8s-controller-coordinator has been deployed along with the entando-k8s-service", () -> {
            final Service k8sSvc = fabric8Client.services().inNamespace(NAMESPACE).withName("entando-k8s-service")
                    .get();
            attachment("EntandoK8SService", objectMapper.writeValueAsString(k8sSvc));
            assertThat(k8sSvc).isNotNull();
            final Optional<Deployment> operatorDeployment = fabric8Client.apps().deployments().inNamespace(NAMESPACE).list().getItems()
                    .stream()
                    .filter(deployment -> deployment.getSpec().getTemplate().getSpec().getContainers().get(0).getImage()
                            .contains("entando-k8s-controller-coordinator")).findFirst();
            assertThat(operatorDeployment).isPresent();
            attachment("OperatorDeployment", objectMapper.writeValueAsString(operatorDeployment.get()));
            await().atMost(1, TimeUnit.MINUTES).ignoreExceptions().until(() -> fabric8Client.apps().deployments().inNamespace(NAMESPACE)
                    .withName(operatorDeployment.get().getMetadata().getName()).isReady());
        });
        step("And I have created an externally provisioned Keycloak SSO capability and waited for it to become available", () -> {
            final KeycloakTestCapabilityProvider keycloakProvider = new KeycloakTestCapabilityProvider(
                    new DefaultSimpleK8SClient(fabric8Client),
                    NAMESPACE);
            final ProvidedCapability keycloakCapability = keycloakProvider.createKeycloakCapability();
            await().atMost(2, TimeUnit.MINUTES).ignoreExceptions()
                    .until(() -> fabric8Client.customResources(ProvidedCapability.class).inNamespace(NAMESPACE)
                            .withName(keycloakCapability.getMetadata().getName()).fromServer().get().getStatus().getPhase()
                            == EntandoDeploymentPhase.SUCCESSFUL);
            keycloakProvider.deleteTestRealms(keycloakCapability, NAMESPACE);
            attachment("Keycloak Capability", objectMapper.writeValueAsString(keycloakCapability));
        });
        step("When I create an EntandoApp that requires SSO and a PostgreSQL DBMS capability", () -> {
            EntandoApp entandoApp =
                    fabric8Client.customResources(EntandoApp.class).inNamespace(NAMESPACE).create(new EntandoAppBuilder()
                            .withNewMetadata()
                            .withNamespace(NAMESPACE)
                            .withName("my-app")
                            .endMetadata()
                            .withNewSpec()
                            .withDbms(DbmsVendor.POSTGRESQL)
                            .withIngressHostName(ingressHostname)
                            .endSpec()
                            .build());
            attachment("Keycloak Capability", objectMapper.writeValueAsString(entandoApp));
        });
        step("Then I expect to see a PostgreSQL database capability that has been made available", () -> {
            await().atMost(2, TimeUnit.MINUTES).ignoreExceptions()
                    .until(() -> fabric8Client.customResources(ProvidedCapability.class).inNamespace(NAMESPACE)
                            .list()
                            .getItems()
                            .stream()
                            .anyMatch(
                                    providedCapability -> providedCapability.getSpec().getImplementation().isPresent() && providedCapability
                                            .getSpec().getImplementation().get().equals(
                                                    StandardCapabilityImplementation.POSTGRESQL)
                                            && providedCapability.getStatus().getPhase() == EntandoDeploymentPhase.SUCCESSFUL));
        });
        step("And a deployment for the Entando App", () -> {
            await().atMost(5, TimeUnit.MINUTES).ignoreExceptions()
                    .until(() -> fabric8Client.apps().deployments().inNamespace(NAMESPACE)
                            .list()
                            .getItems()
                            .stream()
                            .filter(d -> d.getSpec().getTemplate().getSpec().getContainers().get(0).getImage().contains("de-app"))
                            .findFirst().get().getStatus().getReadyReplicas() >= 1);
        });
        step("And an Ingress for the Entando App", () -> {
            Optional<Ingress> ingress = fabric8Client.extensions().ingresses().inNamespace(NAMESPACE)
                    .list()
                    .getItems()
                    .stream()
                    .filter(d -> d.getSpec().getRules().get(0).getHost().equals(ingressHostname)).findFirst();
            assertThat(ingress).isPresent();
        });
        step("And I can connect to the EntandoApp's health check path", () -> {
            final String strUrl = HttpTestHelper.getDefaultProtocol() + "://" + ingressHostname
                    + "/entando-de-app/api/health";
            System.out.println("Attempting to connect to " + strUrl);
            await().atMost(2, TimeUnit.MINUTES).ignoreExceptions()
                    .until(() -> HttpTestHelper.statusOk(strUrl));
        });
    }
}
