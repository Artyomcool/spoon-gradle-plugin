package com.squareup.spoon;

import com.android.ddmlib.*;
import com.android.ddmlib.testrunner.IRemoteAndroidTestRunner;
import com.google.common.base.Strings;
import com.squareup.spoon.html.HtmlRenderer;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.util.*;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.squareup.spoon.DeviceTestResult.Status;
import static com.squareup.spoon.SpoonInstrumentationInfo.parseFromFile;
import static com.squareup.spoon.SpoonLogger.logDebug;
import static com.squareup.spoon.SpoonLogger.logInfo;
import static com.squareup.spoon.SpoonUtils.obtainRealDevice;

/** Represents a collection of devices and the test configuration to be executed. */
public class IncrementalSpoonRunner {
	private static final String DEFAULT_TITLE = "Spoon Execution";

	private final String title;
	private final File androidSdk;
	private final File applicationApk;
	private final File instrumentationApk;
	private final File output;
	private final boolean debug;
	private final boolean noAnimations;
	private final int adbTimeout;
	private final String className;
	private final String methodName;
	private final Set<String> serials;
	private final String classpath;
	private final IRemoteAndroidTestRunner.TestSize testSize;
	private final boolean failIfNoDeviceConnected;
	private AndroidDebugBridge adb;
	private SpoonSummary.Builder summary;
	private final Map<String, IncrementalSpoonDeviceRunner> testRunners =
            new HashMap<String, IncrementalSpoonDeviceRunner>();

	private IncrementalSpoonRunner(String title, File androidSdk, File applicationApk, File instrumentationApk,
								   File output, boolean debug, boolean noAnimations, int adbTimeout, Set<String> serials,
								   String classpath, String className, String methodName,
								   IRemoteAndroidTestRunner.TestSize testSize, boolean failIfNoDeviceConnected) {
		this.title = title;
		this.androidSdk = androidSdk;
		this.applicationApk = applicationApk;
		this.instrumentationApk = instrumentationApk;
		this.output = output;
		this.debug = debug;
		this.noAnimations = noAnimations;
		this.adbTimeout = adbTimeout;
		this.className = className;
		this.methodName = methodName;
		this.classpath = classpath;
		this.testSize = testSize;
		this.serials = serials;
		this.failIfNoDeviceConnected = failIfNoDeviceConnected;
	}

	/**
	 * Install and execute the tests on all specified devices.
	 *
	 * @return {@code true} if there were no test failures or exceptions thrown.
	 */
	public void init() {
		checkArgument(applicationApk.exists(), "Could not find application APK.");
		checkArgument(instrumentationApk.exists(), "Could not find instrumentation APK.");

		adb = SpoonUtils.initAdb(androidSdk);

		if (serials.isEmpty()) {
			serials.addAll(SpoonUtils.findAllDevices(adb));
		}
		if (failIfNoDeviceConnected && serials.isEmpty()) {
			throw new RuntimeException("No device(s) found.");
		}


		try {
			FileUtils.deleteDirectory(output);
		} catch (IOException e) {
			throw new RuntimeException("Unable to clean output directory: " + output, e);
		}

		summary = new SpoonSummary.Builder().setTitle(title).start();

		final SpoonInstrumentationInfo testInfo = parseFromFile(instrumentationApk);

		logDebug(debug, "Application: %s from %s", testInfo.getApplicationPackage(),
				applicationApk.getAbsolutePath());
		logDebug(debug, "Instrumentation: %s from %s", testInfo.getInstrumentationPackage(),
				instrumentationApk.getAbsolutePath());

		for (String serial : serials) {
			IncrementalSpoonDeviceRunner testRunner = getTestRunner(serial, testInfo);
			testRunners.put(serial, testRunner);
			IDevice device = SpoonUtils.obtainRealDevice(adb, serial);
			if (!testRunner.install(device)) {
                throw new RuntimeException("Can't install to device " + serial);
            }
		}
	}

	public boolean finish() {
		for (String serial : serials) {
			String safeSerial = SpoonUtils.sanitizeSerial(serial);
			summary.addResult(safeSerial, testRunners.get(serial).finish());
		}
        SpoonSummary build = summary.end().build();
        render(build);
		AndroidDebugBridge.terminate();
        return parseOverallSuccess(build);
	}

	public void runTests(final String className) {
		runTests(className, methodName);
	}

    public void ignoreTests(String text) {
        logInfo(text);
    }

	public void runTests(final String className, final String methodName) {
		int targetCount = serials.size();
        logInfo("Executing %s#%s on %d device(s).", className, methodName, targetCount);

		if (testSize != null) {
			summary.setTestSize(testSize);
		}

		for (String serial : serials) {
			logDebug(debug, "[%s] Starting execution.", serial);
			testRunners.get(serial).run(className, methodName);
			logDebug(debug, "[%s] Execution done.", serial);
		}

		if (!debug) {
			// Clean up anything in the work directory.
			try {
				FileUtils.deleteDirectory(new File(output, SpoonDeviceRunner.TEMP_DIR));
			} catch (IOException ignored) {
			}
		}

	}

	public void render(SpoonSummary summary) {
		new HtmlRenderer(summary, SpoonUtils.GSON, output).render();
	}

	public void clearData(String packageName) throws TimeoutException, AdbCommandRejectedException, ShellCommandUnresponsiveException, IOException, InterruptedException {
		for (String serial : serials) {
			IDevice device = obtainRealDevice(adb, serial);
			device.executeShellCommand("pm clear " + packageName, new NullOutputReceiver());
            Thread.sleep(2000);
		}
	}

