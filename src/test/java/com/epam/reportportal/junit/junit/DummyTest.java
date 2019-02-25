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

import org.junit.*;

import static org.junit.Assert.assertTrue;

/**
 * Unit test for simple App.
 */
public class DummyTest {

	@BeforeClass
	public static void beforeClass() throws InterruptedException {
		Thread.sleep(100);
	}

	@Before
	public void before() throws InterruptedException {
		Thread.sleep(100);
	}

	@Test
	public void test1() throws InterruptedException {
		Thread.sleep(100);
		assertTrue(true);
	}

	@Test
	public void test2() throws InterruptedException {
		Thread.sleep(100);
		assertTrue(true);
	}

	@After
	public void after() throws InterruptedException {
		Thread.sleep(100);
	}

	@AfterClass
	public static void afterClass() throws InterruptedException {
		Thread.sleep(100);
	}

}
