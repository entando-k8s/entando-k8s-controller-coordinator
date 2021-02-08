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
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watcher.Action;
import io.quarkus.runtime.StartupEvent;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import javax.enterprise.event.Observes;
import javax.inject.Inject;
import org.entando.kubernetes.client.DefaultSimpleK8SClient;
import org.entando.kubernetes.controller.support.client.SimpleK8SClient;
import org.entando.kubernetes.controller.support.common.EntandoOperatorConfig;
import org.entando.kubernetes.controller.support.common.KubeUtils;
import org.entando.kubernetes.controller.support.controller.ControllerExecutor;
import org.entando.kubernetes.model.DoneableEntandoCustomResource;
import org.entando.kubernetes.model.EntandoBaseCustomResource;
import org.entando.kubernetes.model.EntandoCustomResource;
import org.entando.kubernetes.model.EntandoDeploymentPhase;
import org.entando.kubernetes.model.app.EntandoApp;
import org.entando.kubernetes.model.compositeapp.EntandoCompositeApp;
import org.entando.kubernetes.model.debundle.EntandoDeBundle;
import org.entando.kubernetes.model.externaldatabase.EntandoDatabaseService;
import org.entando.kubernetes.model.infrastructure.EntandoClusterInfrastructure;
import org.entando.kubernetes.model.keycloakserver.EntandoKeycloakServer;
import org.entando.kubernetes.model.link.EntandoAppPluginLink;
import org.entando.kubernetes.model.plugin.EntandoPlugin;

public class EntandoControllerCoordinator {

    private final SimpleK8SClient<?> client;
    private final Map<Class<? extends EntandoCustomResource>, List<?>> observers = new ConcurrentHashMap<>();
    private final SimpleEntandoOperationsRegistry entandoResourceOperationsRegistry;

    @Inject
    public EntandoControllerCoordinator(KubernetesClient client) {
        this(new DefaultSimpleK8SClient(client), new DefaultSimpleEntandoOperationsFactory(client));
    }

    public EntandoControllerCoordinator(SimpleK8SClient<?> client, SimpleEntandoOperationsRegistry registry) {
        this.entandoResourceOperationsRegistry = registry;
        this.client = client;
    }

    public void onStartup(@Observes StartupEvent event) {
        //TODO extract TLS and CA certs and write them to the standard secret names

        addObservers(EntandoKeycloakServer.class, this::startImage);
        addObservers(EntandoClusterInfrastructure.class, this::startImage);
        addObservers(EntandoPlugin.class, this::startImage);
        addObservers(EntandoCompositeApp.class, this::startImage);
        addObservers(EntandoDatabaseService.class, this::startImage);
        addObservers(EntandoApp.class, this::startImage);
        addObservers(EntandoAppPluginLink.class, this::startImage);
        addObservers(EntandoDeBundle.class, (action, entandoDeBundle) -> updateEntandoDeBundleStatus(entandoDeBundle));
        KubeUtils.ready(EntandoControllerCoordinator.class.getSimpleName());
    }

    private void updateEntandoDeBundleStatus(EntandoDeBundle entandoDeBundle) {
        client.entandoResources().updatePhase(entandoDeBundle, EntandoDeploymentPhase.SUCCESSFUL);
    }

    @SuppressWarnings("unchecked")
    public <R extends EntandoCustomResource,
            L extends CustomResourceList<R>,
            D extends DoneableEntandoCustomResource<R, D>> List<EntandoResourceObserver<R, D>> getObserver(Class<R> clss) {
        return (List<EntandoResourceObserver<R, D>>) observers.get(clss);
    }

    @SuppressWarnings("unchecked")
    private <R extends EntandoCustomResource,
            D extends DoneableEntandoCustomResource<R, D>> void addObservers(Class<R> type, BiConsumer<Action, R> consumer) {
        final SimpleEntandoOperations<R, D> operations = this.entandoResourceOperationsRegistry
                .getOperations(type);
        List<EntandoResourceObserver<R, D>> observersForType = new ArrayList<>();
        if (EntandoOperatorConfig.isClusterScopedDeployment()) {
            //This code is essentially impossible to test in a shared cluster
            observersForType.add(new EntandoResourceObserver<>(operations.inAnyNamespace(), consumer));
        } else {
            List<String> namespaces = EntandoOperatorConfig.getNamespacesToObserve();
            if (namespaces.isEmpty()) {
                namespaces.add(client.entandoResources().getNamespace());
            }
            for (String namespace : namespaces) {
                observersForType.add(new EntandoResourceObserver<>(operations.inNamespace(namespace), consumer));
            }
        }
        observers.put(type, observersForType);
    }

    private <S extends Serializable, T extends EntandoBaseCustomResource<S>> void startImage(Action action, T resource) {
        ControllerExecutor executor = new ControllerExecutor(client.entandoResources().getNamespace(), client);
        executor.startControllerFor(action, resource, null);
    }
}
