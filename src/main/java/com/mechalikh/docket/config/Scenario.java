/**
 *     Docket: a discrete-event simulator for generative AI inference serving.
 *
 *     This file is part of Docket.
 *
 *     Docket is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     Docket is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with Docket. If not, see <http://www.gnu.org/licenses/>.
 **/
package com.mechalikh.docket.config;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.mechalikh.docket.cost.ExecutionCostModel;
import com.mechalikh.docket.cost.RooflineAnalyticalCostModel;
import com.mechalikh.docket.domain.GpuProfile;
import com.mechalikh.docket.domain.ModelProfile;
import com.mechalikh.docket.domain.Provenance;
import com.mechalikh.docket.domain.TraceRecord;
import com.mechalikh.docket.engine.ReplicaSpec;
import com.mechalikh.docket.engine.WorkloadPlan;
import com.mechalikh.docket.policy.AdmissionPolicy;
import com.mechalikh.docket.policy.CacheCapacityAdmission;
import com.mechalikh.docket.policy.CompositeAdmission;
import com.mechalikh.docket.policy.FirstComeFirstServed;
import com.mechalikh.docket.policy.LeastPredictedWorkRouting;
import com.mechalikh.docket.policy.LeastQueuedRouting;
import com.mechalikh.docket.policy.PriorityFirst;
import com.mechalikh.docket.policy.PredictedDeadlineAdmission;
import com.mechalikh.docket.policy.QueueLimitAdmission;
import com.mechalikh.docket.policy.RoundRobinRouting;
import com.mechalikh.docket.policy.RoutingPolicy;
import com.mechalikh.docket.policy.SchedulingPolicy;
import com.mechalikh.docket.policy.ShortestExpectedFirst;
import com.mechalikh.docket.policy.ShortestOracleFirst;
import com.mechalikh.docket.policy.UnlimitedAdmission;

/**
 * The experiment a settings file describes, read once and then used to build every repetition.
 * <p>
 * The loader is strict on purpose. A scenario file that misses a required setting, spells a
 * distribution attribute wrong, or asks for a precision the device does not declare is refused
 * with a message that names the key, because a silently defaulted value in a published result is
 * indistinguishable from a designed one.
 * <p>
 * Units are part of the key names: memory is configured in GiB, following the way a runtime
 * reports device capacity, and bandwidth and throughput in decimal units, following the way a
 * datasheet writes them.
 *
 * @author Charafeddine Mechalikh
 * @since Docket 0.1
 */
public class Scenario {

	/** Name written in the artifacts, and used as the run directory. */
	protected final String name;
	/** Simulated seconds the run lasts, which caps the clock of the engine. */
	protected final double durationSeconds;
	/** Seed of the random streams of the first repetition. */
	protected final long seed;
	/** How many times the workload is replayed with a different seed. */
	protected final int repetitions;
	/** Directory the artifacts are written to. */
	protected final Path outputDir;
	/** Seconds between two resource samples, zero disables the time series. */
	protected final double samplingIntervalSeconds;
	/** Model served by the deployment. */
	protected final ModelProfile model;
	/** Device every replica runs on. */
	protected final GpuProfile device;
	/** One specification per replica. */
	protected final List<ReplicaSpec> replicas;
	/** Workload the source generates. */
	protected final WorkloadPlan workload;
	/** Policy that decides whether a request enters the service. */
	protected final AdmissionPolicy admission;
	/** Policy that chooses the replica. */
	protected final RoutingPolicy routing;
	/** Policy that orders the waiting queue. */
	protected final SchedulingPolicy scheduling;
	/** Model that prices the execution steps. */
	protected final ExecutionCostModel cost;
	/** The settings this scenario was built from, echoed into the artifacts. */
	protected final Settings settings;

