package org.entando.kubernetes.controller.coordinator.inprocesstests;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watcher.Action;
import io.fabric8.kubernetes.client.server.mock.KubernetesServer;
import io.quarkus.runtime.StartupEvent;
import org.apache.commons.lang3.RandomStringUtils;
import org.entando.kubernetes.controller.coordinator.AbstractControllerCoordinatorTest;
import org.entando.kubernetes.controller.coordinator.EntandoControllerCoordinator;
import org.entando.kubernetes.model.app.EntandoBaseCustomResource;
import org.junit.Rule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.migrationsupport.rules.EnableRuleMigrationSupport;

@Tag("in-process")
@EnableRuleMigrationSupport
public class ControllerCoordinatorMockedTest extends AbstractControllerCoordinatorTest {

    @Rule
    public KubernetesServer server = new KubernetesServer(false, true);
    private EntandoControllerCoordinator coordinator;

    @BeforeEach
    public void prepareCoordinator() {
        this.coordinator = new EntandoControllerCoordinator(getClient());
        coordinator.onStartup(new StartupEvent());
    }

    @Override
    protected KubernetesClient getClient() {
        return server.getClient().inNamespace(NAMESPACE);
    }

    @Override
    @SuppressWarnings("unchecked")
    protected <T extends EntandoBaseCustomResource> void afterCreate(T resource) {
        if (resource.getMetadata().getUid() == null) {
            resource.getMetadata().setUid(RandomStringUtils.randomAlphanumeric(8));
        }
        coordinator.getObserver((Class<T>) resource.getClass()).eventReceived(Action.ADDED, resource);
    }

}
