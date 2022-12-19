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

public enum AnnotationNames {
    PROCESSING_INSTRUCTION("entando.org/processing-instruction"),
    CONTROLLER_IMAGE("entando.org/controller-image"),
    SUPPORTED_CAPABILITIES("entando.org/supported-capabilities"),
    OPERATOR_ID_ANNOTATION("entando.org/operator-id"),
    PROCESSED_BY_OPERATOR_VERSION("entando.org/processed-by-version");
    private final String name;

    AnnotationNames(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

}