	/**
	 * Holds an assembled scenario.
	 *
	 * @param name                    experiment name
	 * @param durationSeconds         simulated duration
	 * @param seed                    first seed
	 * @param repetitions             repetition count
	 * @param outputDir               artifact directory
	 * @param samplingIntervalSeconds resource sampling period
	 * @param model                   served model
	 * @param device                  device template
	 * @param replicas                replica specifications
	 * @param workload                generated workload
	 * @param admission               admission policy
	 * @param routing                 routing policy
	 * @param scheduling              scheduling policy
	 * @param cost                    execution cost model
	 * @param settings                source settings
	 */
	protected Scenario(String name, double durationSeconds, long seed, int repetitions, Path outputDir,
			double samplingIntervalSeconds, ModelProfile model, GpuProfile device, List<ReplicaSpec> replicas,
			WorkloadPlan workload, AdmissionPolicy admission, RoutingPolicy routing, SchedulingPolicy scheduling,
			ExecutionCostModel cost, Settings settings) {
		this.name = name;
		this.durationSeconds = durationSeconds;
		this.seed = seed;
		this.repetitions = repetitions;
		this.outputDir = outputDir;
		this.samplingIntervalSeconds = samplingIntervalSeconds;
		this.model = model;
		this.device = device;
		this.replicas = Collections.unmodifiableList(replicas);
		this.workload = workload;
		this.admission = admission;
		this.routing = routing;
		this.scheduling = scheduling;
		this.cost = cost;
		this.settings = settings;
	}

	/**
	 * Builds the experiment a settings object describes.
	 *
	 * @param settings the loaded settings, whose keys are all expected to be read
	 * @param baseDir  directory relative paths are resolved against, {@code null} for the current
	 *                 one
	 * @return the validated scenario
	 * @throws IOException              when a trace file cannot be read
	 * @throws IllegalArgumentException when a setting is missing, unknown, or inconsistent
	 */
	public static Scenario load(Settings settings, Path baseDir) throws IOException {
		String name = settings.text("experiment.name", "run");
		double duration = settings.number("experiment.durationSeconds");
		if (duration <= 0 || Double.isInfinite(duration))
			throw new IllegalArgumentException(
					"\"experiment.durationSeconds\" must be a positive number of simulated seconds, found " + duration);
		long seed = (long) settings.number("experiment.seed", 1);
		int repetitions = settings.integer("experiment.repetitions", 1);
		if (repetitions < 1)
			throw new IllegalArgumentException("An experiment runs at least one repetition, found " + repetitions);
		// The output directory is resolved against the working directory, not against the scenario
		// file: where results land belongs to the invocation that produced them, while an input
		// such as a trace belongs to the scenario and is read next to it.
		Path output = path(settings.text("experiment.outputDir", "output"), null);
		double sampling = settings.number("metrics.samplingIntervalSeconds", 1);
		if (sampling < 0)
			throw new IllegalArgumentException("The sampling interval cannot be negative, found " + sampling);

		ModelProfile model = model(settings);
		GpuProfile device = device(settings, model);
		List<ReplicaSpec> replicas = replicas(settings, model, device);
		WorkloadPlan workload = workload(settings, model, baseDir);
		ExecutionCostModel cost = costModel(settings, replicas);

		Scenario scenario = new Scenario(name, duration, seed, repetitions, output, sampling, model, device, replicas,
				workload, admission(settings), routing(settings), scheduling(settings), cost, settings);
		scenario.validate();
		return scenario;
	}

	/**
	 * Builds the model profile.
	 *
	 * @param settings the settings
	 * @return the model
	 */
	private static ModelProfile model(Settings settings) {
		ModelProfile model = new ModelProfile();
		model.setId(settings.text("model.id"));
		model.setName(settings.text("model.name", model.getId()));
		model.setVersion(settings.text("model.version", ""));
		model.setArchitecture(settings.text("model.architecture", "dense-transformer"));
		model.setParameterCount(count(settings, "model.parameterCount"));
		model.setLayers(settings.integer("model.layers"));
		model.setHiddenDimension(settings.integer("model.hiddenDimension"));
		model.setAttentionHeads(settings.integer("model.attentionHeads"));
		model.setKvHeads(settings.integer("model.kvHeads", model.getAttentionHeads()));
		model.setHeadDimension(settings.integer("model.headDimension", 0));
		model.setFeedForwardDimension(settings.integer("model.feedForwardDimension", 0));
		model.setVocabularySize(settings.integer("model.vocabularySize", 0));
		model.setMaxContextTokens(settings.integer("model.maxContextTokens"));
		model.setPrecision(settings.text("model.precision", "fp16").toLowerCase(Locale.ROOT));
		model.setBytesPerParameter(settings.number("model.bytesPerParameter", bytesPerScalar(model.getPrecision())));
		model.setKvDataType(settings.text("model.kvDataType", model.getPrecision()).toLowerCase(Locale.ROOT));
		model.setKvBytesPerScalar(
				settings.number("model.kvBytesPerScalar", bytesPerScalar(model.getKvDataType())));
		model.setQuantization(settings.text("model.quantization", "none"));
		model.setActivationBytesPerSequence(settings.number("model.activationBytesPerSequence", 0));
		model.setActiveParameterFraction(settings.number("model.activeParameterFraction", 1));
		model.setProvenance(Provenance.parse(settings.text("model.provenance", "synthetic")));
		model.applyDefaults();
		return model;
	}

