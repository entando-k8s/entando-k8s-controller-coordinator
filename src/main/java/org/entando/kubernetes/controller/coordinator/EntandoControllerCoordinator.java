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
import io.fabric8.kubernetes.client.dsl.internal.CustomResourceOperationsImpl;
import io.quarkus.runtime.StartupEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import javax.enterprise.event.Observes;
import javax.inject.Inject;
import org.entando.kubernetes.controller.EntandoOperatorConfig;
import org.entando.kubernetes.controller.EntandoOperatorConfigProperty;
import org.entando.kubernetes.controller.KubeUtils;
import org.entando.kubernetes.controller.common.ControllerExecutor;
import org.entando.kubernetes.controller.database.EntandoDatabaseServiceController;
import org.entando.kubernetes.model.DoneableEntandoCustomResource;
import org.entando.kubernetes.model.EntandoBaseCustomResource;
import org.entando.kubernetes.model.EntandoResourceOperationsRegistry;
import org.entando.kubernetes.model.app.EntandoApp;
import org.entando.kubernetes.model.compositeapp.EntandoCompositeApp;
import org.entando.kubernetes.model.externaldatabase.EntandoDatabaseService;
import org.entando.kubernetes.model.infrastructure.EntandoClusterInfrastructure;
import org.entando.kubernetes.model.keycloakserver.EntandoKeycloakServer;
import org.entando.kubernetes.model.link.EntandoAppPluginLink;
import org.entando.kubernetes.model.plugin.EntandoPlugin;

public class EntandoControllerCoordinator {

    public static final String ALL_NAMESPACES = "*";
    private final KubernetesClient client;
    private final Map<Class<? extends EntandoBaseCustomResource>, List<?>> observers =
            new ConcurrentHashMap<>();
    private final EntandoResourceOperationsRegistry entandoResourceOperationsRegistry;
    private final EntandoDatabaseServiceController abstractDbAwareController;

    @Inject
    public EntandoControllerCoordinator(KubernetesClient client) {
        this.entandoResourceOperationsRegistry = new EntandoResourceOperationsRegistry(client);
        this.client = client;
        abstractDbAwareController = new EntandoDatabaseServiceController(client);
    }

    public void onStartup(@Observes StartupEvent event) {
        //TODO operator-common ENTANDO_K8S_OPERATOR_DISABLE_PVC_GC which currently reads the property inversely.
        if ("true".equals(System.getenv("ENTANDO_K8S_OPERATOR_DISABLE_PVC_GARBAGE_COLLECTION"))) {
            System.setProperty(EntandoOperatorConfigProperty.ENTANDO_K8S_OPERATOR_DISABLE_PVC_GC.getJvmSystemProperty(), "false");
        } else {
            System.setProperty(EntandoOperatorConfigProperty.ENTANDO_K8S_OPERATOR_DISABLE_PVC_GC.getJvmSystemProperty(), "true");
        }
        addObservers(EntandoKeycloakServer.class, this::startImage);
        addObservers(EntandoClusterInfrastructure.class, this::startImage);
        addObservers(EntandoApp.class, this::startImage);
        addObservers(EntandoPlugin.class, this::startImage);
        addObservers(EntandoAppPluginLink.class, this::startImage);
        addObservers(EntandoCompositeApp.class, this::startImage);
        addObservers(EntandoDatabaseService.class, this.abstractDbAwareController::processEvent);
        KubeUtils.ready(EntandoControllerCoordinator.class.getSimpleName());
    }

    @SuppressWarnings("unchecked")
    public <R extends EntandoBaseCustomResource,
            L extends CustomResourceList<R>,
            D extends DoneableEntandoCustomResource<D, R>> List<EntandoResourceObserver<R, L, D>> getObserver(Class<R> clss) {
        return (List<EntandoResourceObserver<R, L, D>>) observers.get(clss);
    }

    @SuppressWarnings("unchecked")
    private <R extends EntandoBaseCustomResource> void addObservers(Class<R> type, BiConsumer<Action, R> consumer) {
        CustomResourceOperationsImpl operations = this.entandoResourceOperationsRegistry.getOperations(type);
        List<String> namespaces = new ArrayList<>(EntandoOperatorConfig.getNamespacesToObserve());
        EntandoOperatorConfig.getOperatorNamespaceToObserve().ifPresent(s -> namespaces.add(s));
        if (namespaces.isEmpty()) {
            namespaces.add(client.getNamespace());
        }
        List<EntandoResourceObserver<?, ?, ?>> observersForType = new ArrayList<>();
        if (namespaces.stream().anyMatch(s -> s.equals(ALL_NAMESPACES))) {
            //This code is essentially impossible to test in a shared cluster
            observersForType.add(new EntandoResourceObserver<>((CustomResourceOperationsImpl) operations.inAnyNamespace(), consumer));
        } else {
            for (String namespace : namespaces) {
                CustomResourceOperationsImpl namespacedOperations = (CustomResourceOperationsImpl) operations.inNamespace(namespace);
                observersForType.add(new EntandoResourceObserver<>(namespacedOperations, consumer));
            }
        }
        observers.put(type, observersForType);
    }

    private <T extends EntandoBaseCustomResource> void startImage(Action action, T resource) {
        ControllerExecutor executor = new ControllerExecutor(client.getNamespace(), client);
        executor.startControllerFor(action, resource, executor.resolveLatestImageFor(resource.getClass()).orElseThrow(
                IllegalStateException::new));
    }

}
