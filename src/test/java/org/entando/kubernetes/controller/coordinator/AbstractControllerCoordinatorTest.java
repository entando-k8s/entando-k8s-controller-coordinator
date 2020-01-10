package org.entando.kubernetes.controller.coordinator;

import static java.lang.String.format;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.Watcher.Action;
import io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.entando.kubernetes.controller.EntandoOperatorConfig;
import org.entando.kubernetes.controller.EntandoOperatorConfigProperty;
import org.entando.kubernetes.controller.KubeUtils;
import org.entando.kubernetes.controller.integrationtest.support.EntandoOperatorTestConfig;
import org.entando.kubernetes.controller.integrationtest.support.FluentIntegrationTesting;
import org.entando.kubernetes.controller.integrationtest.support.TestFixturePreparation;
import org.entando.kubernetes.controller.test.support.FluentTraversals;
import org.entando.kubernetes.controller.test.support.VariableReferenceAssertions;
import org.entando.kubernetes.model.DbmsImageVendor;
import org.entando.kubernetes.model.EntandoBaseCustomResource;
import org.entando.kubernetes.model.externaldatabase.EntandoDatabaseService;
import org.entando.kubernetes.model.externaldatabase.EntandoDatabaseServiceBuilder;
import org.entando.kubernetes.model.externaldatabase.EntandoDatabaseServiceOperationFactory;
import org.entando.kubernetes.model.keycloakserver.EntandoKeycloakServer;
import org.entando.kubernetes.model.keycloakserver.EntandoKeycloakServerBuilder;
import org.entando.kubernetes.model.keycloakserver.EntandoKeycloakServerOperationFactory;
import org.junit.jupiter.api.Test;

public abstract class AbstractControllerCoordinatorTest implements FluentIntegrationTesting, FluentTraversals, VariableReferenceAssertions {

    public static final String NAMESPACE = EntandoOperatorTestConfig.calculateNameSpace("coordinator-test");
    private static final String FALLBACK_KEYCLOAK_CONTROLLER_VERSION = "6.0.33";
    private static final String FALLBACK_KEYCLOAK_CONTROLLER_IMAGE_INFO = format(
            "{\"version\":\"%s\"}",
            FALLBACK_KEYCLOAK_CONTROLLER_VERSION);
    private static final String KEYCLOAK_CONTROLLER_IMAGE_NAME = "entando-k8s-keycloak-controller";

    protected abstract KubernetesClient getClient();

    @SuppressWarnings("unchecked")
    protected abstract <T extends EntandoBaseCustomResource> void afterCreate(T resource);

    @Test
    public void testExecuteControllerPod() throws JsonProcessingException {
        //Given I have a clean namespace
        TestFixturePreparation.prepareTestFixture(getClient(), deleteAll(EntandoKeycloakServer.class).fromNamespace(NAMESPACE));
        KubernetesClient client = getClient();
        //and the Coordinator observes this namespace
        System.setProperty(EntandoOperatorConfigProperty.ENTANDO_K8S_OPERATOR_NAMESPACE_TO_OBSERVE.getJvmSystemProperty(),
                client.getNamespace());
        //And I have a config map with the Entando KeycloakController's image information
        final String versionToExpect = ensureKeycloakControllerVersion();
        //When I create a new EntandoKeycloakServer resource
        EntandoKeycloakServer keycloakServer = new EntandoKeycloakServerBuilder()
                .withNewMetadata().withName("test-keycloak").withNamespace(client.getNamespace()).endMetadata()
                .withNewSpec()
                .withDbms(DbmsImageVendor.NONE)
                .endSpec()
                .build();
        EntandoKeycloakServerOperationFactory.produceAllEntandoKeycloakServers(client)
                .inNamespace(client.getNamespace()).create(keycloakServer);
        afterCreate(keycloakServer);
        //Then I expect to see at least one controller pod
        FilterWatchListDeletable<Pod, PodList, Boolean, Watch, Watcher<Pod>> listable = client.pods()
                .inNamespace(client.getNamespace())
                .withLabel(KubeUtils.ENTANDO_RESOURCE_KIND_LABEL_NAME, "EntandoKeycloakServer");
        await().ignoreExceptions().atMost(30, TimeUnit.SECONDS).until(() -> listable.list().getItems().size() > 0);
        Pod theControllerPod = listable.list().getItems().get(0);
        assertThat(theVariableNamed("ENTANDO_RESOURCE_ACTION").on(thePrimaryContainerOn(theControllerPod)), is(Action.ADDED.name()));
        assertThat(theVariableNamed("ENTANDO_RESOURCE_NAME").on(thePrimaryContainerOn(theControllerPod)),
                is(keycloakServer.getMetadata().getName()));
        assertThat(theVariableNamed("ENTANDO_RESOURCE_NAMESPACE").on(thePrimaryContainerOn(theControllerPod)),
                is(keycloakServer.getMetadata().getNamespace()));
        //With the correct version specified
        assertTrue(thePrimaryContainerOn(theControllerPod).getImage().endsWith(versionToExpect));
    }