	/**
	 * Builds the device template every replica shares.
	 *
	 * @param settings the settings
	 * @param model    the model it must be able to serve, used for the precision check
	 * @return the device
	 */
	private static GpuProfile device(Settings settings, ModelProfile model) {
		GpuProfile device = new GpuProfile();
		device.setId(settings.text("device.id"));
		device.setDeviceType(settings.text("device.type", "accelerator"));
		device.setMemoryBytes(settings.bytes("device.memoryGiB", "GiB"));
		device.setUnusableMemoryFraction(settings.number("device.unusableMemoryFraction", 0.05));
		if (device.getUnusableMemoryFraction() < 0 || device.getUnusableMemoryFraction() >= 1)
			throw new IllegalArgumentException("\"device.unusableMemoryFraction\" is a fraction in [0, 1), found "
					+ device.getUnusableMemoryFraction());
		device.setMemoryBandwidthBytesPerSecond(
				settings.number("device.memoryBandwidthGBs") * 1e9);
		Map<String, String> achieved = settings.distributionAttributes("device.achievedTFLOPS");
		if (achieved.isEmpty())
			throw new IllegalArgumentException("The device needs at least one achieved throughput, written as "
					+ "\"device.achievedTFLOPS.fp16=312\"; the execution model divides by an achieved value and "
					+ "refuses a peak one");
		for (Map.Entry<String, String> entry : achieved.entrySet())
			device.addAchievedTeraFlops(entry.getKey(), Double.parseDouble(entry.getValue()));
		for (Map.Entry<String, String> entry : settings.distributionAttributes("device.peakTFLOPS").entrySet())
			device.addPeakTeraFlops(entry.getKey(), Double.parseDouble(entry.getValue()));
		device.setIdlePowerWatts(settings.number("device.idlePowerWatts", 0));
		device.setActivePowerWatts(settings.number("device.activePowerWatts", 0));
		device.setKernelLaunchOverheadSeconds(settings.millisToSeconds("device.kernelLaunchOverheadMs", 0));
		device.setHostToDeviceBandwidthBytesPerSecond(
				settings.number("device.hostToDeviceBandwidthGBs", 0) * 1e9);
		device.setHostTransferLatencySeconds(settings.millisToSeconds("device.hostTransferLatencyMs", 0));
		device.setProvenance(Provenance.parse(settings.text("device.provenance", "synthetic")));
		device.setNote(settings.text("device.note", ""));
		if (!device.declaredPrecisions().contains(model.getPrecision()))
			throw new IllegalArgumentException("The model is served in \"" + model.getPrecision() + "\" but \""
					+ device.getId() + "\" declares achieved throughputs only for " + device.declaredPrecisions()
					+ "; declare device.achievedTFLOPS." + model.getPrecision() + " or change model.precision");
		return device;
	}

