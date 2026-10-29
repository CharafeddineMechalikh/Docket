/**
 *     GenAIInferenceSim: a discrete-event simulator for generative AI inference serving.
 *
 *     This file is part of GenAIInferenceSim.
 *
 *     GenAIInferenceSim is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     GenAIInferenceSim is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with GenAIInferenceSim. If not, see <http://www.gnu.org/licenses/>.
 **/
package com.mechalikh.genaism.engine;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.mechalikh.genaism.domain.TraceRecord;
import com.mechalikh.pureedgesim.distribution.Distribution;
import com.mechalikh.pureedgesim.distribution.DistributionFactory;

/**
 * What the workload source generates: the arrival pattern, the request shapes and the quality of
 * the length prediction available at admission time.
 * <p>
 * The plan separates the length a request will really produce from the length the serving system
 * believes it will produce. That distinction decides which policies are deployable: a scheduler
 * that orders the queue by the real length is an upper bound, while one that orders it by the
 * predicted length can be built today. The prediction mode is therefore part of the experiment
 * definition, and the artifacts record it.
 * <p>
 * Length distributions are PureEdgeSim {@link Distribution} objects, so the same names and the
 * same parameter syntax work here as in a PureEdgeSim scenario file.
 *
 * @author Charafeddine Mechalikh
 * @since GenAIInferenceSim 0.1
 */
public class WorkloadPlan {

	/** How the next arrival instant is drawn. */
	public enum Pattern {
		/** One request every {@link WorkloadPlan#getIntervalSeconds()} seconds. */
		FIXED("fixed"),
		/** Inter-arrival times drawn from an exponential distribution of the declared mean rate. */
		POISSON("poisson"),
		/** A group of {@link WorkloadPlan#getBurstSize()} requests at the same instant, periodically. */
		BURSTY("bursty"),
		/** Poisson arrivals whose rate changes over a piecewise-constant profile. */
		TIME_VARYING("time-varying"),
		/** Arrival instants and lengths read from a recorded trace. */
		TRACE("trace");

		/** Label written to the artifacts. */
		private final String label;

		Pattern(String label) {
			this.label = label;
		}

		/**
		 * Returns the label of the pattern.
		 *
		 * @return the name used in a configuration file
		 */
		public String label() {
			return label;
		}

		/**
		 * Finds a pattern by its configuration name.
		 *
		 * @param value the configured name, case insensitive
		 * @return the pattern
		 * @throws IllegalArgumentException when no pattern matches, listing the known names
		 */
		public static Pattern from(String value) {
			for (Pattern pattern : values())
				if (pattern.label.equalsIgnoreCase(value.trim()))
					return pattern;
			throw new IllegalArgumentException("Unknown arrival pattern \"" + value + "\"; known patterns are "
					+ names());
		}

		/**
		 * Returns the configuration names of every pattern.
		 *
		 * @return a comma-separated list
		 */
		public static String names() {
			StringBuilder out = new StringBuilder();
			for (Pattern pattern : values())
				out.append(out.length() == 0 ? "" : ", ").append(pattern.label);
			return out.toString();
		}

		@Override
		public String toString() {
			return label;
		}
	}

	/** What the serving system knows about the output length when it must decide. */
	public enum LengthPrediction {
		/** The controller sees the length the workload drew. An oracle, labelled as one. */
		ORACLE("oracle"),
		/** The controller sees the drawn length multiplied by a factor, a systematic bias. */
		BIASED_FACTOR("biased-factor"),
		/** The controller sees a constant, whatever the request. */
		CONSTANT("constant"),
		/** The controller sees the observed length of the trace row, which for a replay is the truth. */
		TRACE_OBSERVED("trace-observed");

		/** Label written to the artifacts. */
		private final String label;

		LengthPrediction(String label) {
			this.label = label;
		}

		/**
		 * Returns the label of the mode.
		 *
		 * @return the configuration name
		 */
		public String label() {
			return label;
		}

