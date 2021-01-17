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

import io.fabric8.kubernetes.client.CustomResourceList;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.dsl.internal.CustomResourceOperationsImpl;
import java.io.Serializable;
import org.entando.kubernetes.model.DoneableEntandoCustomResource;
import org.entando.kubernetes.model.EntandoBaseCustomResource;
import org.entando.kubernetes.model.EntandoDeploymentPhase;

public class EntandoDeploymentPhaseWatcher<
        S extends Serializable,
        R extends EntandoBaseCustomResource<S>,
        L extends CustomResourceList<R>,
        D extends DoneableEntandoCustomResource<D, R>
        > implements Watcher<R> {

    private final long start = System.currentTimeMillis();
    private final CustomResourceOperationsImpl<R, L, D> operations;

    public EntandoDeploymentPhaseWatcher(CustomResourceOperationsImpl<R, L, D> operations) {
        this.operations = operations;
    }

    public void waitToBeProcessed(R item) {
        Resource<R, D> resource = operations.inNamespace(item.getMetadata().getNamespace())
                .withName(item.getMetadata().getName());
        synchronized (this) {
            try (Watch ignored = resource.watch(this)) {
                while (!(hasBeenProcessed(resource.fromServer().get()) || hasTimedOut())) {
                    this.wait(5000);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Thread interrupted.");
            }
        }

    }

    private boolean hasTimedOut() {
        return System.currentTimeMillis() - start > 60000L;
    }

    @Override
    public void eventReceived(Action action, R r) {
        stateChanged();
    }

    private void stateChanged() {
        synchronized (this) {
            this.notifyAll();
        }
    }

    @Override
    public void onClose(KubernetesClientException e) {
        stateChanged();
    }

    private boolean hasBeenProcessed(R r) {
        return r.getStatus().getEntandoDeploymentPhase() != EntandoDeploymentPhase.REQUESTED;
    }
}
