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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.util.Map;
import org.entando.kubernetes.controller.support.common.EntandoOperatorConfig;

public class ImageVersionPreparation {

    private final KubernetesClient kubernetesClient;

    public ImageVersionPreparation(KubernetesClient kubernetesClient) {
        this.kubernetesClient = kubernetesClient;
    }

    public String ensureImageVersion(String imageName, String fallbackVersion) {
        ConfigMap versionConfigMap = getKubernetesClient().configMaps().inNamespace(getConfigMapNamespace())
                .withName(getVersionsConfigMapName())
                .fromServer().get();
        if (versionConfigMap == null) {
            getKubernetesClient().configMaps().inNamespace(getConfigMapNamespace())
                    .createOrReplace(new ConfigMapBuilder().withNewMetadata().withName(
                            getVersionsConfigMapName()).withNamespace(getConfigMapNamespace()).endMetadata()
                            .addToData(imageName, format(
                                    "{\"version\":\"%s\"}",
                                    fallbackVersion)).build());
            return fallbackVersion;
        } else {
            return ensureImageInfoPresent(versionConfigMap, imageName, fallbackVersion);
        }
    }

    private KubernetesClient getKubernetesClient() {
        return kubernetesClient;
    }

    private String getVersionsConfigMapName() {
        return EntandoOperatorConfig.getEntandoDockerImageInfoConfigMap();
    }

    private String getConfigMapNamespace() {
        return EntandoOperatorConfig.getEntandoDockerImageInfoNamespace().orElse(kubernetesClient.getNamespace());
    }

    private String ensureImageInfoPresent(ConfigMap versionConfigMap, String imageName, String fallbackVersion) {
        String imageInfo = versionConfigMap.getData().get(imageName);
        if (imageInfo == null) {
            versionConfigMap.getData().put(imageName, format(
                    "{\"version\":\"%s\"}",
                    fallbackVersion));
            getKubernetesClient().configMaps().inNamespace(versionConfigMap.getMetadata().getNamespace())
                    .withName(imageName).updateStatus(versionConfigMap);
            return fallbackVersion;
        } else {
            return ensureVersionAttributePresent(versionConfigMap, imageInfo, imageName, fallbackVersion);
        }
    }

    @SuppressWarnings("unchecked")
    private String ensureVersionAttributePresent(ConfigMap versionConfigMap, String imageInfo, String imageName, String fallbackVersion) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> map = mapper.readValue(imageInfo, Map.class);
            String version = (String) map.get("version");
            if (version == null) {
                map.put("version", fallbackVersion);
                versionConfigMap.getData().put(imageName, mapper.writeValueAsString(map));
                getKubernetesClient().configMaps().inNamespace(versionConfigMap.getMetadata().getNamespace())
                        .withName(versionConfigMap.getMetadata().getName()).patch(versionConfigMap);
                return fallbackVersion;
            } else {
                return version;
            }
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }

    }
}