		/**
		 * Tells whether the mode gives the controller the true length.
		 *
		 * @return {@code true} for {@link #ORACLE} and {@link #TRACE_OBSERVED}
		 */
		public boolean isExact() {
			return this == ORACLE || this == TRACE_OBSERVED;
		}

		/**
		 * Finds a mode by its configuration name.
		 *
		 * @param value the configured name, case insensitive
		 * @return the mode
		 * @throws IllegalArgumentException when no mode matches
		 */
		public static LengthPrediction from(String value) {
			for (LengthPrediction mode : values())
				if (mode.label.equalsIgnoreCase(value.trim()))
					return mode;
			StringBuilder known = new StringBuilder();
			for (LengthPrediction mode : values())
				known.append(known.length() == 0 ? "" : ", ").append(mode.label);
			throw new IllegalArgumentException("Unknown length prediction \"" + value + "\"; known modes are " + known);
		}

		@Override
		public String toString() {
			return label;
		}
	}

	/** How the next arrival instant is obtained. */
	protected Pattern pattern = Pattern.POISSON;
	/** Model every generated request targets, when the workload does not select one itself. */
	protected String modelId = "default";
	/** Period of the fixed and bursty patterns, in seconds. */
	protected double intervalSeconds = 1;
	/** Mean arrival rate of the Poisson and time-varying patterns, in requests per minute. */
	protected double ratePerMinute = 6;
	/** Requests released together by the bursty pattern. */
	protected int burstSize = 4;
	/** Minute boundaries of the time-varying rate profile, ascending. */
	protected double[] rateProfileMinutes = new double[0];
	/** Rate of each segment of the profile, in requests per minute, one more value than the boundaries. */
	protected double[] rateProfilePerMinute = new double[0];
	/** Distribution of the prompt lengths. */
	protected Distribution inputLength;
	/** Distribution of the output lengths, used when the lengths are drawn independently. */
	protected Distribution outputLength;
	/** When positive, the output length is this multiple of the prompt length plus noise. */
	protected double outputToInputRatio = 0;
	/** Standard deviation, in tokens, of the noise added to a correlated output length. */
	protected double outputJitterTokens = 0;
	/** Largest output length a generated request may ask for. */
	protected int maxOutputTokens = 4096;
	/** How many tokens the controller believes a request generates. */
	protected LengthPrediction prediction = LengthPrediction.ORACLE;
	/** Factor applied to the drawn length under {@link LengthPrediction#BIASED_FACTOR}. */
	protected double predictionFactor = 1;
	/** Constant length used under {@link LengthPrediction#CONSTANT}. */
	protected int predictionConstant = 128;
	/** Last requests the workload may generate. */
	protected int maxRequests = Integer.MAX_VALUE;
	/** Arrivals stop after this simulated instant, in seconds. */
	protected double stopArrivalsAfterSeconds = Double.MAX_VALUE;
	/** Priority given to the generated requests. */
	protected int priority = 0;
	/** Service class given to the generated requests. */
	protected String serviceClass = "default";
	/** Time-to-first-token target applied to every generated request, in seconds from its arrival. */
	protected double ttftTargetSeconds = 0;
	/** End-to-end target applied to every generated request, in seconds from its arrival. */
	protected double endToEndTargetSeconds = 0;
	/** Per-token target applied to every generated request, in seconds. */
	protected double tpotTargetSeconds = 0;
	/** Client abandonment delay applied to every generated request, in seconds from its arrival. */
	protected double cancellationDelaySeconds = 0;
	/** Recorded rows, required by {@link Pattern#TRACE}. */
	protected List<TraceRecord> trace = Collections.emptyList();
	/** Name of the prompt-length distribution, kept so a run can rebuild its sampler. */
	protected String inputDistributionName;
	/** Attributes of the prompt-length distribution. */
	protected Map<String, String> inputDistributionAttributes = Collections.emptyMap();
	/** Name of the output-length distribution, kept so a run can rebuild its sampler. */
	protected String outputDistributionName;
	/** Attributes of the output-length distribution. */
	protected Map<String, String> outputDistributionAttributes = Collections.emptyMap();

