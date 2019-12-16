package org.entando.kubernetes.controller.coordinator.interprocesstests;

import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.NamespacedKubernetesClient;
import io.quarkus.runtime.StartupEvent;
import org.entando.kubernetes.controller.coordinator.AbstractControllerCoordinatorTest;
import org.entando.kubernetes.controller.coordinator.EntandoControllerCoordinator;
import org.entando.kubernetes.controller.integrationtest.support.EntandoOperatorE2ETestConfig;
import org.entando.kubernetes.controller.integrationtest.support.EntandoOperatorE2ETestConfig.TestTarget;
import org.entando.kubernetes.model.app.EntandoBaseCustomResource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;

@Tag("inter-process")
public class ControllerCoordinatorIntegratedTest extends AbstractControllerCoordinatorTest {

    private static NamespacedKubernetesClient newClient() {
        return new DefaultKubernetesClient().inNamespace(NAMESPACE);
    }

    @BeforeAll
    public static void prepareCoordinator() {
        if (EntandoOperatorE2ETestConfig.getTestTarget() == TestTarget.STANDALONE) {
            new EntandoControllerCoordinator(newClient()).onStartup(new StartupEvent());
        } else {
            //Should be installed by helm chart in pipeline
        }
    }

    @Override
    protected KubernetesClient getClient() {
        return newClient();
    }

    @Override
    protected <T extends EntandoBaseCustomResource> void afterCreate(T resource) {
    }

}
