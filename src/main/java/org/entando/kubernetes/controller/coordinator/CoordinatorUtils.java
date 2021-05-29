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
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.apiextensions.v1beta1.CustomResourceDefinition;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.entando.kubernetes.controller.spi.common.LabelNames;
import org.entando.kubernetes.model.common.EntandoCustomResource;

public class CoordinatorUtils {

    public static final String NO_IMAGE = "none";
    public static final String ENTANDO_OPERATOR_CONFIG = "entando-operator-config";
    public static final String CONTROLLER_IMAGE_OVERRIDES_CONFIGMAP = "entando-controller-image-overrides";
    public static final String ENTANDO_CRD_NAMES_CONFIGMAP_NAME = "entando-crd-names";

    private CoordinatorUtils() {

    }

    public static OperatorProcessingInstruction resolveProcessingInstruction(EntandoCustomResource resource) {
        return resolveAnnotation(resource, AnnotationNames.PROCESSING_INSTRUCTION)
                .map(value -> OperatorProcessingInstruction
                        .valueOf(value.toUpperCase(Locale.ROOT).replace("-", "_")))
                .orElse(OperatorProcessingInstruction.NONE);
    }

    public static Optional<String> resolveAnnotation(HasMetadata resource, AnnotationNames annotationName) {
        return ofNullable(resource.getMetadata().getAnnotations()).map(map -> map.get(annotationName.getName()));
    }

    public static boolean isOfInterest(CustomResourceDefinition r) {
        return ofNullable(r.getMetadata().getLabels()).map(labels -> labels.containsKey(LabelNames.CRD_OF_INTEREST.getName()))
                .orElse(false);
    }

    public static String keyOf(CustomResourceDefinitionContext definitionContext) {
        return keyOf(definitionContext.getKind(), definitionContext.getGroup());
    }

    private static String keyOf(String kind, String group) {
        return kind + "." + group;
    }

    public static String keyOf(CustomResourceDefinition r) {
        return keyOf(r.getSpec().getNames().getKind(), r.getSpec().getGroup());
    }

    public static String keyOf(EntandoCustomResource r) {
        return keyOf(r.getKind(), extractGroupFrom(r.getApiVersion()));
    }

    public static String keyOf(OwnerReference ownerReference) {
        return keyOf(ownerReference.getKind(), extractGroupFrom(ownerReference.getApiVersion()));
    }

    private static String extractGroupFrom(String apiVersion) {
        return apiVersion.substring(0, apiVersion.indexOf("/"));
    }

    public static Optional<String> resolveValue(ConfigMap map, String key) {
        return ofNullable(map.getData()).flatMap(data -> ofNullable(data.get(key)));
    }

    public static Map<String, String> podLabelsFor(EntandoCustomResource resource) {
        return Map.of(
                LabelNames.RESOURCE_KIND.getName(), resource.getKind(),
                LabelNames.RESOURCE_NAMESPACE.getName(), resource.getMetadata().getNamespace(),
                resource.getKind(), resource.getMetadata().getName());
    }
}