	/**
	 * Checks that the plan can generate requests.
	 *
	 * @throws IllegalArgumentException when the pattern lacks its parameters or a value is out of
	 *                                  range
	 */
	public void validate() {
		switch (pattern) {
		case FIXED:
		case BURSTY:
			require(intervalSeconds > 0 && !Double.isInfinite(intervalSeconds),
					"the " + pattern + " pattern needs an arrival interval in seconds");
			break;
		case POISSON:
			require(ratePerMinute > 0, "the poisson pattern needs a positive rate per minute");
			break;
		case TIME_VARYING:
			require(ratePerMinute > 0, "the time-varying pattern needs a positive default rate per minute");
			require(rateProfilePerMinute.length > 0, "the time-varying pattern needs at least one rate segment");
			require(rateProfileMinutes.length == rateProfilePerMinute.length - 1,
					"a profile of " + rateProfilePerMinute.length + " rates needs "
							+ (rateProfilePerMinute.length - 1) + " boundaries, found " + rateProfileMinutes.length);
			for (double rate : rateProfilePerMinute)
				require(rate > 0, "a rate segment of the profile is " + rate + " requests per minute");
			for (int i = 1; i < rateProfileMinutes.length; i++)
				require(rateProfileMinutes[i] > rateProfileMinutes[i - 1],
						"the profile boundaries must ascend, but minute " + rateProfileMinutes[i] + " follows "
								+ rateProfileMinutes[i - 1]);
			break;
		case TRACE:
			require(!trace.isEmpty(), "the trace pattern needs at least one recorded row");
			for (int i = 1; i < trace.size(); i++)
				require(trace.get(i).arrivalTime() >= trace.get(i - 1).arrivalTime(),
						"the trace rows must be sorted by arrival time, but row " + i + " arrives at "
								+ trace.get(i).arrivalTime() + " s after row " + (i - 1) + " at "
								+ trace.get(i - 1).arrivalTime() + " s");
			break;
		default:
			break;
		}
		if (pattern != Pattern.TRACE) {
			require(inputLength != null, "the workload needs an input length distribution");
			require(outputToInputRatio > 0 || outputLength != null,
					"the workload needs either an output length distribution or an output-to-input ratio");
		}
		require(maxOutputTokens >= 1, "the maximum output length must be at least one token");
		require(maxRequests >= 1, "the workload must generate at least one request");
		require(stopArrivalsAfterSeconds > 0, "the arrival stop instant must be positive");
		require(prediction != null, "the workload must state how the controller learns the output length");
		if (prediction == LengthPrediction.BIASED_FACTOR)
			require(predictionFactor > 0, "a biased prediction needs a positive factor, found " + predictionFactor);
		if (prediction == LengthPrediction.CONSTANT)
			require(predictionConstant >= 1, "a constant prediction needs at least one token, found "
					+ predictionConstant);
		require(outputToInputRatio >= 0, "the output-to-input ratio cannot be negative, found " + outputToInputRatio);
		require(outputJitterTokens >= 0, "the output jitter cannot be negative, found " + outputJitterTokens);
		require(ttftTargetSeconds >= 0 && endToEndTargetSeconds >= 0 && tpotTargetSeconds >= 0
				&& cancellationDelaySeconds >= 0, "targets and cancellation delays cannot be negative");
	}

	/**
	 * Returns the rate in force at the given instant for the time-varying pattern.
	 *
	 * @param clock simulation time, in seconds
	 * @return requests per minute
	 */
	public double ratePerMinuteAt(double clock) {
		double minutes = clock / 60.0;
		double rate = ratePerMinute;
		for (int i = 0; i < rateProfileMinutes.length && i + 1 < rateProfilePerMinute.length; i++)
			if (minutes >= rateProfileMinutes[i])
				rate = rateProfilePerMinute[i + 1];
		return rate;
	}

