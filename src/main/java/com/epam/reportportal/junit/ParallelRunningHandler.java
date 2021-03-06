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
package com.epam.reportportal.junit;

import com.epam.reportportal.annotations.TestCaseId;
import com.epam.reportportal.annotations.TestCaseIdKey;
import com.epam.reportportal.annotations.UniqueID;
import com.epam.reportportal.annotations.attribute.Attributes;
import com.epam.reportportal.aspect.StepAspect;
import com.epam.reportportal.junit.utils.SystemAttributesFetcher;
import com.epam.reportportal.listeners.ListenerParameters;
import com.epam.reportportal.listeners.Statuses;
import com.epam.reportportal.service.Launch;
import com.epam.reportportal.service.ReportPortal;
import com.epam.reportportal.service.item.TestCaseIdEntry;
import com.epam.reportportal.service.tree.TestItemTree;
import com.epam.reportportal.utils.AttributeParser;
import com.epam.reportportal.utils.reflect.Accessible;
import com.epam.ta.reportportal.ws.model.*;
import com.epam.ta.reportportal.ws.model.attribute.ItemAttributesRQ;
import com.epam.ta.reportportal.ws.model.issue.Issue;
import com.epam.ta.reportportal.ws.model.launch.StartLaunchRQ;
import com.epam.ta.reportportal.ws.model.log.SaveLogRQ;
import com.nordstrom.automation.junit.*;
import io.reactivex.Maybe;
import io.reactivex.annotations.Nullable;
import org.apache.commons.lang3.reflect.MethodUtils;
import org.junit.*;
import org.junit.internal.runners.model.ReflectiveCallable;
import org.junit.runners.Suite;
import org.junit.runners.model.FrameworkMethod;
import org.junit.runners.model.TestClass;
import rp.com.google.common.annotations.VisibleForTesting;
import rp.com.google.common.base.Function;
import rp.com.google.common.base.Supplier;

import java.io.Serializable;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.Map.Entry;

import static com.epam.reportportal.junit.ParallelRunningContext.ITEM_TREE;
import static com.epam.reportportal.junit.utils.ItemTreeUtils.createItemTreeKey;
import static com.epam.reportportal.junit.utils.ItemTreeUtils.retrieveLeaf;
import static java.util.Optional.ofNullable;
import static rp.com.google.common.base.Strings.isNullOrEmpty;
import static rp.com.google.common.base.Throwables.getStackTraceAsString;

/**
 * MultiThread realization of IListenerHandler. This realization support
 * parallel running of tests and test methods. Main constraint: All test classes
 * in current launch should be unique. (User shouldn't run the same classes
 * twice/or more times in the one launch)
 *
 * @author Aliaksey_Makayed (modified by Andrei_Ramanchuk)
 */
public class ParallelRunningHandler implements IListenerHandler {

	public static final ReportPortal REPORT_PORTAL = ReportPortal.builder().build();

	private ParallelRunningContext context;
	private MemoizingSupplier<Launch> launch;

	/**
	 * Constructor: Instantiate a parallel running handler
	 *
	 * @param parallelRunningContext test execution context manager
	 */
	public ParallelRunningHandler(final ParallelRunningContext parallelRunningContext) {

		context = parallelRunningContext;
		launch = createLaunch();
	}

