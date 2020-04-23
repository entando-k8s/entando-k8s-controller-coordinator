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

import static java.lang.String.format;

import com.google.common.base.Strings;
import io.fabric8.kubernetes.client.CustomResourceList;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.dsl.internal.CustomResourceOperationsImpl;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.entando.kubernetes.model.DoneableEntandoCustomResource;
import org.entando.kubernetes.model.EntandoBaseCustomResource;
import org.entando.kubernetes.model.EntandoDeploymentPhase;
import org.entando.kubernetes.model.compositeapp.EntandoCompositeApp;

public class EntandoResourceObserver<
        R extends EntandoBaseCustomResource,
        L extends CustomResourceList<R>,
        D extends DoneableEntandoCustomResource<D, R>> implements Watcher<R> {

    private static final Logger LOGGER = Logger.getLogger(EntandoResourceObserver.class.getName());
    private final Map<String, R> cache = new ConcurrentHashMap<>();
    private final BiConsumer<Action, R> callback;
    private Executor executor = Executors.newSingleThreadExecutor();

    public EntandoResourceObserver(CustomResourceOperationsImpl<R, L, D> operations, BiConsumer<Action, R> callback) {
        this.callback = callback;
        processExistingRequestedEntandoResources(operations);
        operations.watch(this);
    }

    private void processExistingRequestedEntandoResources(CustomResourceOperationsImpl<R, L, D> operations) {
        List<R> items = operations.list().getItems();
        EntandoDeploymentPhaseWatcher<R, L, D> entandoDeploymentPhaseWatcher = new EntandoDeploymentPhaseWatcher<>(operations);
        for (R item : items) {
            if (item.getStatus().getEntandoDeploymentPhase() == EntandoDeploymentPhase.REQUESTED) {
                eventReceived(Action.ADDED, item);
                entandoDeploymentPhaseWatcher.waitToBeProcessed(item);
            }
            cache.put(item.getMetadata().getUid(), item);
        }
    }

    @Override
    public void eventReceived(Action action, R resource) {
        try {
            if (isNewEvent(resource) && isNotOwnedByCompositeApp(resource)) {
                performCallback(action, resource);
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, e, () -> format("Could not process the %s %s/%s", resource.getKind(),
                    resource.getMetadata().getNamespace(), resource.getMetadata().getName()));
        }
    }

    private boolean isNotOwnedByCompositeApp(R resource) {
        return resource.getMetadata().getOwnerReferences().stream().noneMatch(ownerReference -> ownerReference.getKind().equals(
                EntandoCompositeApp.class.getSimpleName()));
    }

    @Override
    public void onClose(KubernetesClientException cause) {
        LOGGER.log(Level.WARNING, cause, () -> "EntandoResourceObserver closed");
    }

    protected void performCallback(Action action, R resource) {
        logAction(Level.INFO, "Received %s for resource %s %s/%s", action, resource);
        if (action == Action.ADDED || action == Action.MODIFIED) {
            cache.put(resource.getMetadata().getUid(), resource);
            if (resource.getStatus().getEntandoDeploymentPhase() == EntandoDeploymentPhase.REQUESTED) {
                executor.execute(() -> callback.accept(action, resource));
            } else if (resource.getStatus().getEntandoDeploymentPhase().equals(EntandoDeploymentPhase.SUCCESSFUL) &&
                    !Strings.isNullOrEmpty(resource.getMetadata().getDeletionTimestamp())) {
                executor.execute(() -> callback.accept(Action.DELETED, resource));
            }
        } else if (action == Action.DELETED) {
            cache.remove(resource.getMetadata().getUid());
        } else {
            logAction(Level.WARNING, "EntandoResourceObserver could not process the %s action on the %s %s/%s", action, resource);
        }
    }

    private void logAction(Level info, String format, Action action, R resource) {
        LOGGER.log(info,
                () -> format(format, action.name(), resource.getKind(), resource.getMetadata().getNamespace(),
                        resource.getMetadata().getName()));
    }

    protected boolean isNewEvent(R newResource) {
        boolean isNewEvent = true;
        if (cache.containsKey(newResource.getMetadata().getUid())) {
            R oldResource = cache.get(newResource.getMetadata().getUid());
            int knownResourceVersion = Integer.parseInt(oldResource.getMetadata().getResourceVersion());
            int receivedResourceVersion = Integer.parseInt(newResource.getMetadata().getResourceVersion());
            if (knownResourceVersion > receivedResourceVersion) {
                isNewEvent = false;
            }
        }
        return isNewEvent;
    }

}