	/**
	 * Returns whether the plan generates requests through a recorded row.
	 *
	 * @return {@code true} for {@link Pattern#TRACE}
	 */
	public boolean isTrace() {
		return pattern == Pattern.TRACE;
	}

	/**
	 * Returns a one-line description of the plan, for the run header.
	 *
	 * @return the description
	 */
	public String describe() {
		StringBuilder out = new StringBuilder(pattern.label);
		switch (pattern) {
		case FIXED:
		case BURSTY:
			out.append(String.format(Locale.ROOT, ", every %.3f s", intervalSeconds));
			if (pattern == Pattern.BURSTY)
				out.append(", ").append(burstSize).append(" requests per burst");
			break;
		case POISSON:
			out.append(String.format(Locale.ROOT, ", poisson %.3f req/min", ratePerMinute));
			break;
		case TIME_VARYING:
			out.append(String.format(Locale.ROOT, ", time-varying, mean %.3f req/min, %d segments", ratePerMinute,
					rateProfilePerMinute.length));
			break;
		case TRACE:
			out.append(", trace of ").append(trace.size()).append(" rows");
			break;
		default:
			break;
		}
		out.append("; lengths: input=").append(inputLength == null ? "trace" : inputLength.getName());
		out.append(", output=").append(outputToInputRatio > 0
				? String.format(Locale.ROOT, "%.3f x input", outputToInputRatio)
				: outputLength == null ? "trace" : outputLength.getName());
		out.append("; prediction=").append(prediction.label);
		return out.toString();
	}

	/**
	 * Fails when a condition does not hold, naming the pattern that broke it.
	 *
	 * @param condition  the expectation
	 * @param message    what went wrong
	 */
	protected void require(boolean condition, String message) {
		if (!condition)
			throw new IllegalArgumentException("Workload pattern \"" + pattern + "\": " + message);
	}

	public Pattern getPattern() {
		return pattern;
	}

	public WorkloadPlan setPattern(Pattern pattern) {
		this.pattern = pattern;
		return this;
	}

	public String getModelId() {
		return modelId;
	}

	public WorkloadPlan setModelId(String modelId) {
		this.modelId = modelId;
		return this;
	}

	public double getIntervalSeconds() {
		return intervalSeconds;
	}

	public WorkloadPlan setIntervalSeconds(double intervalSeconds) {
		this.intervalSeconds = intervalSeconds;
		return this;
	}

	public double getRatePerMinute() {
		return ratePerMinute;
	}

	public WorkloadPlan setRatePerMinute(double ratePerMinute) {
		this.ratePerMinute = ratePerMinute;
		return this;
	}

	public int getBurstSize() {
		return burstSize;
	}

	public WorkloadPlan setBurstSize(int burstSize) {
		this.burstSize = burstSize;
		return this;
	}

	public double[] getRateProfileMinutes() {
		return rateProfileMinutes;
	}

	public WorkloadPlan setRateProfile(double[] minutes, double[] perMinute) {
		this.rateProfileMinutes = minutes == null ? new double[0] : minutes.clone();
		this.rateProfilePerMinute = perMinute == null ? new double[0] : perMinute.clone();
		return this;
	}

	public double[] getRateProfilePerMinute() {
		return rateProfilePerMinute;
	}

	public Distribution getInputLength() {
		return inputLength;
	}

	public WorkloadPlan setInputLength(Distribution inputLength) {
		this.inputLength = inputLength;
		return this;
	}

