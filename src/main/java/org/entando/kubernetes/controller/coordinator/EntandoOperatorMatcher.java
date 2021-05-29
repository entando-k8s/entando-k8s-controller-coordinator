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

import java.util.logging.Logger;
import org.entando.kubernetes.controller.spi.common.EntandoOperatorConfigBase;
import org.entando.kubernetes.model.common.EntandoCustomResource;

public class EntandoOperatorMatcher {

    private static final Logger LOGGER = Logger.getLogger(EntandoOperatorMatcher.class.getName());

    private EntandoOperatorMatcher() {

    }

    public static boolean matchesThisOperator(EntandoCustomResource r) {
        if (shouldEnforceOperatorId(r) && !hasMyAnnotation(r)) {
            LOGGER.warning(() -> String
                    .format("Operator ID mismatch. Ignoring resource %s:%s/%s", r.getKind(), r.getMetadata().getNamespace(),
                            r.getMetadata().getName()));
            return false;
        }
        return true;
    }

    private static boolean shouldEnforceOperatorId(EntandoCustomResource r) {
        //Enforce operatorId checking if either the resource has the annotation or this operator has been configured with an operatorId
        return isPropertyActive(ControllerCoordinatorProperty.ENTANDO_K8S_OPERATOR_ID)
                || CoordinatorUtils.resolveAnnotation(r, AnnotationNames.OPERATOR_ID_ANNOTATION).isPresent();
    }

    private static boolean isPropertyActive(ControllerCoordinatorProperty property) {
        return EntandoOperatorConfigBase.lookupProperty(property).orElse("").trim().length() > 0;
    }

    private static boolean hasMyAnnotation(EntandoCustomResource r) {
        return CoordinatorUtils.resolveAnnotation(r, AnnotationNames.OPERATOR_ID_ANNOTATION)
                .map(s ->
                        EntandoOperatorConfigBase.lookupProperty(ControllerCoordinatorProperty.ENTANDO_K8S_OPERATOR_ID)
                                .map(s::equals)
                                .orElse(false))
                .orElse(false);
    }

}
