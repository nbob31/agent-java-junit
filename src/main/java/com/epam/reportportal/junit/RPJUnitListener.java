package com.epam.reportportal.junit;

import org.junit.runner.Description;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunListener;

public class RPJUnitListener extends RunListener {

	private IListenerHandler handler = JUnitInjectorProvider.getInstance().getBean(IListenerHandler.class);

	@Override
	public void testRunStarted(Description description) throws Exception {
		handler.startLaunch();
	}

	@Override
	public void testRunFinished(Result result) throws Exception {
		handler.finishLaunch();
	}

	@Override
	public void testStarted(Description description) throws Exception {
		handler.startTest(description);
	}

	@Override
	public void testFinished(Description description) throws Exception {
		handler.finishTest(description, "PASSED");
	}

	@Override
	public void testFailure(Failure failure) throws Exception {
		handler.sendFailureMessage(failure);
		handler.finishTest(failure.getDescription(), "FAILED");
	}
}
