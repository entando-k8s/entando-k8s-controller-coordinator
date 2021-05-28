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
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import javax.inject.Inject;
import org.entando.kubernetes.controller.spi.client.SerializedEntandoResource;
import org.entando.kubernetes.model.capability.ProvidedCapability;
import org.entando.kubernetes.model.common.EntandoDeploymentPhase;

public class EntandoControllerCoordinator implements Watcher<CustomResourceDefinition> {

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
        client.watchControllerConfigMap(CoordinatorUtils.ENTANDO_OPERATOR_CONFIG, new ConfigListener());
        this.controllerImageOverrides = client.findOrCreateControllerConfigMap(CoordinatorUtils.CONTROLLER_IMAGE_OVERRIDES_CONFIGMAP);
        client.watchControllerConfigMap(CoordinatorUtils.CONTROLLER_IMAGE_OVERRIDES_CONFIGMAP, new ControllerImageOverridesWatcher());
        final List<CustomResourceDefinition> customResourceDefinitions = client.loadCustomResourceDefinitionsOfInterest().stream()
                .filter(CoordinatorUtils::isOfInterest)
                .collect(Collectors.toList());
        this.crdNameMapSync = new CrdNameMapSync(client, customResourceDefinitions);
        customResourceDefinitions.forEach(this::processCustomResourceDefinition);
        client.watchCustomResourceDefinitions(this);
        customResourceDefinitions.forEach(this::startObservingInstances);
        observers.computeIfAbsent(CoordinatorUtils.keyOf(CustomResourceDefinitionContext.fromCustomResourceType(ProvidedCapability.class)),
                s -> new EntandoResourceObserver(
                        this.client.getOperations(CustomResourceDefinitionContext.fromCustomResourceType(ProvidedCapability.class)),
                        this::startImage,
                        crdNameMapSync,
                        1L));

        Liveness.alive();
    }

    private EntandoResourceObserver startObservingInstances(CustomResourceDefinition crd) {
        return observers.computeIfAbsent(CoordinatorUtils.keyOf(crd),
                s1 -> new EntandoResourceObserver(
                        this.client.getOperations(CustomResourceDefinitionContext.fromCrd(crd)),
                        this::startImage,
                        crdNameMapSync,
                        crd.getMetadata().getGeneration()));
    }

    public EntandoResourceObserver getObserver(CustomResourceDefinitionContext context) {
        return observers.get(CoordinatorUtils.keyOf(context));
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
        final Optional<String> controllerImageAnnotation = CoordinatorUtils
                .resolveAnnotation(r, CoordinatorUtils.CONTROLLER_IMAGE_ANNOTATION_NAME);
        controllerImageAnnotation.ifPresent(controllerImage -> derivedControllerImageMap.put(r.getMetadata().getName(), controllerImage));
        String controllerImage = controllerImageAnnotation
                .orElseGet(
                        //TODO consider automatically updating these resources to "successful"
                        () -> CoordinatorUtils.resolveValue(this.controllerImageOverrides, CoordinatorUtils.keyOf(r)).orElseThrow(() ->
                                new IllegalStateException(
                                        format("CRD %s has neither the %s annotation, nor is there and entry in the configmap %s for %s",
                                                r.getMetadata().getName(),
                                                CoordinatorUtils.CONTROLLER_IMAGE_ANNOTATION_NAME,
                                                CoordinatorUtils.CONTROLLER_IMAGE_OVERRIDES_CONFIGMAP,
                                                CoordinatorUtils.keyOf(r)))));

        CoordinatorUtils.resolveAnnotation(r, CoordinatorUtils.SUPPORTED_CAPABILITIES_ANNOTATION)
                .ifPresent(capabilities -> Arrays.stream(capabilities.split(","))
                        .forEach(s -> derivedControllerImageMap.put(s + ".capability.org", controllerImage)));

    }

    private String sanitize(String key) {
        return key.toLowerCase(Locale.ROOT);//.replace("_", "").replace("-", "").replace(".", "");
    }

    public String getControllerImageFor(SerializedEntandoResource resource) {
        if (resource.getKind().equals(ProvidedCapability.class.getSimpleName())) {
            final String s = ofNullable(resolveCapabilityFromMap(resource, this.controllerImageOverrides.getData()))
                    .orElse(resolveCapabilityFromMap(resource, this.derivedControllerImageMap));
            return s;
        } else {
            return ofNullable(resolveCustomControllerFromMap(resource, this.controllerImageOverrides.getData()))
                    .orElse(resolveCustomControllerFromMap(resource, this.derivedControllerImageMap));
        }
    }

    private String resolveCapabilityFromMap(SerializedEntandoResource pc, Map<String, String> controllerImageMap) {
        Optional<String> implementation = Optional.ofNullable((String) pc.getSpec().get("implementation"));
        String capability = (String) pc.getSpec().get("capability");
        final Optional<Map<String, String>> nullableMap = ofNullable(controllerImageMap);
        return implementation
                .flatMap(impl -> nullableMap
                        .flatMap(map -> ofNullable(map.get(sanitize(impl + "." + capability + ".capability.org")))))
                .orElse(nullableMap.map(map -> map.get(sanitize(capability + ".capability.org"))).orElse(null));
    }

    private String resolveCustomControllerFromMap(SerializedEntandoResource r, Map<String, String> controllerImageMap) {
        final String crdName = this.crdNameMapSync.getCrdName(r);
        return ofNullable(controllerImageMap).map(map -> map.get(crdName)).orElse(null);
    }

    private <S extends Serializable, T extends SerializedEntandoResource> void startImage(Action action, T resource) {
        try {
            final String controllerImage = getControllerImageFor(resource);
            if (CoordinatorUtils.NO_IMAGE.equals(controllerImage)) {
                //A CRD with now Kubernetes semantics
                client.updatePhase(resource, EntandoDeploymentPhase.SUCCESSFUL);
            } else {
                TrustStoreSecretRegenerator.regenerateIfNecessary(client);
                ControllerExecutor executor = new ControllerExecutor(client.getControllerNamespace(), client, controllerImage);
                executor.startControllerFor(action, resource);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void eventReceived(Action action, CustomResourceDefinition customResourceDefinition) {
        if (CoordinatorUtils.isOfInterest(customResourceDefinition)) {
            processCustomResourceDefinition(customResourceDefinition);
            startObservingInstances(customResourceDefinition);
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