	protected MemoizingSupplier<Launch> createLaunch() {
		return new MemoizingSupplier<>(() -> {
			final ReportPortal reportPortal = ReportPortal.builder().build();
			StartLaunchRQ rq = buildStartLaunchRq(reportPortal.getParameters());
			return reportPortal.newLaunch(rq);
		});
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void startLaunch() {
		Maybe<String> launchId = launch.get().start();
		StepAspect.addLaunch("default", this.launch.get());
		ITEM_TREE.setLaunchId(launchId);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void stopLaunch() {
		FinishExecutionRQ finishExecutionRQ = new FinishExecutionRQ();
		finishExecutionRQ.setEndTime(Calendar.getInstance().getTime());
		launch.get().finish(finishExecutionRQ);
		launch.reset();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void startRunner(Object runner) {
		StartTestItemRQ rq = buildStartSuiteRq(runner);
		Maybe<String> containerId = getContainerId(runner);
		Maybe<String> itemId;
		if (containerId == null) {
			itemId = launch.get().startTestItem(rq);
		} else {
			itemId = launch.get().startTestItem(containerId, rq);
		}
		context.setTestIdOfTestRunner(runner, itemId);
		StepAspect.setParentId(itemId);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void stopRunner(Object runner) {
		FinishTestItemRQ rq = buildFinishTestItemRq();
		launch.get().finishTestItem(context.getItemIdOfTestRunner(runner), rq);
	}

	@Override
	public void startTest(AtomicTest<FrameworkMethod> testContext) {
		StartTestItemRQ rq = buildStartTestItemRq(testContext);
		Maybe<String> testID = launch.get().startTestItem(context.getItemIdOfTestRunner(testContext.getRunner()), rq);
		context.setTestIdOfTest(testContext, testID);
		StepAspect.setParentId(testID);
	}

	@Override
	public void finishTest(AtomicTest<FrameworkMethod> testContext) {
		FinishTestItemRQ rq = buildFinishTestRq(testContext);
		launch.get().finishTestItem(context.getItemIdOfTest(testContext), rq);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void startTestMethod(Object runner, FrameworkMethod method, ReflectiveCallable callable) {
		StartTestItemRQ rq = buildStartStepRq(method);
		rq.setParameters(createStepParameters(method, runner));
		rq.setTestCaseId(getTestCaseId(method, runner, rq.getCodeRef()).getId());

		Maybe<String> parentId;
		Maybe<String> itemId;
		AtomicTest<FrameworkMethod> test = LifecycleHooks.getAtomicTestOf(runner);
		if (test == null || MethodType.AFTER_CLASS.equals(MethodType.detect(method))) {
			// BeforeClass and AfterClass run independently in JUnit
			parentId = context.getItemIdOfTestRunner(runner);
			itemId = launch.get().startTestItem(parentId, rq);
		} else {
			// Before and After run within test context in JUnit
			parentId = context.getItemIdOfTest(test);
			itemId = launch.get().startTestItem(parentId, rq);
		}
		if (REPORT_PORTAL.getParameters().isCallbackReportingEnabled()) {
			ITEM_TREE.getTestItems().put(createItemTreeKey(method), TestItemTree.createTestItemLeaf(parentId, itemId, 0));
		}
		context.setItemIdOfTestMethod(callable, itemId);
		StepAspect.setParentId(itemId);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void stopTestMethod(Object runner, FrameworkMethod method, ReflectiveCallable callable) {
		String status = context.getStatusOfTestMethod(callable);
		FinishTestItemRQ rq = buildFinishStepRq(method, status);
		Maybe<OperationCompletionRS> finishResponse = launch.get().finishTestItem(context.getItemIdOfTestMethod(callable), rq);
		if (REPORT_PORTAL.getParameters().isCallbackReportingEnabled()) {
			updateTestItemTree(method, finishResponse);
		}
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void markCurrentTestMethod(ReflectiveCallable callable, String status) {
		context.setStatusOfTestMethod(callable, status);
	}

	/**
	 * {@inheritDoc}
	 */
	//TODO Finish fixes here
	@Override
	public void handleTestSkip(AtomicTest<FrameworkMethod> testContext) {
		Maybe<String> itemId = null;
		Object runner = testContext.getRunner();
		FrameworkMethod identity = testContext.getIdentity();
		Method method = identity.getMethod();
		ReflectiveCallable callable;

		// determine if retrying a failed invocation
		Maybe<String> parentId = context.getItemIdOfTest(testContext);

		// if actually ignored
		if (parentId == null) {
			// start ignored test
			startTest(testContext);
			// get test class instance
			Object target = getTargetForRunner(runner);
			// synthesize 'callable' object
			callable = LifecycleHooks.encloseCallable(method, target);
			// start ignored test identity method
			startTestMethod(runner, identity, callable);
		} else {
			// retrieve 'callable' from failed invocation
			callable = LifecycleHooks.getCallableOf(runner, method);
		}

		markCurrentTestMethod(callable, Statuses.SKIPPED);
		stopTestMethod(runner, identity, callable);
	}

	private void updateTestItemTree(FrameworkMethod method, Maybe<OperationCompletionRS> finishResponse) {
		TestItemTree.TestItemLeaf testItemLeaf = retrieveLeaf(method, ITEM_TREE);
		if (testItemLeaf != null) {
			testItemLeaf.setFinishResponse(finishResponse);
		}
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void sendReportPortalMsg(ReflectiveCallable callable, final Throwable thrown) {
		Function<String, SaveLogRQ> function = itemUuid -> {
			SaveLogRQ rq = new SaveLogRQ();
			rq.setItemUuid(itemUuid);
			rq.setLevel("ERROR");
			rq.setLogTime(Calendar.getInstance().getTime());
			if (thrown != null) {
				rq.setMessage(getStackTraceAsString(thrown));
			} else {
				rq.setMessage("Test has failed without exception");
			}
			rq.setLogTime(Calendar.getInstance().getTime());

			return rq;
		};
		ReportPortal.emitLog(function);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public boolean isReportable(FrameworkMethod method) {
		return MethodType.detect(method) != null;
	}

	private enum MethodType {
		STEP(Test.class),
		BEFORE_METHOD(Before.class),
		AFTER_METHOD(After.class),
		BEFORE_CLASS(BeforeClass.class),
		AFTER_CLASS(AfterClass.class);

		private Class<? extends Annotation> clazz;

		MethodType(Class<? extends Annotation> markerAnnotation) {
			clazz = markerAnnotation;
		}

		/**
		 * Detect the type of the specified JUnit method.
		 *
		 * @param method {@link FrameworkMethod} object
		 * @return an instance of this or null
		 */
		static MethodType detect(FrameworkMethod method) {
			for (MethodType type : values()) {
				if (null != method.getAnnotation(type.clazz)) {
					return type;
				}
			}
			return null;
		}
	}

	/**
	 * Get the test item ID for the container of the indicated test item.
	 *
	 * @param runner JUnit test runner
	 * @return container ID for the indicated test item; {@code null} for root test items
	 */
	private Maybe<String> getContainerId(Object runner) {
		Object parent = LifecycleHooks.getParentOf(runner);
		// if not root object
		if (parent != null) {
			return context.getItemIdOfTestRunner(parent);
		}
		return null;
	}

	/**
	 * Extension point to customize launch creation event/request
	 *
	 * @param parameters Launch Configuration parameters
	 * @return Request to ReportPortal
	 */
	protected StartLaunchRQ buildStartLaunchRq(ListenerParameters parameters) {
		StartLaunchRQ rq = new StartLaunchRQ();
		rq.setName(parameters.getLaunchName());
		rq.setStartTime(Calendar.getInstance().getTime());
		rq.setAttributes(parameters.getAttributes());
		rq.setMode(parameters.getLaunchRunningMode());
		rq.getAttributes().addAll(SystemAttributesFetcher.collectSystemAttributes(parameters.getSkippedAnIssue()));

		rq.setRerun(parameters.isRerun());
		if (!isNullOrEmpty(parameters.getRerunOf())) {
			rq.setRerunOf(parameters.getRerunOf());
		}

		if (!isNullOrEmpty(parameters.getDescription())) {
			rq.setDescription(parameters.getDescription());
		}
		return rq;
	}

	/**
	 * Extension point to customize suite creation event/request
	 *
	 * @param runner JUnit suite context
	 * @return Request to ReportPortal
	 */
	protected StartTestItemRQ buildStartSuiteRq(Object runner) {
		StartTestItemRQ rq = new StartTestItemRQ();
		rq.setName(getRunnerName(runner));
		rq.setCodeRef(getCodeRef(runner));
		rq.setStartTime(Calendar.getInstance().getTime());
		rq.setType("SUITE");
		return rq;
	}

	/**
	 * Extension point to customize test creation event/request
	 *
	 * @param test JUnit test context
	 * @return Request to ReportPortal
	 */
	protected StartTestItemRQ buildStartTestItemRq(AtomicTest<FrameworkMethod> test) {
		StartTestItemRQ rq = new StartTestItemRQ();
		rq.setName(test.getDescription().getDisplayName());
		rq.setCodeRef(getCodeRef(test.getRunner()));
		rq.setStartTime(Calendar.getInstance().getTime());
		rq.setType("TEST");
		return rq;
	}

	/**
	 * Extension point to customize test step creation event/request
	 *
	 * @param method JUnit framework method context
	 * @return Request to ReportPortal
	 */
	protected StartTestItemRQ buildStartStepRq(FrameworkMethod method) {
		StartTestItemRQ rq = new StartTestItemRQ();
		rq.setName(method.getName());
		rq.setCodeRef(getCodeRef(method));
		rq.setAttributes(getAttributes(method));
		rq.setDescription(createStepDescription(method));
		rq.setUniqueId(extractUniqueID(method));
		rq.setStartTime(Calendar.getInstance().getTime());
		MethodType type = MethodType.detect(method);
		rq.setType(type == null ? "" : type.name());

		rq.setRetry(isRetry(method));
		return rq;
	}

	/**
	 * Extension point to customize test suite on it's finish
	 *
	 * @param testClass JUnit suite context
	 * @return Request to ReportPortal
	 */
	protected FinishTestItemRQ buildFinishSuiteRq(TestClass testClass) {
		FinishTestItemRQ rq = new FinishTestItemRQ();
		rq.setEndTime(Calendar.getInstance().getTime());
		return rq;
	}

	/**
	 * Extension point to customize test on it's finish
	 *
	 * @param testContext JUnit test context
	 * @return Request to ReportPortal
	 */
	@SuppressWarnings("squid:S4144")
	protected FinishTestItemRQ buildFinishTestRq(AtomicTest<FrameworkMethod> testContext) {
		FinishTestItemRQ rq = buildFinishTestItemRq();
		String status = testContext.getThrowable() == null ? Statuses.PASSED : Statuses.FAILED;
		rq.setStatus(status);
		return rq;
	}

	protected FinishTestItemRQ buildFinishTestItemRq() {
		FinishTestItemRQ rq = new FinishTestItemRQ();
		rq.setEndTime(Calendar.getInstance().getTime());
		return rq;
	}

	/**
	 * Extension point to customize test method on it's finish
	 *
	 * @param method JUnit framework method context
	 * @param status method completion status
	 * @return Request to ReportPortal
	 */
	protected FinishTestItemRQ buildFinishStepRq(FrameworkMethod method, String status) {
		FinishTestItemRQ rq = new FinishTestItemRQ();
		rq.setEndTime(Calendar.getInstance().getTime());
		rq.setStatus((status == null || status.equals("")) ? Statuses.PASSED : status);
		// Allows indicate that SKIPPED is not to investigate items for WS
		if (Statuses.SKIPPED.equals(status) && !ofNullable(launch.get().getParameters().getSkippedAnIssue()).orElse(false)) {
			Issue issue = new Issue();
			issue.setIssueType("NOT_ISSUE");
			rq.setIssue(issue);
		}
		return rq;
	}

	/**
	 * Extension point to customize Report Portal test parameters
	 *
	 * @param method JUnit framework method context
	 * @param runner JUnit test runner context
	 * @return Test/Step Parameters being sent to Report Portal
	 */
	protected List<ParameterResource> createStepParameters(FrameworkMethod method, Object runner) {
		List<ParameterResource> parameters = createMethodParameters(method, runner);
		return parameters.isEmpty() ? null : parameters;
	}

	protected TestCaseIdEntry getTestCaseId(FrameworkMethod method, Object runner, String codeRef) {
		Object target = getTargetForRunner(runner);
		return getTestCaseId(method.getMethod(), target, codeRef);
	}

	protected TestCaseIdEntry getTestCaseId(Method method, Object target, String codeRef) {
		if (target instanceof ArtifactParams) {
			com.google.common.base.Optional<Map<String, Object>> params = ((ArtifactParams) target).getParameters();
			boolean isParametrizedMethod = ofNullable(method.getDeclaredAnnotation(TestCaseId.class)).map(TestCaseId::parametrized)
					.orElse(true);
			if (params.isPresent() && isParametrizedMethod) {
				return retrieveParametrizedTestCaseId(target, params.get(), codeRef);
			}
		}
		return ofNullable(method.getDeclaredAnnotation(TestCaseId.class)).flatMap(annotation -> Optional.of(annotation.value())
				.map(TestCaseIdEntry::new)).orElseGet(() -> new TestCaseIdEntry(codeRef));

	}

	protected TestCaseIdEntry retrieveParametrizedTestCaseId(Object target, Map<String, Object> params, String codeRef) {
		return Arrays.stream(target.getClass().getDeclaredFields())
				.filter(field -> params.containsKey(field.getName()) && field.getDeclaredAnnotation(TestCaseIdKey.class) != null)
				.findFirst()
				.flatMap(testCaseIdField -> retrieveTestCaseId(target, testCaseIdField, codeRef, params.get(testCaseIdField.getName())))
				.orElseGet(() -> new TestCaseIdEntry(codeRef));
	}

	/**
	 * @param target          Current entity
	 * @param testCaseIdField Field marked by {@link TestCaseIdKey}
	 * @param codeRef         Location of the current target entity in the code
	 * @param arguments       Arguments of the parametrized test
	 * @return {@link TestCaseIdEntry}
	 */
	private Optional<TestCaseIdEntry> retrieveTestCaseId(Object target, Field testCaseIdField, String codeRef, Object... arguments) {
		try {
			Object testCaseId = Accessible.on(target).field(testCaseIdField).getValue();
			return Optional.of(new TestCaseIdEntry(String.valueOf(testCaseId)));
		} catch (IllegalAccessError e) {
			//do nothing
		}
		return Optional.empty();
	}

	/**
	 * Assemble execution parameters list for the specified framework method.
	 * <p>
	 * <b>NOTE</b>: To support publication of execution parameters, the client test class must implement the
	 * {@link com.nordstrom.automation.junit.ArtifactParams ArtifactParameters} interface.
	 *
	 * @param method JUnit framework method context
	 * @param runner JUnit test runner context
	 * @return Step Parameters being sent to ReportPortal
	 */
	@SuppressWarnings("squid:S3655")
	private List<ParameterResource> createMethodParameters(FrameworkMethod method, Object runner) {
		List<ParameterResource> result = new ArrayList<>();
		if (!(method.isStatic() || isIgnored(method))) {
			Object target = getTargetForRunner(runner);
			if (target instanceof ArtifactParams) {
				com.google.common.base.Optional<Map<String, Object>> params = ((ArtifactParams) target).getParameters();
				if (params.isPresent()) {
					for (Entry<String, Object> param : params.get().entrySet()) {
						ParameterResource parameter = new ParameterResource();
						parameter.setKey(param.getKey());
						parameter.setValue(Objects.toString(param.getValue(), null));
						result.add(parameter);
					}
				}
			}
		}
		return result;
	}

	/**
	 * Get the JUnit test class instance for the specified class runner.
	 * <p>
	 * <b>NOTE</b>: This shim enables subclasses of this handler to supply custom instances.
	 *
	 * @param runner JUnit class runner
	 * @return JUnit test class instance for specified runner
	 */
	protected Object getTargetForRunner(Object runner) {
		return LifecycleHooks.getTargetForRunner(runner);
	}

	/**
	 * Extension point to customize test step description
	 *
	 * @param method JUnit framework method context
	 * @return Test/Step Description being sent to ReportPortal
	 */
	protected String createStepDescription(FrameworkMethod method) {
		DisplayName itemDisplayName = method.getAnnotation(DisplayName.class);
		return (itemDisplayName != null) ? itemDisplayName.value() : getChildName(method);
	}

	/**
	 * Returns test item ID from annotation if it provided.
	 *
	 * @param method Where to find
	 * @return test item ID or null
	 */
	private static String extractUniqueID(FrameworkMethod method) {
		UniqueID itemUniqueID = method.getAnnotation(UniqueID.class);
		return itemUniqueID != null ? itemUniqueID.value() : null;
	}

	/**
	 * Determine if the specified JUnit framework method is being ignored.
	 *
	 * @param method JUnit framework method context
	 * @return {@code true} if specified method is being ignored; otherwise {@code false}
	 */
	private static boolean isIgnored(FrameworkMethod method) {
		return (null != method.getAnnotation(Ignore.class));
	}

	/**
	 * Determine if the specified JUnit framework method is being retried.
	 *
	 * @param method JUnit framework method context
	 * @return {@code true} if specified method is being retried; otherwise {@code false}
	 */
	private static boolean isRetry(FrameworkMethod method) {
		return (null != method.getAnnotation(RetriedTest.class));
	}

	/**
	 * Get name associated with the specified JUnit runner.
	 *
	 * @param runner JUnit test runner
	 * @return name for runner
	 */
	private static String getRunnerName(Object runner) {
		String name;
		TestClass testClass = LifecycleHooks.getTestClassOf(runner);
		Class<?> javaClass = testClass.getJavaClass();
		if (javaClass != null) {
			name = javaClass.getName();
		} else {
			String role = (null == LifecycleHooks.getParentOf(runner)) ? "Root " : "Context ";
			String type = (runner instanceof Suite) ? "Suite" : "Class";
			name = role + type + " Runner";
		}
		return name;
	}

	/**
	 * Get code reference associated with the specified JUnit runner.
	 *
	 * @param runner JUnit test runner
	 * @return code reference to the runner
	 */
	@Nullable
	private String getCodeRef(Object runner) {
		TestClass testClass = LifecycleHooks.getTestClassOf(runner);
		Class<?> javaClass = testClass.getJavaClass();
		if (javaClass != null) {
			return javaClass.getCanonicalName();
		}
		return null;
	}

	/**
	 * Get code reference associated with the specified JUnit test method.
	 *
	 * @param frameworkMethod JUnit test method
	 * @return code reference to the test method
	 */
	@Nullable
	private String getCodeRef(FrameworkMethod frameworkMethod) {
		return frameworkMethod.getDeclaringClass().getCanonicalName() + "." + frameworkMethod.getName();
	}

	private Set<ItemAttributesRQ> getAttributes(FrameworkMethod frameworkMethod) {
		return ofNullable(frameworkMethod.getMethod()).map(m -> ofNullable(m.getAnnotation(Attributes.class)).map(AttributeParser::retrieveAttributes)
				.orElseGet(Collections::emptySet)).orElseGet(Collections::emptySet);
	}

	/**
	 * Get name of the specified JUnit child object.
	 *
	 * @param child JUnit child object
	 * @return child object name
	 */
	private static String getChildName(Object child) {
		try {
			return (String) MethodUtils.invokeMethod(child, "getName");
		} catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
			return child.toString();
		}
	}

	@VisibleForTesting
	static class MemoizingSupplier<T> implements Supplier<T>, Serializable {
		final Supplier<T> delegate;
		transient volatile boolean initialized;
		transient T value;
		private static final long serialVersionUID = 0L;

		MemoizingSupplier(Supplier<T> delegate) {
			this.delegate = delegate;
		}

		public T get() {
			if (!initialized) {
				synchronized (this) {
					if (!initialized) {
						T t = delegate.get();
						value = t;
						initialized = true;
						return t;
					}
				}
			}

			return value;
		}

		public synchronized void reset() {
			initialized = false;
		}

		public String toString() {
			return "Suppliers.memoize(" + delegate + ")";
		}
	}
}