	/**
	 * Declares the prompt length by distribution name and attributes.
	 * <p>
	 * The pair is remembered, so that {@link #copyForRun()} can hand each repetition a sampler
	 * bound to that repetition's random stream. A sampler built once and reused across repetitions
	 * would carry the stream of the first one, which makes two repetitions of the same seed look
	 * different for a reason that has nothing to do with the workload.
	 *
	 * @param name       the PureEdgeSim distribution name, for instance {@code pareto}
	 * @param attributes its parameters, copied
	 * @return this plan
	 */
	public WorkloadPlan setInputDistribution(String name, Map<String, String> attributes) {
		this.inputDistributionName = name;
		this.inputDistributionAttributes = copy(attributes);
		this.inputLength = build(name, this.inputDistributionAttributes);
		return this;
	}

	public Distribution getOutputLength() {
		return outputLength;
	}

	public WorkloadPlan setOutputLength(Distribution outputLength) {
		this.outputLength = outputLength;
		return this;
	}

	/**
	 * Declares the generated length by distribution name and attributes, as
	 * {@link #setInputDistribution(String, Map)} does for the prompt.
	 *
	 * @param name       the PureEdgeSim distribution name
	 * @param attributes its parameters, copied
	 * @return this plan
	 */
	public WorkloadPlan setOutputDistribution(String name, Map<String, String> attributes) {
		this.outputDistributionName = name;
		this.outputDistributionAttributes = copy(attributes);
		this.outputLength = build(name, this.outputDistributionAttributes);
		return this;
	}

	/**
	 * Returns a plan with the same description but with fresh samplers.
	 * <p>
	 * The repetition calls this after it has seeded the random stream, so every draw of the run
	 * comes from that stream. Distributions that were set as objects rather than as a name are
	 * shared: the simulator cannot rebuild what it was not told how to build.
	 *
	 * @return an independent plan holding the same workload definition
	 */
	public WorkloadPlan copyForRun() {
		WorkloadPlan copy = new WorkloadPlan();
		copy.pattern = pattern;
		copy.modelId = modelId;
		copy.intervalSeconds = intervalSeconds;
		copy.ratePerMinute = ratePerMinute;
		copy.burstSize = burstSize;
		copy.rateProfileMinutes = rateProfileMinutes;
		copy.rateProfilePerMinute = rateProfilePerMinute;
		copy.outputToInputRatio = outputToInputRatio;
		copy.outputJitterTokens = outputJitterTokens;
		copy.maxOutputTokens = maxOutputTokens;
		copy.prediction = prediction;
		copy.predictionFactor = predictionFactor;
		copy.predictionConstant = predictionConstant;
		copy.maxRequests = maxRequests;
		copy.stopArrivalsAfterSeconds = stopArrivalsAfterSeconds;
		copy.priority = priority;
		copy.serviceClass = serviceClass;
		copy.ttftTargetSeconds = ttftTargetSeconds;
		copy.endToEndTargetSeconds = endToEndTargetSeconds;
		copy.tpotTargetSeconds = tpotTargetSeconds;
		copy.cancellationDelaySeconds = cancellationDelaySeconds;
		copy.trace = trace;
		copy.inputDistributionName = inputDistributionName;
		copy.inputDistributionAttributes = inputDistributionAttributes;
		copy.outputDistributionName = outputDistributionName;
		copy.outputDistributionAttributes = outputDistributionAttributes;
		copy.inputLength = inputDistributionName == null ? inputLength : build(inputDistributionName,
				inputDistributionAttributes);
		copy.outputLength = outputDistributionName == null ? outputLength : build(outputDistributionName,
				outputDistributionAttributes);
		return copy;
	}

	/**
	 * Builds a sampler from a name and its attributes, using the stream of the current thread.
	 *
	 * @param name       distribution name
	 * @param attributes its parameters
	 * @return the sampler, or {@code null} when the name is not given
	 */
	protected static Distribution build(String name, Map<String, String> attributes) {
		if (name == null)
			return null;
		return DistributionFactory.create(name, attributes);
	}

