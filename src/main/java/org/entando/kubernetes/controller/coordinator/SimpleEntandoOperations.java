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

import static org.entando.kubernetes.controller.coordinator.CoordinatorUtils.callIoVulnerable;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.WatcherException;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import java.io.IOException;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.entando.kubernetes.controller.spi.client.SerializedEntandoResource;

public interface SimpleEntandoOperations {

    SimpleEntandoOperations inNamespace(String namespace);

    SimpleEntandoOperations inAnyNamespace();

    Watch watch(SerializedResourceWatcher rldEntandoResourceObserver);

    List<SerializedEntandoResource> list();

    SerializedEntandoResource removeAnnotation(SerializedEntandoResource r, String name);

    SerializedEntandoResource putAnnotation(SerializedEntandoResource r, String name, String value);

    void removeSuccessfullyCompletedPods(SerializedEntandoResource resource);

    CustomResourceDefinitionContext getDefinitionContext();

    String getControllerNamespace();

    class CustomResourceWatcher implements Watcher<String> {

        private static final Logger LOGGER = Logger.getLogger(CustomResourceWatcher.class.getName());
        private final SimpleEntandoOperations operations;
        private final SerializedResourceWatcher observer;

        public CustomResourceWatcher(SimpleEntandoOperations operations, SerializedResourceWatcher observer) {
            this.operations = operations;
            this.observer = observer;
        }

        @Override
        public void eventReceived(Action action, String s) {
            callIoVulnerable(() -> {
                final SerializedEntandoResource r = new ObjectMapper().readValue(s, SerializedEntandoResource.class);
                r.setDefinition(operations.getDefinitionContext());
                observer.eventReceived(action, r);
                return null;
            });

        }

        @Override
        public void onClose(WatcherException cause) {
            if (cause.getMessage().contains("resourceVersion") && cause.getMessage().contains("too old")) {
                LOGGER.log(Level.WARNING, () -> "EntandoResourceObserver closed due to out of date resourceVersion. Reconnecting ... ");
                operations.watch(observer);
            } else {
                LOGGER.log(Level.SEVERE, cause, () -> "EntandoResourceObserver closed. Can't reconnect. The container should restart now.");
                Liveness.dead();
            }
        }

    }

    interface IoVulnerable<T> {

        T call() throws IOException;
    }
}