	public void forceStop(String packageName) throws TimeoutException, AdbCommandRejectedException, ShellCommandUnresponsiveException, IOException {
		for (String serial : serials) {
			IDevice device = obtainRealDevice(adb, serial);
			device.executeShellCommand("adb shell am force-stop " + packageName, new NullOutputReceiver());
		}
	}

	/** Returns {@code false} if a test failed on any device. */
	public static boolean parseOverallSuccess(SpoonSummary summary) {
		for (DeviceResult result : summary.getResults().values()) {
			if (result.getInstallFailed()) {
				return false; // App and/or test installation failed.
			}
			if (!result.getExceptions().isEmpty() && result.getTestResults().isEmpty()) {
				return false; // No tests run and top-level exception present.
			}
			for (DeviceTestResult methodResult : result.getTestResults().values()) {
				if (methodResult.getStatus() != Status.PASS) {
					return false; // Individual test failure.
				}
			}
		}
		return true;
	}

	private IncrementalSpoonDeviceRunner getTestRunner(String serial, SpoonInstrumentationInfo testInfo) {
		return new IncrementalSpoonDeviceRunner(androidSdk, applicationApk, instrumentationApk, output, serial,
				debug, noAnimations, adbTimeout, classpath, testInfo, testSize);
	}

	/** Build a test suite for the specified devices and configuration. */
	public static class Builder {
		private String title = DEFAULT_TITLE;
		private File androidSdk;
		private File applicationApk;
		private File instrumentationApk;
		private File output;
		private boolean debug = false;
		private Set<String> serials;
		private String classpath = System.getProperty("java.class.path");
		private String className;
		private String methodName;
		private boolean noAnimations;
		private IRemoteAndroidTestRunner.TestSize testSize;
		private int adbTimeout;
		private boolean failIfNoDeviceConnected;

		/** Identifying title for this execution. */
		public Builder setTitle(String title) {
			checkNotNull(title, "Title cannot be null.");
			this.title = title;
			return this;
		}

		/** Path to the local Android SDK directory. */
		public Builder setAndroidSdk(File androidSdk) {
			checkNotNull(androidSdk, "SDK path not specified.");
			checkArgument(androidSdk.exists(), "SDK path does not exist.");
			this.androidSdk = androidSdk;
			return this;
		}

		/** Path to application APK. */
		public Builder setApplicationApk(File apk) {
			checkNotNull(apk, "APK path not specified.");
			checkArgument(apk.exists(), "APK path does not exist.");
			this.applicationApk = apk;
			return this;
		}

		/** Path to instrumentation APK. */
		public Builder setInstrumentationApk(File apk) {
			checkNotNull(apk, "Instrumentation APK path not specified.");
			checkArgument(apk.exists(), "Instrumentation APK path does not exist.");
			this.instrumentationApk = apk;
			return this;
		}

		/** Path to output directory. */
		public Builder setOutputDirectory(File output) {
			checkNotNull(output, "Output directory not specified.");
			this.output = output;
			return this;
		}

		/** Whether or not debug logging is enabled. */
		public Builder setDebug(boolean debug) {
			this.debug = debug;
			return this;
		}

		/** Whether or not animations are enabled. */
		public Builder setNoAnimations(boolean noAnimations) {
			this.noAnimations = noAnimations;
			return this;
		}

		/** Set ADB timeout. */
		public Builder setAdbTimeout(int value) {
			this.adbTimeout = value;
			return this;
		}

		/** Add a device serial for test execution. */
		public Builder addDevice(String serial) {
			checkNotNull(serial, "Serial cannot be null.");
			checkArgument(serials == null || !serials.isEmpty(), "Already marked as using all devices.");
			if (serials == null) {
				serials = new LinkedHashSet<String>();
			}
			serials.add(serial);
			return this;
		}

		/** Use all currently attached device serials when executed. */
		public Builder useAllAttachedDevices() {
			if (this.serials != null) {
				throw new IllegalStateException("Serial list already contains entries.");
			}
			if (this.androidSdk == null) {
				throw new IllegalStateException("SDK must be set before calling this method.");
			}
			this.serials = new HashSet<String>();
			return this;
		}

		/** Classpath to use for new JVM processes. */
		public Builder setClasspath(String classpath) {
			checkNotNull(classpath, "Classpath cannot be null.");
			this.classpath = classpath;
			return this;
		}

		public Builder setClassName(String className) {
			this.className = className;
			return this;
		}

		public Builder setTestSize(IRemoteAndroidTestRunner.TestSize testSize) {
			this.testSize = testSize;
			return this;
		}

		public Builder setFailIfNoDeviceConnected(boolean failIfNoDeviceConnected) {
			this.failIfNoDeviceConnected = failIfNoDeviceConnected;
			return this;
		}

		public Builder setMethodName(String methodName) {
			this.methodName = methodName;
			return this;
		}

		public IncrementalSpoonRunner build() {
			checkNotNull(androidSdk, "SDK is required.");
			checkArgument(androidSdk.exists(), "SDK path does not exist.");
			checkNotNull(applicationApk, "Application APK is required.");
			checkNotNull(instrumentationApk, "Instrumentation APK is required.");
			checkNotNull(output, "Output path is required.");
			checkNotNull(serials, "Device serials are required.");
			if (!Strings.isNullOrEmpty(methodName)) {
				checkArgument(!Strings.isNullOrEmpty(className),
						"Must specify class name if you're specifying a method name.");
			}

			return new IncrementalSpoonRunner(title, androidSdk, applicationApk, instrumentationApk, output, debug,
					noAnimations, adbTimeout, serials, classpath, className, methodName, testSize,
					failIfNoDeviceConnected);
		}
	}

}
