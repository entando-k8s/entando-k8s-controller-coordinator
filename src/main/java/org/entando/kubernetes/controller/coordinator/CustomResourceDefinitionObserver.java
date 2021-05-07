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
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.apiextensions.v1beta1.CustomResourceDefinition;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.WatcherException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.entando.kubernetes.controller.support.controller.ControllerImageResolver;
import org.entando.kubernetes.model.capability.ProvidedCapability;
import org.entando.kubernetes.model.common.EntandoCustomResource;

public class CustomResourceDefinitionObserver implements ControllerImageResolver {

    private static final Logger LOGGER = Logger.getLogger(CustomResourceDefinitionObserver.class.getName());
    public static final String CONTROLLER_IMAGE_ANNOTATION_NAME = "entando.org/controller-image";
    private final KubernetesClient client;
    private final Map<String, String> derivedControllerImageMap = new ConcurrentHashMap<>();
    private ConfigMap crdNameMap;
    private ConfigMap controllerImageOverrides;

    public CustomResourceDefinitionObserver(KubernetesClient client) {
        this.client = client;
        this.crdNameMap = findOrCreateMap(client, "entando-crd-names");
        this.controllerImageOverrides = findOrCreateMap(client, "entando-controller-image-overrides");
        client.configMaps().inNamespace(client.getNamespace()).withName("entando-controller-image-overrides")
                .watch(new Watcher<ConfigMap>() {
                    @Override
                    public void eventReceived(Action action, ConfigMap configMap) {
                        CustomResourceDefinitionObserver.this.controllerImageOverrides = configMap;
                    }

                    @Override
                    public void onClose(WatcherException cause) {
                        LOGGER.log(Level.SEVERE, cause,
                                () -> "ConfigMapObserver closed. Can't reconnect. The container should restart now.");
                        Liveness.dead();
                    }
                });
        client.apiextensions().v1beta1().customResourceDefinitions().list().getItems().stream()
                .filter(crd -> crd.getMetadata().getAnnotations().containsKey(CONTROLLER_IMAGE_ANNOTATION_NAME))
                .forEach(crd -> this.processCustomResourceDefinition(crd, crdNameMap));
        client.configMaps().inNamespace(client.getNamespace()).patch(crdNameMap);
        client.apiextensions().v1beta1().customResourceDefinitions().watch(new Watcher<>() {
            @Override
            public void eventReceived(Action action, CustomResourceDefinition r) {
                if (r.getMetadata().getAnnotations().containsKey(CONTROLLER_IMAGE_ANNOTATION_NAME)) {
                    ConfigMap latestCrdNameMap = findOrCreateMap(client, "entando-crd-names");
                    processCustomResourceDefinition(r, latestCrdNameMap);
                    CustomResourceDefinitionObserver.this.crdNameMap = client.configMaps().inNamespace(client.getNamespace())
                            .patch(latestCrdNameMap);
                }

            }

            @Override
            public void onClose(WatcherException cause) {
                LOGGER.log(Level.SEVERE, cause,
                        () -> "CustomResourceDefinitionObserver closed. Can't reconnect. The container should restart now.");
                Liveness.dead();
            }
        });
    }

    private void processCustomResourceDefinition(CustomResourceDefinition r, ConfigMap latestCrdNameMap) {
        latestCrdNameMap.getData().put(r.getSpec().getNames().getKind() + "." + r.getSpec().getGroup(), r.getMetadata().getName());
        derivedControllerImageMap
                .put(r.getMetadata().getName(), r.getMetadata().getAnnotations().get(CONTROLLER_IMAGE_ANNOTATION_NAME));
        final String supportedCapabilities = r.getMetadata().getAnnotations().get("entando.org/supported-capabilities");
        if (supportedCapabilities != null) {
            Arrays.stream(supportedCapabilities.split(",")).forEach(s -> derivedControllerImageMap
                    .put(s + ".capability.org", r.getMetadata().getAnnotations().get(CONTROLLER_IMAGE_ANNOTATION_NAME)));
        }
    }

    private ConfigMap findOrCreateMap(KubernetesClient client, String name) {
        ConfigMap configMap = client.configMaps().inNamespace(client.getNamespace()).withName(name).get();
        if (configMap == null) {
            configMap = client.configMaps().inNamespace(client.getNamespace()).create(new ConfigMapBuilder()
                    .withNewMetadata()
                    .withName(name)
                    .withNamespace(client.getNamespace())
                    .endMetadata()
                    .build());

        }
        if (configMap.getData() == null) {
            configMap.setData(new HashMap<>());
        }
        return configMap;
    }

    private String sanitize(String key) {
        return key.toLowerCase(Locale.ROOT);//.replace("_", "").replace("-", "").replace(".", "");
    }

    @Override
    public String getControllerImageFor(EntandoCustomResource resource) {
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
        final String apiVersion = r.getApiVersion();
        final String group = apiVersion.substring(0, apiVersion.indexOf("/"));
        final String crdName = this.crdNameMap.getData().get(r.getKind() + "." + group);
        return controllerImageMap.get(crdName);
    }
}
