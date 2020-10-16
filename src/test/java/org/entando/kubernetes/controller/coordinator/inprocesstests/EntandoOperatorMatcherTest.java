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

package org.entando.kubernetes.controller.coordinator.inprocesstests;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.HashMap;
import org.entando.kubernetes.controller.EntandoOperatorConfigProperty;
import org.entando.kubernetes.controller.coordinator.EntandoOperatorMatcher;
import org.entando.kubernetes.model.app.EntandoApp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Tags;
import org.junit.jupiter.api.Test;

@Tags({@Tag("in-process"), @Tag("component"), @Tag("pre-deployment")})
class EntandoOperatorMatcherTest {

    @BeforeEach
    @AfterEach
    public void removeJvmSytemProperty() {
        System.getProperties().remove(EntandoOperatorConfigProperty.ENTANDO_K8S_OPERATOR_API_VERSION_RANGE.getJvmSystemProperty());
        System.getProperties().remove(EntandoOperatorConfigProperty.ENTANDO_K8S_OPERATOR_ID.getJvmSystemProperty());
    }

    @Test
    void testOperatorIdVariableMissing() {
        EntandoApp entandoApp = new EntandoApp();
        assertTrue(EntandoOperatorMatcher.matchesThisOperator(entandoApp));
        entandoApp.getMetadata().setAnnotations(new HashMap<>());
        entandoApp.getMetadata().getAnnotations().put(EntandoOperatorMatcher.OPERATOR_ID_ANNOTATION, "myid");
        assertFalse(EntandoOperatorMatcher.matchesThisOperator(entandoApp));
    }

    @Test
    void testOperatorIdAnnotationMissing() {
        EntandoApp entandoApp = new EntandoApp();
        assertTrue(EntandoOperatorMatcher.matchesThisOperator(entandoApp));
        System.setProperty(EntandoOperatorConfigProperty.ENTANDO_K8S_OPERATOR_ID.getJvmSystemProperty(), "myid");
        assertFalse(EntandoOperatorMatcher.matchesThisOperator(entandoApp));
    }

    @Test
    void testOperatorIdActiveAndMatching() {
        System.setProperty(EntandoOperatorConfigProperty.ENTANDO_K8S_OPERATOR_ID.getJvmSystemProperty(), "myid");
        EntandoApp entandoApp = new EntandoApp();
        entandoApp.getMetadata().setAnnotations(Collections.singletonMap(EntandoOperatorMatcher.OPERATOR_ID_ANNOTATION, "myid"));
        assertTrue(EntandoOperatorMatcher.matchesThisOperator(entandoApp));
    }

    @Test
    void testOperatorActiveAndNotMatching() {
        System.setProperty(EntandoOperatorConfigProperty.ENTANDO_K8S_OPERATOR_ID.getJvmSystemProperty(), "myid");
        EntandoApp entandoApp = new EntandoApp();
        entandoApp.getMetadata().setAnnotations(Collections.singletonMap(EntandoOperatorMatcher.OPERATOR_ID_ANNOTATION, "someid"));
        assertFalse(EntandoOperatorMatcher.matchesThisOperator(entandoApp));
    }

    @Test
    void testVersionRangeInactive() {
        EntandoApp entandoApp = new EntandoApp();
        entandoApp.setApiVersion("entando.org/v1");
        assertTrue(EntandoOperatorMatcher.matchesThisOperator(entandoApp));
    }

    @Test
    void testVersionRangeActive() {
        System.setProperty(EntandoOperatorConfigProperty.ENTANDO_K8S_OPERATOR_API_VERSION_RANGE.getJvmSystemProperty(), "1-2");
        EntandoApp entandoApp = new EntandoApp();
        entandoApp.getMetadata().setAnnotations(new HashMap<>());
        entandoApp.setApiVersion("entando.org/v1.0");
        assertTrue(EntandoOperatorMatcher.matchesThisOperator(entandoApp));
        entandoApp.setApiVersion("entando.org/v2.0.0");
        assertTrue(EntandoOperatorMatcher.matchesThisOperator(entandoApp));
        entandoApp.setApiVersion("entando.org/v2.0.1");
        assertFalse(EntandoOperatorMatcher.matchesThisOperator(entandoApp));
    }

    @Test
    void testNullVersionDefaultsToOne() {
        System.setProperty(EntandoOperatorConfigProperty.ENTANDO_K8S_OPERATOR_API_VERSION_RANGE.getJvmSystemProperty(), "1");
        EntandoApp entandoApp = new EntandoApp();
        entandoApp.setApiVersion(null);
        assertTrue(EntandoOperatorMatcher.matchesThisOperator(entandoApp));
    }
}
