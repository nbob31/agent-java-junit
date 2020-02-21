package com.epam.reportportal.junit;

import com.nordstrom.automation.junit.JUnitRetryAnalyzer;
import org.junit.runners.model.FrameworkMethod;

public class RetryImpl implements JUnitRetryAnalyzer {

	@Override
	public boolean retry(FrameworkMethod method, Throwable thrown) {
		return thrown != null;
	}
}