	/**
	 * Builds the replicas of the deployment.
	 * <p>
	 * {@code replicas.count} is the number of identical devices, not of processes sharing one
	 * device: the service refuses two replicas on the same device because each would count the
	 * whole memory as its own.
	 *
	 * @param settings the settings
	 * @param model    the served model
	 * @param device   the device template
	 * @return one specification per replica
	 */
	private static List<ReplicaSpec> replicas(Settings settings, ModelProfile model, GpuProfile device) {
		int count = settings.integer("replicas.count", 1);
		if (count < 1)
			throw new IllegalArgumentException("The deployment needs at least one replica, found " + count);
		List<ReplicaSpec> specs = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			ReplicaSpec spec = new ReplicaSpec();
			spec.setModel(model);
			spec.setDevice(count == 1 ? device : withId(device, device.getId() + "-" + (i + 1)));
			spec.setServiceId(settings.text("experiment.name", "run"));
			spec.setMaxBatchSize(settings.integer("replica.maxBatchSize", 64));
			spec.setMaxPrefillChunkTokens(settings.integer("replica.maxPrefillChunkTokens", 2048));
			spec.setAllowPreemption(settings.flag("replica.allowPreemption", true));
			spec.setVictimOrder(ReplicaSpec.VictimOrder
					.from(settings.text("replica.victimOrder", ReplicaSpec.VictimOrder.NEWEST_FIRST.label())));
			spec.setAbortOnDeadlineMiss(settings.flag("replica.abortOnDeadlineMiss", false));
			spec.setTokensPerBlock(settings.integer("replica.tokensPerBlock", 16));
			spec.setRuntimeOverheadBytes(settings.bytesOrZero("replica.runtimeOverheadGiB", "GiB"));
			spec.setReservedActivationSlots(settings.integer("replica.reservedActivationSlots", 0));
			spec.validate();
			specs.add(spec);
		}
		return specs;
	}

	/**
	 * Returns a copy of a device carrying another identifier, so that replicas of one template are
	 * distinct devices.
	 *
	 * @param device the template
	 * @param id     the identifier to give the copy
	 * @return the copy
	 */
	private static GpuProfile withId(GpuProfile device, String id) {
		GpuProfile copy = device.copy();
		copy.setId(id);
		return copy;
	}

	/**
	 * Builds the workload plan.
	 *
	 * @param settings the settings
	 * @param model    the served model, which the workload targets unless a trace row says otherwise
	 * @param baseDir  directory the trace path is resolved against
	 * @return the plan
	 * @throws IOException when the trace file cannot be read
	 */
	private static WorkloadPlan workload(Settings settings, ModelProfile model, Path baseDir) throws IOException {
		WorkloadPlan plan = new WorkloadPlan();
		plan.setPattern(WorkloadPlan.Pattern.from(settings.text("workload.pattern", "poisson")));
		plan.setModelId(settings.text("workload.modelId", model.getId()));
		plan.setIntervalSeconds(settings.number("workload.intervalSeconds", 1));
		plan.setRatePerMinute(settings.number("workload.ratePerMinute", 6));
		plan.setBurstSize(settings.integer("workload.burstSize", 4));
		List<String> boundaries = settings.list("workload.rateProfileMinutes");
		List<String> rates = settings.list("workload.rateProfilePerMinute");
		if (!boundaries.isEmpty() || !rates.isEmpty()) {
			double[] minutes = new double[boundaries.size()];
			for (int i = 0; i < boundaries.size(); i++)
				minutes[i] = Double.parseDouble(boundaries.get(i));
			double[] perMinute = new double[rates.size()];
			for (int i = 0; i < rates.size(); i++)
				perMinute[i] = Double.parseDouble(rates.get(i));
			plan.setRateProfile(minutes, perMinute);
		}
		applyDistribution(plan, settings, "workload.inputDistribution", true);
		applyDistribution(plan, settings, "workload.outputDistribution", false);
		plan.setOutputToInputRatio(settings.number("workload.outputToInputRatio", 0));
		plan.setOutputJitterTokens(settings.number("workload.outputJitterTokens", 0));
		plan.setMaxOutputTokens(settings.integer("workload.maxOutputTokens", 4096));
		plan.setPrediction(WorkloadPlan.LengthPrediction
				.from(settings.text("workload.prediction", WorkloadPlan.LengthPrediction.ORACLE.label())));
		plan.setPredictionFactor(settings.number("workload.predictionFactor", 1));
		plan.setPredictionConstant(settings.integer("workload.predictionConstant", 128));
		plan.setMaxRequests(settings.integer("workload.maxRequests", Integer.MAX_VALUE));
		plan.setStopArrivalsAfterSeconds(settings.number("workload.stopArrivalsAfterSeconds", Double.MAX_VALUE));
		plan.setPriority(settings.integer("workload.priority", 0));
		plan.setServiceClass(settings.text("workload.serviceClass", "default"));
		plan.setTtftTargetSeconds(settings.number("workload.ttftTargetSeconds", 0));
		plan.setEndToEndTargetSeconds(settings.number("workload.endToEndTargetSeconds", 0));
		plan.setTpotTargetSeconds(settings.number("workload.tpotTargetSeconds", 0));
		plan.setCancellationDelaySeconds(settings.number("workload.cancellationDelaySeconds", 0));
		if (plan.isTrace()) {
			String file = settings.text("workload.traceFile");
			double scale = settings.number("workload.traceTimeScale", 1);
			int maxRows = settings.integer("workload.traceMaxRows", Integer.MAX_VALUE);
			TraceLoader.ReadTrace read = TraceLoader.read(path(file, baseDir), scale, maxRows);
			List<TraceRecord> rows = read.rows();
			plan.setTrace(rows);
			if (rows.size() > plan.getMaxRequests())
				plan.setMaxRequests(rows.size());
			settings.remember("workload.traceDescription", read.describe());
		}
		plan.validate();
		return plan;
	}

	/**
	 * Declares a length distribution on the plan from a {@code <prefix>.name} key and its
	 * attributes.
	 * <p>
	 * The name and the attributes travel to the plan rather than a built sampler, so that each
	 * repetition can bind a fresh sampler to its own random stream.
	 *
	 * @param plan     the workload plan being filled
	 * @param settings the settings
	 * @param prefix   the key prefix of the distribution
	 * @param input    {@code true} for the prompt length, {@code false} for the generated length
	 */
	private static void applyDistribution(WorkloadPlan plan, Settings settings, String prefix, boolean input) {
		String name = settings.text(prefix + ".name", "");
		if (name.isEmpty())
			return;
		Map<String, String> attributes = settings.distributionAttributes(prefix);
		if (input)
			plan.setInputDistribution(name, attributes);
		else
			plan.setOutputDistribution(name, attributes);
	}

	/**
	 * Builds the admission policy, which may be a chain of rules.
	 *
	 * @param settings the settings
	 * @return the policy
	 */
	private static AdmissionPolicy admission(Settings settings) {
		String className = settings.text("policy.admission.class", "");
		if (!className.isEmpty())
			return instantiate(className, AdmissionPolicy.class, "policy.admission.class");
		List<String> names = settings.list("policy.admission");
		if (names.isEmpty())
			return new UnlimitedAdmission();
		List<AdmissionPolicy> policies = new ArrayList<>(names.size());
		for (String name : names) {
			switch (name.toLowerCase(Locale.ROOT)) {
			case "unlimited":
				policies.add(new UnlimitedAdmission());
				break;
			case "queue-limit":
				policies.add(new QueueLimitAdmission(settings.integer("policy.queueLimit.maxWaitingRequests")));
				break;
			case "cache-capacity":
				policies.add(new CacheCapacityAdmission(settings.integer("policy.cacheCapacity.headroomTokens", 0)));
				break;
			case "predicted-deadline":
				policies.add(new PredictedDeadlineAdmission(
						settings.number("policy.predictedDeadline.tolerance", 1)));
				break;
			default:
				throw new IllegalArgumentException("Unknown admission policy \"" + name
						+ "\"; use unlimited, queue-limit, cache-capacity or predicted-deadline");
			}
		}
		return policies.size() == 1 ? policies.get(0) : new CompositeAdmission(policies);
	}

	/**
	 * Builds the routing policy.
	 *
	 * @param settings the settings
	 * @return the policy
	 */
	private static RoutingPolicy routing(Settings settings) {
		String className = settings.text("policy.routing.class", "");
		if (!className.isEmpty())
			return instantiate(className, RoutingPolicy.class, "policy.routing.class");
		String name = settings.text("policy.routing", "least-queued");
		switch (name.toLowerCase(Locale.ROOT)) {
		case "round-robin":
			return new RoundRobinRouting();
		case "least-queued":
			return new LeastQueuedRouting();
		case "least-predicted-work":
			return new LeastPredictedWorkRouting();
		default:
			throw new IllegalArgumentException(
					"Unknown routing policy \"" + name + "\"; use round-robin, least-queued or least-predicted-work");
		}
	}

	/**
	 * Builds the scheduling policy.
	 *
	 * @param settings the settings
	 * @return the policy
	 */
	private static SchedulingPolicy scheduling(Settings settings) {
		String className = settings.text("policy.scheduling.class", "");
		if (!className.isEmpty())
			return instantiate(className, SchedulingPolicy.class, "policy.scheduling.class");
		String name = settings.text("policy.scheduling", "fcfs");
		switch (name.toLowerCase(Locale.ROOT)) {
		case "fcfs":
			return new FirstComeFirstServed();
		case "priority":
			return new PriorityFirst();
		case "shortest-expected-first":
			return new ShortestExpectedFirst();
		case "shortest-oracle-first":
			return new ShortestOracleFirst();
		default:
			throw new IllegalArgumentException("Unknown scheduling policy \"" + name + "\"; use fcfs, priority, "
					+ "shortest-expected-first or shortest-oracle-first");
		}
	}

	/**
	 * Builds a policy from a fully qualified class name, so that an experiment can use a policy
	 * that lives outside this project without patching the simulator.
	 * <p>
	 * The class is loaded from the classpath of the running simulation, so an external policy is
	 * used by adding its directory to {@code java -cp} next to the jar. Each error names the
	 * setting and the class, because a misspelled package would otherwise fail as a stack trace
	 * from deep inside the simulator.
	 *
	 * @param className the fully qualified class name
	 * @param type      the interface the policy must implement
	 * @param key       the setting that named the class, for the error messages
	 * @param <T>       the policy type
	 * @return the policy instance
	 */
	private static <T> T instantiate(String className, Class<T> type, String key) {
		Class<?> implementation;
		try {
			implementation = Class.forName(className);
		} catch (ClassNotFoundException e) {
			throw new IllegalArgumentException("The setting \"" + key + "\" names \"" + className
					+ "\", which is not on the classpath; add the directory holding it to java -cp", e);
		}
		if (!type.isAssignableFrom(implementation))
			throw new IllegalArgumentException("The setting \"" + key + "\" names \"" + className
					+ "\", which does not implement " + type.getSimpleName());
		try {
			return type.cast(implementation.getDeclaredConstructor().newInstance());
		} catch (ReflectiveOperationException e) {
			throw new IllegalArgumentException("The class \"" + className + "\" named by \"" + key
					+ "\" needs a public no-argument constructor", e);
		}
	}

	/**
	 * Builds the execution cost model.
	 *
	 * @param settings the settings
	 * @param replicas the deployment, whose largest batch decides the extrapolation threshold
	 * @return the cost model
	 */
	private static ExecutionCostModel costModel(Settings settings, List<ReplicaSpec> replicas) {
		String name = settings.text("cost.model", RooflineAnalyticalCostModel.NAME);
		if (!name.equalsIgnoreCase(RooflineAnalyticalCostModel.NAME))
			throw new IllegalArgumentException("The only execution cost model implemented so far is \""
					+ RooflineAnalyticalCostModel.NAME + "\", found \"" + name + "\"");
		int maxBatch = 0;
		for (ReplicaSpec spec : replicas)
			maxBatch = Math.max(maxBatch, spec.getMaxBatchSize());
		RooflineAnalyticalCostModel cost = new RooflineAnalyticalCostModel();
		cost.setSupportedMaxBatchSize(settings.integer("cost.supportedMaxBatchSize", Math.max(maxBatch, 1)));
		cost.setSupportedMaxStepTokens(settings.integer("cost.supportedMaxStepTokens", 16384));
		return cost;
	}

	/**
	 * Reads a count that may carry a magnitude suffix, so that a scenario can write
	 * {@code model.parameterCount=7B}.
	 *
	 * @param settings the settings
	 * @param key      the setting name
	 * @return the number of parameters
	 */
	private static long count(Settings settings, String key) {
		String value = settings.text(key).toUpperCase(Locale.ROOT);
		long multiplier = 1;
		int cut = value.length() - 1;
		char last = value.charAt(cut);
		switch (last) {
		case 'K':
			multiplier = 1_000L;
			break;
		case 'M':
			multiplier = 1_000_000L;
			break;
		case 'B':
			multiplier = 1_000_000_000L;
			break;
		case 'T':
			multiplier = 1_000_000_000_000L;
			break;
		default:
			cut = value.length();
			break;
		}
		String number = value.substring(0, cut).replace(",", "").trim();
		if (number.isEmpty())
			throw new IllegalArgumentException("The setting \"" + key + "\" is \"" + value + "\", which holds no number");
		try {
			return (long) (Double.parseDouble(number) * multiplier);
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("The setting \"" + key + "\" must be a number, optionally suffixed with "
					+ "K, M, B or T, found \"" + value + "\"", e);
		}
	}

	/**
	 * Returns the default bytes a scalar occupies for a precision name, so that a scenario that
	 * states {@code fp16} does not have to state two.
	 *
	 * @param precision the precision name
	 * @return bytes per scalar
	 */
	private static double bytesPerScalar(String precision) {
		switch (precision.toLowerCase(Locale.ROOT)) {
		case "fp32":
		case "tf32":
			return 4;
		case "fp16":
		case "bf16":
			return 2;
		case "fp8":
		case "int8":
			return 1;
		case "int4":
			return 0.5;
		default:
			throw new IllegalArgumentException("Unknown precision \"" + precision
					+ "\"; state model.bytesPerParameter explicitly for a precision the loader does not know");
		}
	}

	/**
	 * Resolves a configured path against a base directory.
	 *
	 * @param value   the configured path
	 * @param baseDir the base, {@code null} for an absolute result
	 * @return the resolved path
	 */
	private static Path path(String value, Path baseDir) {
		Path path = Paths.get(value);
		if (path.isAbsolute() || baseDir == null)
			return path;
		return baseDir.resolve(value);
	}

	/**
	 * Checks the cross-cutting expectations a single validate() of a part cannot see.
	 */
	private void validate() {
		model.validate();
		device.validate();
		for (ReplicaSpec spec : replicas)
			spec.validate();
		workload.validate();
		if (cost == null)
			throw new IllegalStateException("The scenario was built without an execution cost model");
		if (!workload.getModelId().equals(model.getId()) && !workload.isTrace())
			throw new IllegalArgumentException("The workload targets model \"" + workload.getModelId()
					+ "\" but the deployment serves \"" + model.getId()
					+ "\"; every request of a single-model scenario would be refused as NO_REPLICA_FOR_MODEL");
	}

	/**
	 * Returns the provenance label of the whole scenario, the weakest of its parts.
	 *
	 * @return {@code synthetic} unless the model, the device and the cost model are all measured
	 */
	public String overallProvenance() {
		Provenance weakest = Provenance.MEASURED;
		for (Provenance provenance : new Provenance[] { model.getProvenance(), device.getProvenance() })
			if (provenance.ordinal() < weakest.ordinal())
				weakest = provenance;
		if (!cost.isCalibrated() && weakest != Provenance.SYNTHETIC)
			weakest = Provenance.SYNTHETIC;
		return weakest.label();
	}

	/**
	 * Returns the header lines that describe the run, one per row.
	 *
	 * @return the description
	 */
	public List<String> describe() {
		Map<String, String> lines = new LinkedHashMap<>();
		lines.put("experiment", name + ", " + durationSeconds + " s, seed " + seed + ", " + repetitions
				+ " repetition(s)");
		lines.put("model", model.getId() + ": " + model.getParameterCount() + " parameters, " + model.getLayers()
				+ " layers, hidden " + model.getHiddenDimension() + ", kv " + model.kvDimension() + " scalars/token, "
				+ model.getPrecision() + ", context " + model.getMaxContextTokens() + " tokens ["
				+ model.getProvenance().label() + "]");
		lines.put("device", device.getId() + ": " + String.format(Locale.ROOT, "%.1f GiB",
				device.getMemoryBytes() / (1024.0 * 1024.0 * 1024.0)) + ", " + String.format(Locale.ROOT, "%.0f GB/s",
						device.getMemoryBandwidthBytesPerSecond() / 1e9)
						+ ", achieved " + device.declaredPrecisions() + " "
						+ achievedSummary() + " TFLOPS [" + device.getProvenance().label() + "]");
		lines.put("deployment", replicas.size() + " replica(s), batch <= " + replicas.get(0).getMaxBatchSize()
				+ ", prefill chunk " + replicas.get(0).getMaxPrefillChunkTokens() + " tokens, block "
				+ replicas.get(0).getTokensPerBlock() + " tokens, preemption "
				+ (replicas.get(0).isAllowPreemption() ? replicas.get(0).getVictimOrder().label() : "off")
				+ ", deadline abort " + (replicas.get(0).isAbortOnDeadlineMiss() ? "on" : "off"));
		lines.put("cost", cost.name() + " [" + cost.provenance().label() + (cost.isCalibrated() ? ", calibrated"
				: ", uncalibrated") + "]");
		lines.put("workload", workload.describe());
		lines.put("policies", "admission " + admission.describe() + ", routing " + routing.name() + ", scheduling "
				+ scheduling.describe());
		List<String> out = new ArrayList<>(lines.size());
		lines.forEach((key, value) -> out.add(String.format(Locale.ROOT, "%-11s %s", key, value)));
		return out;
	}

	/**
	 * Returns the achieved throughputs of the device, in the order the precisions are declared.
	 *
	 * @return a compact list, for the header
	 */
	private String achievedSummary() {
		StringBuilder out = new StringBuilder();
		for (String precision : device.declaredPrecisions())
			out.append(out.length() == 0 ? "" : "/").append(device.getAchievedTeraFlops(precision));
		return out.toString();
	}

	/**
	 * Returns the experiment name, which also names the run directory and the experiment column of
	 * the artifacts.
	 *
	 * @return the experiment name
	 */
	public String getName() {
		return name;
	}

	/**
	 * Returns the simulated duration of the run, which caps the clock of the engine.
	 *
	 * @return the duration in simulated seconds
	 */
	public double getDurationSeconds() {
		return durationSeconds;
	}

	/**
	 * Returns the seed of the first repetition; later repetitions derive their own seed from it.
	 *
	 * @return the first seed
	 */
	public long getSeed() {
		return seed;
	}

	/**
	 * Returns how many times the workload is replayed with a fresh seed.
	 *
	 * @return the repetition count, at least one
	 */
	public int getRepetitions() {
		return repetitions;
	}

	/**
	 * Returns the directory the artifacts are written to, already resolved against the working
	 * directory of the invocation rather than against the scenario file.
	 *
	 * @return the output directory
	 */
	public Path getOutputDir() {
		return outputDir;
	}

	/**
	 * Returns the seconds between two resource samples; zero disables the time series.
	 *
	 * @return the sampling interval in simulated seconds
	 */
	public double getSamplingIntervalSeconds() {
		return samplingIntervalSeconds;
	}

	/**
	 * Returns the model the deployment serves, which the workload targets unless a trace row says
	 * otherwise.
	 *
	 * @return the model profile
	 */
	public ModelProfile getModel() {
		return model;
	}

	/**
	 * Returns the device template every replica runs on; with several replicas each receives a
	 * distinct copy carrying its own identifier.
	 *
	 * @return the device profile
	 */
	public GpuProfile getDevice() {
		return device;
	}

	/**
	 * Returns one specification per replica, in deployment order.
	 *
	 * @return the replica specifications, as an unmodifiable list
	 */
	public List<ReplicaSpec> getReplicas() {
		return replicas;
	}

	/**
	 * Returns the workload plan that generates the arrival stream and the request lengths.
	 *
	 * @return the workload plan
	 */
	public WorkloadPlan getWorkload() {
		return workload;
	}

	/**
	 * Returns the admission policy, which may be a chain of rules, that decides whether a request
	 * enters the service.
	 *
	 * @return the admission policy
	 */
	public AdmissionPolicy getAdmission() {
		return admission;
	}

	/**
	 * Returns the routing policy that chooses the replica for an admitted request.
	 *
	 * @return the routing policy
	 */
	public RoutingPolicy getRouting() {
		return routing;
	}

	/**
	 * Returns the scheduling policy that orders the waiting queue of every replica.
	 *
	 * @return the scheduling policy
	 */
	public SchedulingPolicy getScheduling() {
		return scheduling;
	}

	/**
	 * Returns the execution cost model that prices the prefill and decode steps.
	 *
	 * @return the cost model
	 */
	public ExecutionCostModel getCost() {
		return cost;
	}

	/**
	 * Returns the settings the scenario was built from, kept so the artifacts can echo the
	 * effective configuration.
	 * <p>
	 * Loading is strict: a required value that is missing or malformed is refused, and a key that
	 * nothing reads produces a warning rather than being dropped silently, so a misspelled setting
	 * surfaces instead of passing for a designed one.
	 *
	 * @return the source settings
	 */
	public Settings getSettings() {
		return settings;
	}

	/**
	 * Returns a one-line description of the deployment and its workload, for the console.
	 *
	 * @return the description
	 */
	@Override
	public String toString() {
		return "Scenario[" + name + ", " + replicas.size() + " replica(s) of " + model.getId() + " on "
				+ device.getId() + ", " + workload.getPattern() + "]";
	}
}
