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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import java.util.HashMap;
import java.util.Locale;
import org.entando.kubernetes.model.app.EntandoApp;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Tags;
import org.junit.jupiter.api.Test;

@Tags({@Tag("pre-deployment"), @Tag("unit")})
class CoordinatorUtilsTest {

    @Test
    void testResolveInstruction() {
        EntandoApp app = new EntandoApp();
        assertThat(CoordinatorUtils.resolveProcessingInstruction(app), is(OperatorProcessingInstruction.NONE));
        app.getMetadata().setAnnotations(new HashMap<>());
        assertThat(CoordinatorUtils.resolveProcessingInstruction(app), is(OperatorProcessingInstruction.NONE));
        app.getMetadata().getAnnotations().put(AnnotationNames.PROCESSING_INSTRUCTION.getName(),
                OperatorProcessingInstruction.IGNORE.name().toLowerCase(Locale.ROOT));
        assertThat(CoordinatorUtils.resolveProcessingInstruction(app), is(OperatorProcessingInstruction.IGNORE));
        app.getMetadata().getAnnotations().remove(AnnotationNames.PROCESSING_INSTRUCTION.getName());
        assertThat(CoordinatorUtils.resolveProcessingInstruction(app), is(OperatorProcessingInstruction.NONE));
    }

}
