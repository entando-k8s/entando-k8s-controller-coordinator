package org.entando.kubernetes.controller.coordinator;

import io.fabric8.kubernetes.client.CustomResourceList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watcher.Action;
import io.fabric8.kubernetes.client.dsl.internal.CustomResourceOperationsImpl;
import io.quarkus.runtime.StartupEvent;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import javax.enterprise.event.Observes;
import javax.inject.Inject;
import org.entando.kubernetes.controller.EntandoOperatorConfig;
import org.entando.kubernetes.controller.KubeUtils;
import org.entando.kubernetes.controller.common.ControllerExecutor;
import org.entando.kubernetes.model.DoneableEntandoCustomResource;
import org.entando.kubernetes.model.EntandoResourceOperationsRegistry;
import org.entando.kubernetes.model.app.EntandoApp;
import org.entando.kubernetes.model.app.EntandoBaseCustomResource;
import org.entando.kubernetes.model.externaldatabase.EntandoDatabaseService;
import org.entando.kubernetes.model.infrastructure.EntandoClusterInfrastructure;
import org.entando.kubernetes.model.keycloakserver.EntandoKeycloakServer;
import org.entando.kubernetes.model.link.EntandoAppPluginLink;
import org.entando.kubernetes.model.plugin.EntandoPlugin;

public class EntandoControllerCoordinator {

    private final KubernetesClient client;
    private final Map<Class<? extends EntandoBaseCustomResource>, EntandoResourceObserver<?, ?, ?>> observers = new ConcurrentHashMap<>();
    private final ControllerExecutor executor;
    private final EntandoResourceOperationsRegistry entandoResourceOperationsRegistry;
    private final EntandoDatabaseServiceController abstractDbAwareController;

    @Inject
    public EntandoControllerCoordinator(KubernetesClient client) {
        this.entandoResourceOperationsRegistry = new EntandoResourceOperationsRegistry(client);
        this.client = client;
        this.executor = new ControllerExecutor(client.getNamespace(), client);
        abstractDbAwareController = new EntandoDatabaseServiceController(client);
    }

    public void onStartup(@Observes StartupEvent event) {
        addObserver(EntandoKeycloakServer.class, this::startImage);
        addObserver(EntandoClusterInfrastructure.class, this::startImage);
        addObserver(EntandoApp.class, this::startImage);
        addObserver(EntandoPlugin.class, this::startImage);
        addObserver(EntandoAppPluginLink.class, this::startImage);
        addObserver(EntandoDatabaseService.class, this.abstractDbAwareController::processEvent);
        KubeUtils.ready(EntandoControllerCoordinator.class.getSimpleName());
    }

    @SuppressWarnings("unchecked")
    public <R extends EntandoBaseCustomResource,
            L extends CustomResourceList<R>,
            D extends DoneableEntandoCustomResource<D, R>> EntandoResourceObserver<R, L, D> getObserver(Class<R> clss) {
        return (EntandoResourceObserver<R, L, D>) observers.get(clss);
    }

    @SuppressWarnings("unchecked")
    private <R extends EntandoBaseCustomResource> void addObserver(Class<R> type, BiConsumer<Action, R> consumer) {
        CustomResourceOperationsImpl operations = this.entandoResourceOperationsRegistry.getOperations(type);
        CustomResourceOperationsImpl namespacedOperations = EntandoOperatorConfig.getOperatorNamespaceOverride()
                .map(s -> (CustomResourceOperationsImpl) operations.inNamespace(s))
                .orElse((CustomResourceOperationsImpl) operations.inAnyNamespace());
        observers.put(operations.getType(), new EntandoResourceObserver<>(namespacedOperations, consumer));
    }

    private <T extends EntandoBaseCustomResource> void startImage(Action action, T resource) {
        executor.startControllerFor(action, resource, ControllerExecutor.resolveLatestImageFor(client, resource.getClass()).orElseThrow(
                IllegalStateException::new));
    }

}
