package org.entando.kubernetes.controller.coordinator.interprocesstests;

import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.NamespacedKubernetesClient;
import io.quarkus.runtime.StartupEvent;
import org.entando.kubernetes.controller.coordinator.AbstractControllerCoordinatorTest;
import org.entando.kubernetes.controller.coordinator.EntandoControllerCoordinator;
import org.entando.kubernetes.controller.integrationtest.support.EntandoOperatorTestConfig;
import org.entando.kubernetes.controller.integrationtest.support.EntandoOperatorTestConfig.TestTarget;
import org.entando.kubernetes.model.EntandoBaseCustomResource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;

@Tag("inter-process")
public class ControllerCoordinatorIntegratedTest extends AbstractControllerCoordinatorTest {

    private NamespacedKubernetesClient client;

    private static NamespacedKubernetesClient newClient() {
        return new DefaultKubernetesClient().inNamespace(NAMESPACE);
    }

    @BeforeAll
    public static void prepareCoordinator() {
        if (EntandoOperatorTestConfig.getTestTarget() == TestTarget.STANDALONE) {
            new EntandoControllerCoordinator(newClient()).onStartup(new StartupEvent());
        } else {
            //Should be installed by helm chart in pipeline
        }
    }

    @Override
    protected KubernetesClient getClient() {
        if (this.client == null) {
            this.client = newClient();
        }
        return this.client;
    }

    @Override
    protected <T extends EntandoBaseCustomResource> void afterCreate(T resource) {
    }

}
