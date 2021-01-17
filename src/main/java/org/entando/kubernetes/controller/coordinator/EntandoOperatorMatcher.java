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

import com.github.zafarkhaja.semver.Version;
import java.util.Optional;
import java.util.logging.Logger;
import org.entando.kubernetes.controller.EntandoOperatorConfigBase;
import org.entando.kubernetes.controller.EntandoOperatorConfigProperty;
import org.entando.kubernetes.model.EntandoBaseCustomResource;

public class EntandoOperatorMatcher {

    public static final String OPERATOR_ID_ANNOTATION = "entando.org/operator-id";
    private static final Logger LOGGER = Logger.getLogger(EntandoOperatorMatcher.class.getName());

    private EntandoOperatorMatcher() {

    }

    public static boolean matchesThisOperator(EntandoBaseCustomResource<?> r) {
        if (shouldEnforceOperatorId(r) && !hasMyAnnotation(r)) {
            LOGGER.warning(() -> String
                    .format("Operator ID mismatch. Ignoring resource %s:%s/%s", r.getKind(), r.getMetadata().getNamespace(),
                            r.getMetadata().getName()));
            return false;
        }
        if (isVersionCheckingActive() && !isInMyVersionRange(r)) {
            LOGGER.warning(() -> String
                    .format("Operator version mismatch. Ignoring resource %s:%s/%s", r.getKind(), r.getMetadata().getNamespace(),
                            r.getMetadata().getName()));
            return false;
        }
        return true;
    }

    private static boolean shouldEnforceOperatorId(EntandoBaseCustomResource<?> r) {
        //Enforce operatorId checking if either the resource has the annotation or this operator has been configured with an operatorId
        return isPropertyActive(EntandoOperatorConfigProperty.ENTANDO_K8S_OPERATOR_ID) || Optional
                .ofNullable(r.getMetadata().getAnnotations()).map(anns -> anns.containsKey(OPERATOR_ID_ANNOTATION)).orElse(false);
    }

    private static boolean isPropertyActive(EntandoOperatorConfigProperty property) {
        return EntandoOperatorConfigBase.lookupProperty(property).orElse("").trim().length() > 0;
    }

    private static boolean isVersionCheckingActive() {
        return isPropertyActive(EntandoOperatorConfigProperty.ENTANDO_K8S_OPERATOR_API_VERSION_RANGE);
    }

    private static boolean hasMyAnnotation(EntandoBaseCustomResource<?> r) {
        String resourceOperatorId = Optional.ofNullable(r.getMetadata().getAnnotations()).map(anns -> anns.get(OPERATOR_ID_ANNOTATION))
                .orElse(null);
        String myOperatorId = EntandoOperatorConfigBase.lookupProperty(EntandoOperatorConfigProperty.ENTANDO_K8S_OPERATOR_ID).orElse(null);
        return resourceOperatorId != null && resourceOperatorId.equals(myOperatorId);
    }

    private static boolean isInMyVersionRange(EntandoBaseCustomResource<?> r) {
        Version crdVersion = Version.valueOf(
                Optional.ofNullable(r.getApiVersion()).map(s -> fillSemVer(r)).orElse("1.0.0"));
        return EntandoOperatorConfigBase.lookupProperty(EntandoOperatorConfigProperty.ENTANDO_K8S_OPERATOR_API_VERSION_RANGE)
                .map(crdVersion::satisfies).orElse(true);
    }

    private static String fillSemVer(EntandoBaseCustomResource<?> r) {
        String version = r.getApiVersion().substring("entando.org/v".length());
        long count = version.chars().filter(i -> i == '.').count();
        if (count == 0) {
            version = version + ".0.0";
        } else if (count == 1) {
            version = version + ".0";
        }
        return version;
    }

}
