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

import static java.util.Optional.ofNullable;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.apiextensions.v1beta1.CustomResourceDefinition;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.WatcherException;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import io.quarkus.runtime.Startup;
import io.quarkus.runtime.StartupEvent;
import java.io.Serializable;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import javax.inject.Inject;
import org.entando.kubernetes.model.capability.ProvidedCapability;
import org.entando.kubernetes.model.common.EntandoCustomResource;
import org.entando.kubernetes.model.common.EntandoDeploymentPhase;

public class EntandoControllerCoordinator implements Watcher<CustomResourceDefinition> {

    public static final String NO_IMAGE = "none";
    public static final String ENTANDO_OPERATOR_CONFIG = "entando-operator-config";
    public static final String CONTROLLER_IMAGE_OVERRIDES_CONFIGMAP = "entando-controller-image-overrides";
    public static final String SUPPORTED_CAPABILITIES_ANNOTATION = "entando.org/supported-capabilities";
    private final SimpleKubernetesClient client;
    private static final Logger LOGGER = Logger.getLogger(EntandoControllerCoordinator.class.getName());
    private final Map<String, String> derivedControllerImageMap = new ConcurrentHashMap<>();
    private ConfigMap controllerImageOverrides;
    private final Map<String, EntandoResourceObserver> observers = new ConcurrentHashMap<>();
    private CrdNameMapSync crdNameMapSync;

    @Inject
    public EntandoControllerCoordinator(KubernetesClient client) {
        this(new DefaultSimpleKubernetesClient(client));
    }

    public EntandoControllerCoordinator(SimpleKubernetesClient client) {
        this.client = client;
    }

    @Startup
    public void onStartup(StartupEvent e) {
        client.watchControllerConfigMap(ENTANDO_OPERATOR_CONFIG, new ConfigListener());
        this.controllerImageOverrides = client.findOrCreateControllerConfigMap(CONTROLLER_IMAGE_OVERRIDES_CONFIGMAP);
        client.watchControllerConfigMap(CONTROLLER_IMAGE_OVERRIDES_CONFIGMAP, new ControllerImageOverridesWatcher());
        final List<CustomResourceDefinition> customResourceDefinitions = client.loadCustomResourceDefinitionsOfInterest().stream()
                .filter(CoordinatorUtils::isOfInterest)
                .collect(Collectors.toList());
        customResourceDefinitions.forEach(this::processCustomResourceDefinition);
        this.crdNameMapSync = new CrdNameMapSync(client, customResourceDefinitions);
        client.watchCustomResourceDefinitions(this);
        Liveness.alive();
    }

    @SuppressWarnings("unchecked")
    //for testing purposes only, pseudo-deprecated
    public <R extends EntandoCustomResource> EntandoResourceObserver getObserver(Class clss) {
        return observers.get(CoordinatorUtils.keyOf(CustomResourceDefinitionContext.fromCustomResourceType(clss)));
    }

    public void shutdownObservers(int wait, TimeUnit timeUnit) {
        this.observers.values().forEach(observer -> observer.shutDownAndWait(wait, timeUnit));
    }

    private void processCustomResourceDefinition(CustomResourceDefinition r) {
        final String key = CoordinatorUtils.keyOf(r);
        final EntandoResourceObserver existingObserver = observers.get(key);
        if (existingObserver != null && existingObserver.getCrdGeneration() < r.getMetadata().getGeneration()) {
            existingObserver.shutDownAndWait(10, TimeUnit.SECONDS);
            observers.remove(key);
        }
        final String controllerImage = CoordinatorUtils.resolveControllerImageAnnotation(r);
        derivedControllerImageMap.put(r.getMetadata().getName(), controllerImage);
        CoordinatorUtils.resolveAnnotation(r, SUPPORTED_CAPABILITIES_ANNOTATION).ifPresent(capabilities ->
                Arrays.stream(capabilities.split(","))
                        .forEach(s -> derivedControllerImageMap.put(s + ".capability.org", controllerImage)));

        observers.computeIfAbsent(key,
                s -> new EntandoResourceObserver(
                        this.client.getOperations(CustomResourceDefinitionContext.fromCrd(r)),
                        this::startImage,
                        crdNameMapSync,
                        r.getMetadata().getGeneration()));
    }

    private String sanitize(String key) {
        return key.toLowerCase(Locale.ROOT);//.replace("_", "").replace("-", "").replace(".", "");
    }

    private String getControllerImageFor(EntandoCustomResource resource) {
        if (resource instanceof ProvidedCapability) {
            ProvidedCapability pc = (ProvidedCapability) resource;
            return ofNullable(resolveCapabilityFromMap(pc, this.controllerImageOverrides.getData()))
                    .orElse(resolveCapabilityFromMap(pc, this.derivedControllerImageMap));
        } else {
            return ofNullable(resolveCustomControllerFromMap(resource, this.controllerImageOverrides.getData()))
                    .orElse(resolveCustomControllerFromMap(resource, this.derivedControllerImageMap));
        }
    }

    private String resolveCapabilityFromMap(ProvidedCapability pc, Map<String, String> controllerImageMap) {
        return pc.getSpec().getImplementation()
                .flatMap(impl -> ofNullable(
                        controllerImageMap.get(sanitize(impl.name() + "." + impl.getCapability().name() + ".capability.org"))))
                .orElse(controllerImageMap.get(sanitize(pc.getSpec().getCapability().name() + ".capability.org")));
    }

    private String resolveCustomControllerFromMap(EntandoCustomResource r, Map<String, String> controllerImageMap) {
        final String crdName = this.crdNameMapSync.getCrdName(r);
        return controllerImageMap.get(crdName);
    }

    private <S extends Serializable, T extends EntandoCustomResource> void startImage(Action action, T resource) {
        final String controllerImage = getControllerImageFor(resource);
        if (NO_IMAGE.equals(controllerImage)) {
            //A CRD with now Kubernetes semantics
            client.updatePhase(resource, EntandoDeploymentPhase.SUCCESSFUL);
        } else {
            TrustStoreSecretRegenerator.regenerateIfNecessary(client);
            ControllerExecutor executor = new ControllerExecutor(client.getControllerNamespace(), client, controllerImage);
            executor.startControllerFor(action, resource);
        }
    }

    @Override
    public void eventReceived(Action action, CustomResourceDefinition customResourceDefinition) {
        if (CoordinatorUtils.isOfInterest(customResourceDefinition)) {
            processCustomResourceDefinition(customResourceDefinition);
        }
    }

    @Override
    public void onClose(WatcherException e) {
        LOGGER.log(Level.SEVERE, e, () -> "EntandoControllerCoordinator closed. Can't reconnect. The container should restart now.");
        Liveness.dead();
    }

    private class ControllerImageOverridesWatcher implements Watcher<ConfigMap> {

        @Override
        public void eventReceived(Action action, ConfigMap configMap) {
            EntandoControllerCoordinator.this.controllerImageOverrides = configMap;
        }

        @Override
        public void onClose(WatcherException cause) {
            LOGGER.log(Level.SEVERE, cause,
                    () -> "ConfigMapObserver closed. Can't reconnect. The container should restart now.");
            Liveness.dead();
        }
    }
}