	/**
	 * Copies a parameter map, keeping the insertion order so that an error message lists the
	 * attributes as they were declared.
	 *
	 * @param attributes the parameters, possibly {@code null}
	 * @return the copy
	 */
	protected static Map<String, String> copy(Map<String, String> attributes) {
		return attributes == null ? Collections.emptyMap() : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
	}

	public double getOutputToInputRatio() {
		return outputToInputRatio;
	}

	public WorkloadPlan setOutputToInputRatio(double outputToInputRatio) {
		this.outputToInputRatio = outputToInputRatio;
		return this;
	}

	public double getOutputJitterTokens() {
		return outputJitterTokens;
	}

	public WorkloadPlan setOutputJitterTokens(double outputJitterTokens) {
		this.outputJitterTokens = outputJitterTokens;
		return this;
	}

	public int getMaxOutputTokens() {
		return maxOutputTokens;
	}

	public WorkloadPlan setMaxOutputTokens(int maxOutputTokens) {
		this.maxOutputTokens = maxOutputTokens;
		return this;
	}

	public LengthPrediction getPrediction() {
		return prediction;
	}

	public WorkloadPlan setPrediction(LengthPrediction prediction) {
		this.prediction = prediction;
		return this;
	}

	public double getPredictionFactor() {
		return predictionFactor;
	}

	public WorkloadPlan setPredictionFactor(double predictionFactor) {
		this.predictionFactor = predictionFactor;
		return this;
	}

	public int getPredictionConstant() {
		return predictionConstant;
	}

	public WorkloadPlan setPredictionConstant(int predictionConstant) {
		this.predictionConstant = predictionConstant;
		return this;
	}

	public int getMaxRequests() {
		return maxRequests;
	}

	public WorkloadPlan setMaxRequests(int maxRequests) {
		this.maxRequests = maxRequests;
		return this;
	}

	public double getStopArrivalsAfterSeconds() {
		return stopArrivalsAfterSeconds;
	}

	public WorkloadPlan setStopArrivalsAfterSeconds(double stopArrivalsAfterSeconds) {
		this.stopArrivalsAfterSeconds = stopArrivalsAfterSeconds;
		return this;
	}

	public int getPriority() {
		return priority;
	}

	public WorkloadPlan setPriority(int priority) {
		this.priority = priority;
		return this;
	}

	public String getServiceClass() {
		return serviceClass;
	}

	public WorkloadPlan setServiceClass(String serviceClass) {
		this.serviceClass = serviceClass == null || serviceClass.isEmpty() ? "default" : serviceClass;
		return this;
	}

	public double getTtftTargetSeconds() {
		return ttftTargetSeconds;
	}

	public WorkloadPlan setTtftTargetSeconds(double ttftTargetSeconds) {
		this.ttftTargetSeconds = ttftTargetSeconds;
		return this;
	}

	public double getEndToEndTargetSeconds() {
		return endToEndTargetSeconds;
	}

	public WorkloadPlan setEndToEndTargetSeconds(double endToEndTargetSeconds) {
		this.endToEndTargetSeconds = endToEndTargetSeconds;
		return this;
	}

	public double getTpotTargetSeconds() {
		return tpotTargetSeconds;
	}

	public WorkloadPlan setTpotTargetSeconds(double tpotTargetSeconds) {
		this.tpotTargetSeconds = tpotTargetSeconds;
		return this;
	}

	public double getCancellationDelaySeconds() {
		return cancellationDelaySeconds;
	}

	public WorkloadPlan setCancellationDelaySeconds(double cancellationDelaySeconds) {
		this.cancellationDelaySeconds = cancellationDelaySeconds;
		return this;
	}

	public List<TraceRecord> getTrace() {
		return trace;
	}

	public WorkloadPlan setTrace(List<TraceRecord> trace) {
		this.trace = trace == null ? Collections.emptyList() : trace;
		return this;
	}

	@Override
	public String toString() {
		return "WorkloadPlan[" + describe() + "]";
	}
}