    protected String ensureKeycloakControllerVersion() throws JsonProcessingException {
        ConfigMap versionConfigMap = getClient().configMaps().inNamespace(getConfigMapNamespace()).withName(getVersionsConfigMapName())
                .fromServer().get();
        if (versionConfigMap == null) {
            getClient().configMaps().inNamespace(getConfigMapNamespace()).createNew().withNewMetadata().withName(
                    getVersionsConfigMapName()).endMetadata()
                    .addToData(KEYCLOAK_CONTROLLER_IMAGE_NAME, FALLBACK_KEYCLOAK_CONTROLLER_IMAGE_INFO).done();
            return FALLBACK_KEYCLOAK_CONTROLLER_VERSION;
        } else {
            return ensureImageInfoPresent(versionConfigMap);
        }
    }

    private String getVersionsConfigMapName() {
        return EntandoOperatorConfig.getEntandoDockerImageVersionsConfigMap();
    }

    private String getConfigMapNamespace() {
        return EntandoOperatorConfig.getOperatorConfigMapNamespace().orElse(NAMESPACE);
    }

    private String ensureImageInfoPresent(ConfigMap versionConfigMap) throws JsonProcessingException {
        String imageInfo = versionConfigMap.getData().get(KEYCLOAK_CONTROLLER_IMAGE_NAME);
        if (imageInfo == null) {
            getClient().configMaps().inNamespace(versionConfigMap.getMetadata().getNamespace())
                    .withName(versionConfigMap.getMetadata().getName()).edit()
                    .addToData(KEYCLOAK_CONTROLLER_IMAGE_NAME, FALLBACK_KEYCLOAK_CONTROLLER_IMAGE_INFO).done();
            return FALLBACK_KEYCLOAK_CONTROLLER_VERSION;
        } else {
            return ensureVersionAttributePresent(versionConfigMap, imageInfo);
        }
    }

    @SuppressWarnings("unchecked")
    private String ensureVersionAttributePresent(ConfigMap versionConfigMap, String imageInfo) throws JsonProcessingException {
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> map = mapper.readValue(imageInfo, Map.class);
        String version = (String) map.get("version");
        if (version == null) {
            map.put("version", FALLBACK_KEYCLOAK_CONTROLLER_VERSION);
            getClient().configMaps().inNamespace(versionConfigMap.getMetadata().getNamespace())
                    .withName(versionConfigMap.getMetadata().getName()).edit()
                    .addToData(KEYCLOAK_CONTROLLER_IMAGE_NAME, mapper.writeValueAsString(map)).done();
            return FALLBACK_KEYCLOAK_CONTROLLER_VERSION;
        } else {
            return version;
        }
    }

    @Test
    public void testExecuteControllerObject() {
        TestFixturePreparation.prepareTestFixture(getClient(), deleteAll(EntandoDatabaseService.class).fromNamespace(NAMESPACE));
        System.setProperty(EntandoOperatorConfigProperty.ENTANDO_K8S_OPERATOR_NAMESPACE_TO_OBSERVE.getJvmSystemProperty(),
                getClient().getNamespace());
        EntandoDatabaseService database = new EntandoDatabaseServiceBuilder()
                .withNewMetadata().withName("test-database").withNamespace(getClient().getNamespace()).endMetadata()
                .withNewSpec()
                .withDbms(DbmsImageVendor.ORACLE)
                .withHost("somedatabase.com")
                .withPort(5050)
                .withSecretName("oracle-secret")
                .endSpec()
                .build();
        EntandoDatabaseServiceOperationFactory.produceAllEntandoDatabaseServices(getClient())
                .inNamespace(getClient().getNamespace()).create(database);
        afterCreate(database);
        FilterWatchListDeletable<Service, ServiceList, Boolean, Watch, Watcher<Service>> listable = getClient()
                .services()
                .inNamespace(getClient().getNamespace()).withLabel("EntandoDatabaseService", database.getMetadata().getName());
        await().ignoreExceptions().atMost(30, TimeUnit.SECONDS).until(() -> listable.list().getItems().size() > 0);
        Service service = listable.list().getItems().get(0);
        assertThat(service.getSpec().getExternalName(), is("somedatabase.com"));
    }
}
