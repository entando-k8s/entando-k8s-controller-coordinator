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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.HashMap;
import org.entando.kubernetes.model.app.EntandoApp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Tags;
import org.junit.jupiter.api.Test;

@Tags({@Tag("in-process"), @Tag("unit"), @Tag("pre-deployment")})
class EntandoOperatorMatcherTest {

    @BeforeEach
    @AfterEach
    public void removeJvmSystemProperties() {
        System.getProperties().remove(ControllerCoordinatorProperty.ENTANDO_K8S_OPERATOR_ID.getJvmSystemProperty());
    }

    @Test
    void testOperatorIdVariableMissing() {
        EntandoApp entandoApp = new EntandoApp();
        assertTrue(EntandoOperatorMatcher.matchesThisOperator(entandoApp));
        entandoApp.getMetadata().setAnnotations(new HashMap<>());
        entandoApp.getMetadata().getAnnotations().put(AnnotationNames.OPERATOR_ID_ANNOTATION.getName(), "myid");
        assertFalse(EntandoOperatorMatcher.matchesThisOperator(entandoApp));
    }

    @Test
    void testOperatorIdAnnotationMissing() {
        EntandoApp entandoApp = new EntandoApp();
        assertTrue(EntandoOperatorMatcher.matchesThisOperator(entandoApp));
        System.setProperty(ControllerCoordinatorProperty.ENTANDO_K8S_OPERATOR_ID.getJvmSystemProperty(), "myid");
        assertFalse(EntandoOperatorMatcher.matchesThisOperator(entandoApp));
    }

    @Test
    void testOperatorIdActiveAndMatching() {
        System.setProperty(ControllerCoordinatorProperty.ENTANDO_K8S_OPERATOR_ID.getJvmSystemProperty(), "myid");
        EntandoApp entandoApp = new EntandoApp();
        entandoApp.getMetadata().setAnnotations(Collections.singletonMap(AnnotationNames.OPERATOR_ID_ANNOTATION.getName(), "myid"));
        assertTrue(EntandoOperatorMatcher.matchesThisOperator(entandoApp));
    }

    @Test
    void testOperatorActiveAndNotMatching() {
        System.setProperty(ControllerCoordinatorProperty.ENTANDO_K8S_OPERATOR_ID.getJvmSystemProperty(), "myid");
        EntandoApp entandoApp = new EntandoApp();
        entandoApp.getMetadata().setAnnotations(Collections.singletonMap(AnnotationNames.OPERATOR_ID_ANNOTATION.getName(), "someid"));
        assertFalse(EntandoOperatorMatcher.matchesThisOperator(entandoApp));
    }

    @Test
    void testVersionRangeInactive() {
        EntandoApp entandoApp = new EntandoApp();
        entandoApp.setApiVersion("entando.org/v1");
        assertTrue(EntandoOperatorMatcher.matchesThisOperator(entandoApp));
    }

}
