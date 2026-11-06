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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.mechalikh.docket.cost.CalibratedRooflineCostModel;
import com.mechalikh.docket.cost.ExecutionCostModel;
import com.mechalikh.docket.cost.RooflineAnalyticalCostModel;
import com.mechalikh.docket.domain.GpuProfile;
import com.mechalikh.docket.domain.ModelProfile;
import com.mechalikh.docket.domain.Provenance;
import com.mechalikh.docket.domain.TraceRecord;
import com.mechalikh.docket.engine.ReplicaSpec;
import com.mechalikh.docket.engine.WorkloadPlan;
import com.mechalikh.docket.network.LinkProfile;
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
	/** Representative device of the deployment: the template when one is configured, the first profile otherwise. */
	protected final GpuProfile device;
	/** Device profiles the scenario declares, in declaration order, keyed by the name the fleet uses. */
	protected final Map<String, GpuProfile> deviceProfiles;
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
	/** Whether the deployment splits prefill and decode onto different replicas. */
	protected final boolean disaggregated;
	/** Bearer a prompt cache crosses to reach a decode replica, {@code null} when the hand-off is instant. */
	protected final LinkProfile kvFabric;
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
	 * @param device                  representative device of the deployment
	 * @param deviceProfiles          declared device profiles, keyed by profile name
	 * @param replicas                replica specifications
	 * @param workload                generated workload
	 * @param admission               admission policy
	 * @param routing                 routing policy
	 * @param scheduling              scheduling policy
	 * @param cost                    execution cost model
	 * @param disaggregated           whether prefill and decode run on separate replicas
	 * @param kvFabric                bearer a prompt cache crosses to a decode replica, or {@code null}
	 * @param settings                source settings
	 */
	protected Scenario(String name, double durationSeconds, long seed, int repetitions, Path outputDir,
			double samplingIntervalSeconds, ModelProfile model, GpuProfile device, Map<String, GpuProfile> deviceProfiles,
			List<ReplicaSpec> replicas, WorkloadPlan workload, AdmissionPolicy admission, RoutingPolicy routing,
			SchedulingPolicy scheduling, ExecutionCostModel cost, boolean disaggregated, LinkProfile kvFabric,
			Settings settings) {
		this.name = name;
		this.durationSeconds = durationSeconds;
		this.seed = seed;
		this.repetitions = repetitions;
		this.outputDir = outputDir;
		this.samplingIntervalSeconds = samplingIntervalSeconds;
		this.model = model;
		this.device = device;
		this.deviceProfiles = Collections.unmodifiableMap(new LinkedHashMap<>(deviceProfiles));
		this.replicas = Collections.unmodifiableList(replicas);
		this.workload = workload;
		this.admission = admission;
		this.routing = routing;
		this.scheduling = scheduling;
		this.cost = cost;
		this.disaggregated = disaggregated;
		this.kvFabric = kvFabric;
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
		List<String> profileNames = settings.list("devices");
		Map<String, GpuProfile> profiles = deviceProfiles(settings, model, profileNames);
		Map<String, LinkProfile> links = new LinkedHashMap<>();
		boolean disaggregated = settings.flag("deployment.disaggregated", false);
		List<ReplicaSpec> replicas;
		LinkProfile kvFabric = null;
		if (disaggregated) {
			replicas = disaggregatedReplicas(settings, model, profiles, profileNames, links);
			String fabricName = settings.text("deployment.fabric", "");
			if (!fabricName.isEmpty())
				kvFabric = link(settings, fabricName, links);
		} else {
			replicas = replicas(settings, model, profiles, profileNames, links);
		}
		GpuProfile device = profiles.values().iterator().next();
		WorkloadPlan workload = workload(settings, model, baseDir);
		ExecutionCostModel cost = costModel(settings, replicas);

		Scenario scenario = new Scenario(name, duration, seed, repetitions, output, sampling, model, device, profiles,
				replicas, workload, admission(settings), routing(settings), scheduling(settings), cost, disaggregated,
				kvFabric, settings);
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
		model.setQuantizedKernel(settings.flag("model.quantizedKernel", false));
		model.setComputePrecision(settings.text("model.computePrecision", ""));
		model.setDequantFlopsPerScalar(settings.number("model.dequantFlopsPerScalar", 0));
		model.setComputeBytesPerScalar(settings.number("model.computeBytesPerScalar", 0));
		model.setActivationBytesPerSequence(settings.number("model.activationBytesPerSequence", 0));
		model.setActiveParameterFraction(settings.number("model.activeParameterFraction", 1));
		model.setProvenance(Provenance.parse(settings.text("model.provenance", "synthetic"), "model.provenance"));
		model.applyDefaults();
		return model;
	}

	/**
	 * Builds the device profiles the deployment draws from.
	 * <p>
	 * A scenario that names no profiles keeps the form it has always had: one {@code device.*} block
	 * whose keys carry no profile, whose identifier is required, and which {@code replicas.count}
	 * clones. A scenario that lists profiles in {@code devices} reads each machine from its own
	 * {@code device.<profile>.} block, which is how a fleet of unlike devices is described. The two
	 * forms are not mixed, because a deployment that read some figures from a flat block and others
	 * from a profile would report a device no file states.
	 *
	 * @param settings     the settings
	 * @param model        the model every profile must be able to serve
	 * @param profileNames the names listed in {@code devices}, empty for the flat form
	 * @return the profiles in declaration order, keyed by the name the fleet refers to them by
	 */
	private static Map<String, GpuProfile> deviceProfiles(Settings settings, ModelProfile model,
			List<String> profileNames) {
		Map<String, GpuProfile> profiles = new LinkedHashMap<>();
		if (profileNames.isEmpty()) {
			GpuProfile template = device(settings, model, "device.", "");
			profiles.put(template.getId(), template);
			return profiles;
		}
		if (settings.raw("device.id") != null)
			throw new IllegalArgumentException("\"devices\" lists profiles while a flat \"device.id\" is set too; write "
					+ "every machine as device.<profile>.<key>, or drop devices to keep the single-device form");
		for (String profile : profileNames) {
			if (profiles.containsKey(profile))
				throw new IllegalArgumentException("Device profile \"" + profile
						+ "\" is listed twice in \"devices\"; repeat the name in \"replicas\" to give it several replicas");
			profiles.put(profile, device(settings, model, "device." + profile + ".", profile));
		}
		return profiles;
	}

	/**
	 * Builds the device one settings block describes.
	 *
	 * @param settings  the settings
	 * @param model     the model it must be able to serve, used for the precision check
	 * @param prefix    key prefix of the block, {@code "device."} for the flat form and
	 *                  {@code "device.<profile>."} for a named one
	 * @param defaultId identifier to fall back to when the block declares none; empty makes the
	 *                  identifier required, which is how the flat form has always read it
	 * @return the device
	 */
	private static GpuProfile device(Settings settings, ModelProfile model, String prefix, String defaultId) {
		GpuProfile device = new GpuProfile();
		device.setId(defaultId.isEmpty() ? settings.text(prefix + "id") : settings.text(prefix + "id", defaultId));
		device.setDeviceType(settings.text(prefix + "type", "accelerator"));
		device.setMemoryBytes(settings.bytes(prefix + "memoryGiB", "GiB"));
		device.setUnusableMemoryFraction(settings.number(prefix + "unusableMemoryFraction", 0.05));
		if (device.getUnusableMemoryFraction() < 0 || device.getUnusableMemoryFraction() >= 1)
			throw new IllegalArgumentException("\"" + prefix + "unusableMemoryFraction\" is a fraction in [0, 1), found "
					+ device.getUnusableMemoryFraction());
		device.setMemoryBandwidthBytesPerSecond(
				settings.number(prefix + "memoryBandwidthGBs") * 1e9);
		Map<String, String> achieved = settings.distributionAttributes(prefix + "achievedTFLOPS");
		if (achieved.isEmpty())
			throw new IllegalArgumentException("The device needs at least one achieved throughput, written as \""
					+ prefix + "achievedTFLOPS.fp16=312\"; the execution model divides by an achieved value and "
					+ "refuses a peak one");
		for (Map.Entry<String, String> entry : achieved.entrySet())
			device.addAchievedTeraFlops(entry.getKey(), Double.parseDouble(entry.getValue()));
		for (Map.Entry<String, String> entry : settings.distributionAttributes(prefix + "peakTFLOPS").entrySet())
			device.addPeakTeraFlops(entry.getKey(), Double.parseDouble(entry.getValue()));
		device.setIdlePowerWatts(settings.number(prefix + "idlePowerWatts", 0));
		device.setActivePowerWatts(settings.number(prefix + "activePowerWatts", 0));
		device.setKernelLaunchOverheadSeconds(settings.millisToSeconds(prefix + "kernelLaunchOverheadMs", 0));
		device.setProvenance(Provenance.parse(settings.text(prefix + "provenance", "synthetic"),
				prefix + "provenance"));
		device.setNote(settings.text(prefix + "note", ""));
		if (!device.declaredPrecisions().contains(model.getPrecision()))
			throw new IllegalArgumentException("The model is served in \"" + model.getPrecision() + "\" but \""
					+ device.getId() + "\" declares achieved throughputs only for " + device.declaredPrecisions()
					+ "; declare " + prefix + "achievedTFLOPS." + model.getPrecision()
					+ " or change model.precision");
		return device;
	}

	/**
	 * Returns the bearer a device block says its clients reach the device through.
	 *
	 * @param settings the settings
	 * @param prefix   key prefix of the device block, {@code "device."} or {@code "device.<profile>."}
	 * @param links    the bearers built so far, keyed by the name the devices use
	 * @return the link profile, {@code null} when the block names no link
	 */
	private static LinkProfile deviceLink(Settings settings, String prefix, Map<String, LinkProfile> links) {
		String name = settings.text(prefix + "link", "");
		if (name.isEmpty())
			return null;
		return link(settings, name, links);
	}

	/**
	 * Builds one access link from its {@code link.<name>.} block, once per name.
	 * <p>
	 * Two devices that name the same bearer must hold the same object, because the queue of a link
	 * is the whole point of declaring one: an access point serving three phones is one bottleneck,
	 * not three independent ones.
	 *
	 * @param settings the settings
	 * @param name     the name a device block wrote in its {@code link} key
	 * @param links    the bearers built so far
	 * @return the link profile, validated
	 */
	private static LinkProfile link(Settings settings, String name, Map<String, LinkProfile> links) {
		LinkProfile cached = links.get(name);
		if (cached != null)
			return cached;
		String prefix = "link." + name + ".";
		LinkProfile link = new LinkProfile();
		link.setId(name);
		link.setCapacityMbps(settings.number(prefix + "capacityMbps"));
		link.setLatencyMs(settings.number(prefix + "latencyMs", 0));
		link.setBytesPerInputToken(
				settings.number(prefix + "bytesPerInputToken", link.getBytesPerInputToken()));
		link.setBytesPerOutputToken(
				settings.number(prefix + "bytesPerOutputToken", link.getBytesPerOutputToken()));
		link.setProvenance(
					Provenance.parse(settings.text(prefix + "provenance", "synthetic"), prefix + "provenance"));
		link.setNote(settings.text(prefix + "note", ""));
		link.validate();
		links.put(name, link);
		return link;
	}

	/**
	 * Builds the replicas of the deployment.
	 * <p>
	 * Two forms are accepted, and a scenario uses exactly one of them. {@code replicas.count} is
	 * the number of identical devices, clones of the one {@code device.*} template, not of
	 * processes sharing one device: the service refuses two replicas on the same device because
	 * each would count the whole memory as its own. {@code replicas} is a fleet, one profile name
	 * per replica, drawn from the profiles {@code devices} declares, so a phone, a small server and
	 * an accelerator node can serve the same model side by side.
	 *
	 * @param settings     the settings
	 * @param model        the served model
	 * @param profiles     the declared device profiles, one entry for the flat form
	 * @param profileNames the names {@code devices} declared, empty for the flat form
	 * @param links        the bearers built so far, so that two devices naming one link share it
	 * @return one specification per replica
	 */
	private static List<ReplicaSpec> replicas(Settings settings, ModelProfile model, Map<String, GpuProfile> profiles,
			List<String> profileNames, Map<String, LinkProfile> links) {
		List<String> fleet = settings.list("replicas");
		if (fleet.isEmpty())
			return clonedReplicas(settings, model, profiles.values().iterator().next(), profileNames, links);
		if (settings.raw("replicas.count") != null)
			throw new IllegalArgumentException("\"replicas\" lists the machines of the fleet while \"replicas.count\" "
					+ "also counts clones of one template; drop one of the two");
		Map<String, Integer> uses = new LinkedHashMap<>();
		for (String profile : fleet)
			uses.merge(profile, 1, Integer::sum);
		Map<String, Integer> seen = new LinkedHashMap<>();
		List<ReplicaSpec> specs = new ArrayList<>(fleet.size());
		for (String profile : fleet) {
			GpuProfile declared = profiles.get(profile);
			if (declared == null)
				throw new IllegalArgumentException("The fleet entry \"" + profile
						+ "\" is not a declared device profile; \"devices\" lists " + profileNames);
			// A profile used more than once describes several machines of the same kind, which
			// need distinct identifiers for the same reason the cloned form gives them one.
			GpuProfile device = uses.get(profile) == 1 ? declared
					: withId(declared, declared.getId() + "-" + seen.merge(profile, 1, Integer::sum));
			ReplicaSpec spec = replicaSpec(settings, model, device,
					deviceLink(settings, "device." + profile + ".", links), ReplicaSpec.Role.COLOCATED);
			applyProfileOverrides(spec, settings, profile);
			spec.validate();
			specs.add(spec);
		}
		return specs;
	}

	/**
	 * Builds the deployment of the flat form: one template cloned {@code replicas.count} times.
	 *
	 * @param settings     the settings
	 * @param model        the served model
	 * @param device       the device template
	 * @param profileNames the declared profile names, non-empty when the scenario has profiles
	 * @param links        the bearers built so far, so that every replica naming one link shares it
	 * @return one specification per replica
	 */
	private static List<ReplicaSpec> clonedReplicas(Settings settings, ModelProfile model, GpuProfile device,
			List<String> profileNames, Map<String, LinkProfile> links) {
		if (!profileNames.isEmpty())
			throw new IllegalArgumentException("\"devices\" declares the profiles " + profileNames
					+ " but \"replicas\" names none of them; give one entry per replica, for instance replicas="
					+ String.join(",", profileNames));
		int count = settings.integer("replicas.count", 1);
		if (count < 1)
			throw new IllegalArgumentException("The deployment needs at least one replica, found " + count);
		LinkProfile link = deviceLink(settings, "device.", links);
		List<ReplicaSpec> specs = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			ReplicaSpec spec = replicaSpec(settings, model,
					count == 1 ? device : withId(device, device.getId() + "-" + (i + 1)), link,
					ReplicaSpec.Role.COLOCATED);
			spec.validate();
			specs.add(spec);
		}
		return specs;
	}

	/**
	 * Builds the deployment of a disaggregated scenario: a set of prefill replicas and a set of
	 * decode replicas, each drawn from the declared device profiles.
	 * <p>
	 * The prefill stage uses the first {@code devices} profile and the decode stage the second, or
	 * the same one when only a single machine kind is declared. Each stage may hold several
	 * replicas, cloned with distinct identifiers the same way {@code replicas.count} does. The two
	 * forms of a colocated deployment, {@code replicas.count} and {@code replicas}, are refused,
	 * because a split deployment states its shape with {@code deployment.prefillReplicas} and
	 * {@code deployment.decodeReplicas} instead and a scenario that set both would describe two
	 * different machines.
	 *
	 * @param settings     the settings
	 * @param model        the served model
	 * @param profiles     the declared device profiles, one entry for the flat form
	 * @param profileNames the names {@code devices} declared, empty for the flat form
	 * @param links        the bearers built so far, so that replicas naming one link share it
	 * @return one specification per replica, prefill stage first
	 */
	private static List<ReplicaSpec> disaggregatedReplicas(Settings settings, ModelProfile model,
			Map<String, GpuProfile> profiles, List<String> profileNames, Map<String, LinkProfile> links) {
		if (settings.raw("replicas.count") != null || !settings.list("replicas").isEmpty())
			throw new IllegalArgumentException("\"deployment.disaggregated\" builds the fleet from "
					+ "deployment.prefillReplicas and deployment.decodeReplicas; drop \"replicas.count\" and \"replicas\" "
					+ "when the deployment is disaggregated");
		int prefillCount = settings.integer("deployment.prefillReplicas");
		int decodeCount = settings.integer("deployment.decodeReplicas");
		if (prefillCount < 1 || decodeCount < 1)
			throw new IllegalArgumentException("A disaggregated deployment needs at least one prefill and one decode "
					+ "replica, found " + prefillCount + " and " + decodeCount);
		List<GpuProfile> machineKinds = new ArrayList<>(profiles.values());
		// A scenario with two profiles puts prefill on the first and decode on the second, so a
		// compute-rich machine prefills and a memory-rich one decodes. With one profile both stages
		// clone it, which models a split deployment on identical hardware.
		GpuProfile prefillDevice = machineKinds.get(0);
		GpuProfile decodeDevice = machineKinds.size() > 1 ? machineKinds.get(1) : prefillDevice;
		String prefillProfile = profileNames.isEmpty() ? null : profileNames.get(0);
		String decodeProfile = profileNames.size() > 1 ? profileNames.get(1) : prefillProfile;
		List<ReplicaSpec> specs = new ArrayList<>(prefillCount + decodeCount);
		specs.addAll(stageReplicas(settings, model, prefillDevice, prefillProfile, links, prefillCount,
				ReplicaSpec.Role.PREFILL, "p"));
		specs.addAll(stageReplicas(settings, model, decodeDevice, decodeProfile, links, decodeCount,
				ReplicaSpec.Role.DECODE, "d"));
		return specs;
	}

	/**
	 * Builds the replicas of one stage of a disaggregated deployment.
	 *
	 * @param settings     the settings
	 * @param model        the served model
	 * @param device       the device kind of the stage
	 * @param profile      the profile name the stage was drawn from, {@code null} for the flat form
	 * @param links        the bearers built so far
	 * @param count        replicas in the stage, at least one
	 * @param role         the phase the stage serves
	 * @param idTag        suffix keeping prefill and decode identifiers apart
	 * @return one validated specification per replica of the stage
	 */
	private static List<ReplicaSpec> stageReplicas(Settings settings, ModelProfile model, GpuProfile device,
			String profile, Map<String, LinkProfile> links, int count, ReplicaSpec.Role role, String idTag) {
		String prefix = profile == null ? "device." : "device." + profile + ".";
		List<ReplicaSpec> specs = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			// Every stage replica carries a role-tagged identifier: a split deployment on a single
			// machine kind would otherwise hand the prefill and the decode replica the same device
			// id, which the service refuses because each would count the whole memory as its own.
			GpuProfile machine = withId(device, device.getId() + "-" + idTag + (i + 1));
			ReplicaSpec spec = replicaSpec(settings, model, machine, deviceLink(settings, prefix, links), role);
			if (profile != null)
				applyProfileOverrides(spec, settings, profile);
			spec.validate();
			specs.add(spec);
		}
		return specs;
	}

	/**
	 * Fills one replica specification from the shared {@code replica.*} keys.
	 *
	 * @param settings the settings
	 * @param model    the served model
	 * @param device   the device the replica runs on
	 * @param link     the bearer the traffic of the replica crosses, {@code null} when the device
	 *                 block names none
	 * @param role     the phase of serving the replica is responsible for
	 * @return the specification, not yet validated
	 */
	private static ReplicaSpec replicaSpec(Settings settings, ModelProfile model, GpuProfile device,
			LinkProfile link, ReplicaSpec.Role role) {
		ReplicaSpec spec = new ReplicaSpec();
		spec.setModel(model);
		spec.setDevice(device);
		spec.setLink(link);
		spec.setRole(role);
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
		spec.setSpeculativeDecoding(settings.flag("replica.speculativeDecoding", false));
		spec.setDraftTokens(settings.integer("replica.draftTokens", 2));
		spec.setAcceptanceRate(settings.number("replica.acceptanceRate", 0.7));
		spec.setPrefixCache(settings.flag("replica.prefixCache", false));
		return spec;
	}

	/**
	 * Applies the serving limits one device profile overrides, written as
	 * {@code replica.<profile>.<key>}.
	 * <p>
	 * The batch ceiling and the cache block are per-machine choices rather than per-deployment
	 * ones: a phone runs one sequence at a time and a server batches dozens, so a fleet that shared
	 * one ceiling would compare a placement against a deployment nobody runs. Keys not listed here
	 * are reported rather than ignored, because a limit that silently kept the fleet default is the
	 * kind of oversight a published comparison cannot afford.
	 *
	 * @param spec    the specification to adjust
	 * @param settings the settings
	 * @param profile the profile name the replica was drawn from
	 */
	private static void applyProfileOverrides(ReplicaSpec spec, Settings settings, String profile) {
		String prefix = "replica." + profile + ".";
		for (Map.Entry<String, String> entry : settings.distributionAttributes("replica." + profile).entrySet()) {
			String key = prefix + entry.getKey();
			String value = entry.getValue();
			switch (entry.getKey()) {
			case "maxBatchSize":
				spec.setMaxBatchSize(settings.integer(key));
				break;
			case "maxPrefillChunkTokens":
				spec.setMaxPrefillChunkTokens(settings.integer(key));
				break;
			case "allowPreemption":
				spec.setAllowPreemption(settings.flag(key, spec.isAllowPreemption()));
				break;
			case "abortOnDeadlineMiss":
				spec.setAbortOnDeadlineMiss(settings.flag(key, spec.isAbortOnDeadlineMiss()));
				break;
			case "victimOrder":
				spec.setVictimOrder(ReplicaSpec.VictimOrder.from(value));
				break;
			case "tokensPerBlock":
				spec.setTokensPerBlock(settings.integer(key));
				break;
			case "runtimeOverheadGiB":
				spec.setRuntimeOverheadBytes(settings.bytes(key, "GiB"));
				break;
			case "reservedActivationSlots":
				spec.setReservedActivationSlots(settings.integer(key));
				break;
			case "speculativeDecoding":
				spec.setSpeculativeDecoding(settings.flag(key, spec.isSpeculativeDecoding()));
				break;
			case "draftTokens":
				spec.setDraftTokens(settings.integer(key));
				break;
			case "acceptanceRate":
				spec.setAcceptanceRate(settings.number(key));
				break;
			case "prefixCache":
				spec.setPrefixCache(settings.flag(key, spec.isPrefixCache()));
				break;
			default:
				throw new IllegalArgumentException("Unknown replica setting \"" + key
						+ "\"; a profile overrides maxBatchSize, maxPrefillChunkTokens, allowPreemption, victimOrder, "
						+ "abortOnDeadlineMiss, tokensPerBlock, runtimeOverheadGiB, reservedActivationSlots, "
						+ "speculativeDecoding, draftTokens, acceptanceRate or prefixCache");
			}
		}
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
	 * Applies the objectives stated per service class.
	 * <p>
	 * A recorded trace does not carry the promises an operator made to the classes it mixes, so
	 * the scenario states them: {@code workload.ttftTargetSeconds.<class>} and its two siblings.
	 * {@link WorkloadPlan#validate()} then refuses a class that no request of this workload belongs
	 * to, because a target nobody meets is worse than an absent one.
	 *
	 * @param plan     the workload plan to annotate
	 * @param settings the settings
	 */
	private static void applyClassTargets(WorkloadPlan plan, Settings settings) {
		Set<String> classes = new LinkedHashSet<>();
		classes.addAll(settings.distributionAttributes("workload.ttftTargetSeconds").keySet());
		classes.addAll(settings.distributionAttributes("workload.endToEndTargetSeconds").keySet());
		classes.addAll(settings.distributionAttributes("workload.tpotTargetSeconds").keySet());
		for (String serviceClass : classes)
			plan.setClassTarget(serviceClass,
					settings.number("workload.ttftTargetSeconds." + serviceClass, 0),
					settings.number("workload.endToEndTargetSeconds." + serviceClass, 0),
					settings.number("workload.tpotTargetSeconds." + serviceClass, 0));
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
		applyClassTargets(plan, settings);
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
		int maxBatch = 0;
		for (ReplicaSpec spec : replicas)
			maxBatch = Math.max(maxBatch, spec.getMaxBatchSize());
		RooflineAnalyticalCostModel cost;
		if (name.equalsIgnoreCase(RooflineAnalyticalCostModel.NAME))
			cost = new RooflineAnalyticalCostModel();
		else if (name.equalsIgnoreCase(CalibratedRooflineCostModel.NAME))
			cost = new CalibratedRooflineCostModel(settings.text("cost.calibration.source", ""));
		else
			throw new IllegalArgumentException("Unknown execution cost model \"" + name + "\", expected \""
					+ RooflineAnalyticalCostModel.NAME + "\" or \"" + CalibratedRooflineCostModel.NAME + "\"");
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
		int prefill = 0;
		int decode = 0;
		for (ReplicaSpec spec : replicas) {
			if (spec.getRole() == ReplicaSpec.Role.PREFILL)
				prefill++;
			else if (spec.getRole() == ReplicaSpec.Role.DECODE)
				decode++;
		}
		if (disaggregated && (prefill < 1 || decode < 1))
			throw new IllegalArgumentException("A disaggregated deployment needs at least one prefill and one decode "
					+ "replica, found " + prefill + " and " + decode);
		if (!disaggregated && (prefill > 0 || decode > 0))
			throw new IllegalArgumentException("A replica has a prefill or decode role but \"deployment.disaggregated\" "
					+ "is off; set it to true to split the phases");
		// Prefix reuse shrinks the prefill of a request on the device that holds the shared blocks.
		// Under disaggregation the prompt is prefilled on one pool and its cache is handed to another,
		// so the two mechanisms would describe where the shared prefix lives in two different ways; the
		// combination is refused rather than guessed, and a split deployment keeps the full prefill.
		if (disaggregated)
			for (ReplicaSpec spec : replicas)
				if (spec.isPrefixCache())
					throw new IllegalArgumentException("Replica on device \"" + spec.getDevice().getId()
							+ "\" turns prefixCache on, but disaggregated deployments hand the prompt cache between "
							+ "pools; prefix reuse is modelled only for colocated deployments, so set "
							+ "deployment.disaggregated=false or replica.prefixCache=false");
		// A quantized kernel that computes at a precision wider than the weights needs the device to
		// declare throughput at that precision, so a scenario cannot silently divide by the storage
		// format's figure. The check mirrors the roofline's own lookup and fails at load time.
		if (model.isQuantizedKernel()) {
			String computePrecision = model.effectiveComputePrecision();
			for (GpuProfile profile : deviceProfiles.values())
				if (Double.isNaN(profile.getAchievedTeraFlops(computePrecision)))
					throw new IllegalArgumentException("Model \"" + model.getId() + "\" runs a quantized kernel at "
							+ computePrecision + " but device \"" + profile.getId() + "\" declares no achieved throughput "
							+ "for that precision; declare device.achievedTFLOPS." + computePrecision + " or set "
							+ "model.computePrecision to a precision the device lists");
		}
	}

	/**
	 * Returns the provenance label of the whole scenario, the weakest of its parts.
	 * <p>
	 * An access link counts as a part: a deployment whose bearers are guessed reports guessed
	 * latencies even when its model and its machines are measured, and the label a reader sees has
	 * to name the figure they cannot check.
	 *
	 * @return {@code synthetic} unless the model, every device profile, every link and the cost
	 *         model are measured
	 */
	public String overallProvenance() {
		Provenance weakest = Provenance.MEASURED;
		List<Provenance> parts = new ArrayList<>();
		parts.add(model.getProvenance());
		for (GpuProfile profile : deviceProfiles.values())
			parts.add(profile.getProvenance());
		for (LinkProfile link : declaredLinks().values())
			parts.add(link.getProvenance());
		if (kvFabric != null)
			parts.add(kvFabric.getProvenance());
		for (Provenance provenance : parts)
			if (provenance.ordinal() < weakest.ordinal())
				weakest = provenance;
		if (!cost.isCalibrated() && weakest != Provenance.SYNTHETIC)
			weakest = Provenance.SYNTHETIC;
		return weakest.label();
	}

	/**
	 * Returns the access links the replicas are reached through, one entry per declared bearer.
	 * <p>
	 * The map is derived from the specifications instead of stored beside them, so a scenario
	 * cannot report a link no replica actually uses.
	 *
	 * @return the distinct link profiles, in the order the replicas first name them, empty when the
	 *         deployment models no access link
	 */
	public Map<String, LinkProfile> declaredLinks() {
		Map<String, LinkProfile> links = new LinkedHashMap<>();
		for (ReplicaSpec spec : replicas)
			if (spec.getLink() != null)
				links.putIfAbsent(spec.getLink().getId(), spec.getLink());
		return links;
	}

	/**
	 * Returns the weakest provenance declared by the device profiles of the deployment.
	 * <p>
	 * A fleet mixes measured and assumed machines, and the artifact has to say so with one label:
	 * the figure that matters is the one a reader cannot check.
	 *
	 * @return the provenance label of the least trustworthy device
	 */
	public String deviceProvenance() {
		Provenance weakest = Provenance.MEASURED;
		for (GpuProfile profile : deviceProfiles.values())
			if (profile.getProvenance().ordinal() < weakest.ordinal())
				weakest = profile.getProvenance();
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
		lines.put("device", describeDevices());
		lines.put("deployment", deploymentSummary());
		String network = describeLinks();
		if (network != null)
			lines.put("network", network);
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
	 * Returns the header value of the device line.
	 * <p>
	 * A single device prints as it always did. A fleet prints one segment per profile, because the
	 * point of declaring several machines is that they differ, and a header that named only the
	 * first would hide the comparison the run is about.
	 *
	 * @return the device line
	 */
	private String describeDevices() {
		if (deviceProfiles.size() == 1)
			return describeDevice(deviceProfiles.values().iterator().next());
		StringBuilder out = new StringBuilder().append(deviceProfiles.size()).append(" device profiles");
		for (GpuProfile profile : deviceProfiles.values())
			out.append("; ").append(describeDevice(profile));
		return out.toString();
	}

	/**
	 * Returns the compact one-profile description used by the header.
	 *
	 * @param profile the device to describe
	 * @return capacity, bandwidth, achieved throughputs and provenance
	 */
	private static String describeDevice(GpuProfile profile) {
		return profile.getId() + ": " + String.format(Locale.ROOT, "%.1f GiB",
				profile.getMemoryBytes() / (1024.0 * 1024.0 * 1024.0)) + ", "
				+ String.format(Locale.ROOT, "%.0f GB/s", profile.getMemoryBandwidthBytesPerSecond() / 1e9)
				+ ", achieved " + profile.declaredPrecisions() + " " + achievedSummary(profile) + " TFLOPS ["
				+ profile.getProvenance().label() + "]";
	}

	/**
	 * Returns the header value of the deployment line.
	 * <p>
	 * Identical replicas share one summary. A fleet whose replicas differ per profile prints each
	 * replica's own limits, because the batch ceiling of a phone and of a server are not the same
	 * promise, and the line has to say which replica made which.
	 *
	 * @return the deployment line
	 */
	private String deploymentSummary() {
		ReplicaSpec first = replicas.get(0);
		StringBuilder shared = new StringBuilder(replicas.size() + " replica(s)");
		boolean uniform = true;
		for (ReplicaSpec spec : replicas)
			if (spec.getMaxBatchSize() != first.getMaxBatchSize()
					|| spec.getMaxPrefillChunkTokens() != first.getMaxPrefillChunkTokens()
					|| spec.getTokensPerBlock() != first.getTokensPerBlock()
					|| spec.isAllowPreemption() != first.isAllowPreemption()
					|| spec.getVictimOrder() != first.getVictimOrder()
					|| spec.isAbortOnDeadlineMiss() != first.isAbortOnDeadlineMiss())
				uniform = false;
		if (uniform)
			shared.append(", batch <= ").append(first.getMaxBatchSize()).append(", prefill chunk ")
					.append(first.getMaxPrefillChunkTokens()).append(" tokens, block ").append(first.getTokensPerBlock())
					.append(" tokens, preemption ")
					.append(first.isAllowPreemption() ? first.getVictimOrder().label() : "off").append(", deadline abort ")
					.append(first.isAbortOnDeadlineMiss() ? "on" : "off");
		else
			for (ReplicaSpec spec : replicas) {
				shared.append("; ").append(spec.getDevice().getId());
				if (disaggregated)
					shared.append(" [").append(spec.getRole().label()).append("]");
				shared.append(" batch<=").append(spec.getMaxBatchSize())
						.append(" chunk=").append(spec.getMaxPrefillChunkTokens()).append(" block=")
						.append(spec.getTokensPerBlock())
						.append(" preempt=").append(spec.isAllowPreemption() ? spec.getVictimOrder().label() : "off")
						.append(" abort=").append(spec.isAbortOnDeadlineMiss() ? "on" : "off");
			}
		if (disaggregated)
			shared.insert(0, disaggregationHeader());
		String mechanisms = describeMechanisms();
		if (!mechanisms.isEmpty())
			shared.append(", ").append(mechanisms);
		return shared.toString();
	}

	/**
	 * Describes the mechanisms a deployment turns on beyond the serving defaults, for the deployment
	 * line of the console and the configuration echo.
	 * <p>
	 * A run that emits several tokens per decode step, or reuses a shared prompt prefix, reads very
	 * differently from the plain loop, so the header names the mechanism and the figure that drives it
	 * instead of leaving a reader to infer it from a latency. When every replica keeps both off the
	 * line stays empty and the deployment prints as it always did.
	 *
	 * @return a comma-separated description of the active mechanisms, empty when none are on
	 */
	private String describeMechanisms() {
		StringBuilder parts = new StringBuilder();
		boolean speculative = false;
		int tokensPerStep = 1;
		for (ReplicaSpec spec : replicas)
			if (spec.isSpeculativeDecoding()) {
				speculative = true;
				tokensPerStep = Math.max(tokensPerStep, spec.acceptedTokensPerStep());
			}
		if (speculative)
			parts.append("speculative decode x").append(tokensPerStep).append(" token/step");
		boolean prefix = false;
		for (ReplicaSpec spec : replicas)
			if (spec.isPrefixCache())
				prefix = true;
		if (prefix) {
			if (parts.length() > 0)
				parts.append(", ");
			parts.append("prefix reuse on");
		}
		if (model.isQuantizedKernel()) {
			if (parts.length() > 0)
				parts.append(", ");
			parts.append("quantized kernel (store ").append(model.getPrecision()).append(", compute ")
					.append(model.effectiveComputePrecision()).append(")");
		}
		return parts.toString();
	}

	/**
	 * Returns the prefix naming the phase split and the fabric a cache crosses, for the deployment
	 * header of a disaggregated run.
	 *
	 * @return "disaggregated: N prefill + M decode, ..." followed by a separator
	 */
	private String disaggregationHeader() {
		int prefill = 0;
		for (ReplicaSpec spec : replicas)
			if (spec.getRole() == ReplicaSpec.Role.PREFILL)
				prefill++;
		int decode = replicas.size() - prefill;
		return "disaggregated: " + prefill + " prefill + " + decode + " decode, "
				+ (kvFabric == null ? "instant KV hand-off; " : "KV fabric " + kvFabric.getId() + "; ");
	}

	/**
	 * Returns the header value of the access-link line, naming the bearers the replicas are reached
	 * through.
	 * <p>
	 * The console and the configuration echo print the same text, so a reader who compares a capture
	 * with the artifacts of the run does not have to wonder which figure produced the other.
	 *
	 * @return one segment per declared link, {@code null} when the deployment models none
	 */
	public String networkSummary() {
		return describeLinks();
	}

	/**
	 * Returns the header value of the network line.
	 * <p>
	 * The line is absent when no replica names a bearer, which is how a scenario that charges only
	 * the GPU keeps the header it always had.
	 *
	 * @return one segment per declared link, or {@code null} when the deployment has none
	 */
	private String describeLinks() {
		Map<String, LinkProfile> links = declaredLinks();
		if (links.isEmpty())
			return null;
		StringBuilder out = new StringBuilder();
		for (LinkProfile link : links.values())
			out.append(out.length() == 0 ? "" : "; ").append(link.getId()).append(": ").append(String.format(
					Locale.ROOT, "%.1f Mbps, %.1f ms one way, %s [%s]", link.getCapacityMbps(), link.getLatencyMs(),
					link.payloadSummary(), link.getProvenance().label()));
		return out.toString();
	}

	/**
	 * Returns the achieved throughputs of a device, in the order the precisions are declared.
	 *
	 * @param profile the device to read
	 * @return a compact list, for the header
	 */
	private static String achievedSummary(GpuProfile profile) {
		StringBuilder out = new StringBuilder();
		for (String precision : profile.declaredPrecisions())
			out.append(out.length() == 0 ? "" : "/").append(profile.getAchievedTeraFlops(precision));
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
	 * <p>
	 * A fleet of unlike machines has no single template, so this is its first profile. Use
	 * {@link #getDeviceProfiles()} to see every machine kind the deployment declares.
	 *
	 * @return the device profile
	 */
	public GpuProfile getDevice() {
		return device;
	}

	/**
	 * Returns the device profiles the deployment draws from, in declaration order.
	 * <p>
	 * One entry for the flat {@code device.*} form, one per name listed in {@code devices}
	 * otherwise. The replicas of a fleet carry copies of these profiles with distinct identifiers,
	 * so a profile is the machine kind and a replica is one machine of that kind.
	 *
	 * @return an unmodifiable map from profile name to device
	 */
	public Map<String, GpuProfile> getDeviceProfiles() {
		return deviceProfiles;
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
	 * Returns whether the deployment splits prefill and decode onto separate replicas.
	 *
	 * @return {@code true} when at least one replica carries a prefill or decode role
	 */
	public boolean isDisaggregated() {
		return disaggregated;
	}

	/**
	 * Returns the bearer a finished prompt cache crosses to reach a decode replica.
	 *
	 * @return the fabric link profile, {@code null} when the hand-off is modeled as instant or the
	 *         deployment does not disaggregate
	 */
	public LinkProfile getKvFabric() {
		return kvFabric;
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
