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

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.apiextensions.v1beta1.CustomResourceDefinition;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import java.util.Locale;
import java.util.Optional;
import org.entando.kubernetes.model.common.EntandoCustomResource;

public class CoordinatorUtils {

    public static final String ENTANDO_RESOURCE_KIND_LABEL_NAME = "EntandoResourceKind";
    public static final String ENTANDO_RESOURCE_NAMESPACE_LABEL_NAME = "EntandoResourceNamespace";

    public static final String PROCESSING_INSTRUCTION_ANNOTATION_NAME = "entando.org/processing-instruction";
    public static final String CONTROLLER_IMAGE_ANNOTATION_NAME = "entando.org/controller-image";

    public static OperatorProcessingInstruction resolveProcessingInstruction(
            EntandoCustomResource resource) {
        return resolveAnnotation(resource, PROCESSING_INSTRUCTION_ANNOTATION_NAME)
                .map(value -> OperatorProcessingInstruction
                        .valueOf(value.toUpperCase(Locale.ROOT).replace("-", "_")))
                .orElse(OperatorProcessingInstruction.NONE);
    }

    public static Optional<String> resolveAnnotation(HasMetadata resource, String name) {
        return ofNullable(resource.getMetadata().getAnnotations()).map(map -> map.get(name));
    }

    public static String keyOf(CustomResourceDefinitionContext definitionContext) {
        return keyOf(definitionContext.getKind(), definitionContext.getGroup());
    }

    private static String keyOf(String kind, String group) {
        return kind + "." + group;
    }

    public static boolean isOfInterest(CustomResourceDefinition r) {
        return r.getMetadata().getAnnotations().containsKey(CONTROLLER_IMAGE_ANNOTATION_NAME);
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

    public static String resolveControllerImageAnnotation(CustomResourceDefinition r) {
        return resolveAnnotation(r, CONTROLLER_IMAGE_ANNOTATION_NAME).orElse(null);
    }
}
