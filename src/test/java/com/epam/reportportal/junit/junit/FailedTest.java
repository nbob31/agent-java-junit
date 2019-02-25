/*
 * Copyright 2018 EPAM Systems
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.epam.reportportal.junit.junit;

import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class FailedTest {

    @Rule
    public ExpectedException thrown = ExpectedException.none();

    @Test
    public void testFailureWithRule() {
        thrown.expect(AssertionError.class);
        Assert.fail();
    }

    @Test(expected = AssertionError.class)
    public void testFailureWithCustomMessage() {
        Assert.assertEquals("Failure msg", 2, 1);
    }

    @Test(expected = AssertionError.class)
    public void testFailure() {
        Assert.assertEquals(2, 1);
    }
    
    @Test
    @Ignore
    public void testIgnore() {
    	Assert.assertEquals(1,  1);
    }
}
