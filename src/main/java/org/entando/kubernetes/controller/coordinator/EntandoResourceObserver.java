package org.entando.kubernetes.controller.coordinator;

import static java.lang.String.format;

import io.fabric8.kubernetes.client.CustomResourceList;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.dsl.internal.CustomResourceOperationsImpl;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.entando.kubernetes.model.DoneableEntandoCustomResource;
import org.entando.kubernetes.model.EntandoDeploymentPhase;
import org.entando.kubernetes.model.app.EntandoBaseCustomResource;

public class EntandoResourceObserver<
        R extends EntandoBaseCustomResource,
        L extends CustomResourceList<R>,
        D extends DoneableEntandoCustomResource<D, R>> implements Watcher<R> {

    private static final Logger LOGGER = Logger.getLogger(EntandoResourceObserver.class.getName());
    private final Map<String, R> cache = new ConcurrentHashMap<>();

    private NonNamespaceOperation<R, L, D, Resource<R, D>> operations;
    private final BiConsumer<Action, R> callback;

    private Executor executor = Executors.newSingleThreadExecutor();

    public EntandoResourceObserver(CustomResourceOperationsImpl<R, L, D> operations, BiConsumer<Action, R> callback) {
        this.operations = operations;
        this.callback = callback;
        cache.putAll(operations.list().getItems().stream().collect(Collectors.toMap(r -> r.getMetadata().getUid(), o -> o)));
        operations.watch(this);
    }

    @Override
    public void eventReceived(Action action, R resource) {
        try {
            if (isNewEvent(resource)) {
                performCallback(action, resource);
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, e, () -> format("Could not process the %s %s/%s", resource.getKind(),
                    resource.getMetadata().getNamespace(), resource.getMetadata().getName()));
        }
    }

    @Override
    public void onClose(KubernetesClientException cause) {
        LOGGER.log(Level.SEVERE, cause, () -> "EntandoResourceObserver closed");
    }

    protected void performCallback(Action action, R resource) {
        System.out.println("received " + action + " for resource " + resource);
        if (action == Action.ADDED || action == Action.MODIFIED) {
            cache.put(resource.getMetadata().getUid(), resource);
            if (resource.getStatus().getEntandoDeploymentPhase() == EntandoDeploymentPhase.REQUESTED) {
                executor.execute(() -> callback.accept(action, resource));
            }
        } else if (action == Action.DELETED) {
            cache.remove(resource.getMetadata().getUid());
        } else {
            LOGGER.log(Level.WARNING, () -> format("EntandoResourceObserver could not process the %s action on the %s %s/%s", action.name(),
                    resource.getKind(), resource.getMetadata().getNamespace(), resource.getMetadata().getName()));
        }
    }

    protected boolean isNewEvent(R resource) {
        boolean isNewEvent = true;
        if (cache.containsKey(resource.getMetadata().getUid())) {
            int knownResourceVersion = Integer.parseInt(cache.get(resource.getMetadata().getUid()).getMetadata().getResourceVersion());
            int receivedResourceVersion = Integer.parseInt(resource.getMetadata().getResourceVersion());
            if (knownResourceVersion > receivedResourceVersion) {
                isNewEvent = false;
            }
        }
        return isNewEvent;
    }
}
