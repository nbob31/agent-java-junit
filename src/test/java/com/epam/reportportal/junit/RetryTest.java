package com.epam.reportportal.junit;

import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Random;

import static org.junit.Assert.fail;

public class RetryTest {

	private static final Logger LOGGER = LoggerFactory.getLogger(RetryTest.class);
	private static final Random random = new Random();

	@Test
	public void failTwo() {
		if (random.nextBoolean()) {
			LOGGER.info("passed");
		} else {
			fail();
			LOGGER.info("failed");
		}
	}
}
