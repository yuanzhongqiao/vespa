// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.schema.derived;

import com.yahoo.schema.parser.ParseException;
import org.junit.Test;

import java.io.IOException;

/**
 * @author bratseth
 */
public class IntegerAttributeToStringIndexTestCase extends AbstractExportingTestCase {
    @Test
    public void testIt() throws IOException, ParseException {
        assertCorrectDeriving("integerattributetostringindex");
    }
}