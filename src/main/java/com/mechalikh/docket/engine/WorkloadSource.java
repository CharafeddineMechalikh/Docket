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
package com.mechalikh.docket.engine;

import java.util.Collections;
import java.util.Map;

import com.mechalikh.docket.domain.InferenceRequest;
import com.mechalikh.docket.domain.TraceRecord;
import com.mechalikh.pureedgesim.distribution.Distribution;
import com.mechalikh.pureedgesim.distribution.DistributionFactory;
import com.mechalikh.pureedgesim.distribution.Randomness;
import com.mechalikh.pureedgesim.simulationengine.Event;
import com.mechalikh.pureedgesim.simulationengine.OnSimulationStartListener;
import com.mechalikh.pureedgesim.simulationengine.PureEdgeSim;
import com.mechalikh.pureedgesim.simulationengine.SimEntity;

/**
 * Releases the requests of a workload into a service, following a {@link WorkloadPlan}.
 * <p>
 * The source only decides when a request arrives and what it asks for. It never knows whether
 * the request will be served: admission, routing and scheduling belong to the service, so an
 * experiment cannot leak the state of the deployment into the traffic.
 * <p>
 * Every random figure comes from the PureEdgeSim {@link Randomness} streams, which are seeded
 * once per experiment. Two runs of the same configuration with the same seed therefore draw the
 * same arrivals and the same lengths, which is what the determinism test checks.
 *
 * @author Charafeddine Mechalikh
 * @since Docket 0.1
 */
public class WorkloadSource extends SimEntity implements OnSimulationStartListener {

	/** Tag of the event that releases the next batch of requests. */
	public static final int ARRIVE = 1;

	/** Plan that decides the arrivals and the request shapes. */
	protected final WorkloadPlan plan;
	/** Service the requests are sent to. */
	protected final InferenceService service;
	/** Exponential inter-arrival sampler, rebuilt when the rate profile changes segment. */
	protected Distribution interArrival;
	/** Normal sampler of the noise added to a correlated output length, built on first use. */
	protected Distribution jitter;
	/** Segment of the rate profile the sampler was built for, {@code -1} when the rate is constant. */
	protected double samplerRate = -1;
	/** Index of the next trace row to release. */
	protected int traceIndex;
	/** Requests generated so far, used for the identifiers and for the maximum count. */
	protected long generatedRequests;
	/** Requests still to be released inside the current burst. */
	protected int burstRemaining;

	/**
	 * Creates the source of one experiment.
	 *
	 * @param simulation the event engine
	 * @param plan       the workload to generate, validated by the caller
	 * @param service    the service that receives the requests
	 */
	public WorkloadSource(PureEdgeSim simulation, WorkloadPlan plan, InferenceService service) {
		super(simulation);
		this.plan = plan;
		this.service = service;
	}

	/**
	 * Schedules the first arrival at the start of the simulation.
	 * <p>
	 * A trace replays the recorded instants, and a random arrival process draws its first gap, so
	 * both start when the drawing says. The fixed and bursty patterns have a period but no offset,
	 * so their first request arrives at the instant zero.
	 */
	@Override
	public void onSimulationStart() {
		if (plan.isTrace() || plan.getPattern() == WorkloadPlan.Pattern.POISSON
				|| plan.getPattern() == WorkloadPlan.Pattern.TIME_VARYING) {
			scheduleNextArrival();
			return;
		}
		scheduleNow(this, ARRIVE);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	protected void processEvent(Event e) {
		if (e.getTag() != ARRIVE)
			throw new IllegalArgumentException(getClass().getSimpleName() + " received tag " + e.getTag()
					+ ", it only knows " + ARRIVE + " (ARRIVE)");
		releaseOne();
		if (burstRemaining > 0) {
			burstRemaining--;
			scheduleNow(this, ARRIVE);
		} else {
			scheduleNextArrival();
		}
	}

	/**
	 * Builds one request and hands it to the service at the current instant.
	 */
	protected void releaseOne() {
		InferenceRequest request = nextRequest(simulation.clock(), generatedRequests + 1);
		generatedRequests++;
		request.setArrivalTime(simulation.clock());
		service.submit(request);
	}

	/**
	 * Builds the request a given arrival instant would produce.
	 * <p>
	 * Exposed for the tests, which need to check the shapes without running an engine. The
	 * identifiers are sequential, so the order of the calls decides the identifier of a request
	 * and, with it, the tie-break of two requests arriving at the same instant.
	 *
	 * @param clock          arrival instant, in seconds
	 * @param requestId      identifier to give the request
	 * @return the generated request
	 */
	public InferenceRequest nextRequest(double clock, long requestId) {
		if (plan.isTrace())
			return fromTrace(clock, requestId);
		int input = positiveSample(plan.getInputLength(), "input length");
		int output = outputLength(input);
		InferenceRequest request = new InferenceRequest(requestId, plan.getModelId(), input);
		request.setOutputTokens(output);
		request.setMaxOutputTokens(plan.getMaxOutputTokens());
		request.setPredictedOutputTokens(predictedLength(output));
		request.setPriority(plan.getPriority());
		request.setServiceClass(plan.getServiceClass());
		applyTargets(request, clock, plan.getTtftTargetSeconds(), plan.getEndToEndTargetSeconds(),
				plan.getTpotTargetSeconds(), plan.getCancellationDelaySeconds());
		return request;
	}

	/**
	 * Builds the request of the next trace row.
	 *
	 * @param clock     the arrival instant of the row, which the caller has already honoured
	 * @param requestId identifier to give the request
	 * @return the replayed request
	 */
	protected InferenceRequest fromTrace(double clock, long requestId) {
		TraceRecord row = plan.getTrace().get(traceIndex);
		traceIndex++;
		InferenceRequest request = new InferenceRequest(requestId, row.modelId(), row.inputTokens());
		request.setOutputTokens(row.outputTokens());
		request.setMaxOutputTokens(Math.max(row.outputTokens(), plan.getMaxOutputTokens()));
		request.setPredictedOutputTokens(predictedLength(row.outputTokens()));
		request.setPriority(row.priority());
		request.setServiceClass(row.serviceClass());
		request.setSessionId(row.sessionId());
		applyTargets(request, clock, row.ttftTarget(), row.endToEndTarget(), row.tpotTarget(),
				row.cancellationDelay());
		return request;
	}

	/**
	 * Turns the relative targets of a request into absolute instants.
	 *
	 * @param request        the request to annotate
	 * @param clock          its arrival instant
	 * @param ttftTarget     time-to-first-token target, zero for none
	 * @param endToEndTarget end-to-end target, zero for none
	 * @param tpotTarget     per-token target, zero for none
	 * @param cancellation   abandonment delay, zero for never
	 */
	protected static void applyTargets(InferenceRequest request, double clock, double ttftTarget,
			double endToEndTarget, double tpotTarget, double cancellation) {
		if (ttftTarget > 0)
			request.setTtftDeadline(clock + ttftTarget);
		if (endToEndTarget > 0)
			request.setEndToEndDeadline(clock + endToEndTarget);
		if (tpotTarget > 0)
			request.setTpotTarget(tpotTarget);
		if (cancellation > 0)
			request.setCancellationTime(clock + cancellation);
	}

	/**
	 * Draws the output length of a request.
	 *
	 * @param inputTokens the prompt length, used when the workload declares the two lengths
	 *                    correlated
	 * @return the number of tokens the request asks the model to generate
	 */
	protected int outputLength(int inputTokens) {
		if (plan.getOutputToInputRatio() <= 0)
			return clampOutput(positiveSample(plan.getOutputLength(), "output length"));
		double scaled = inputTokens * plan.getOutputToInputRatio();
		if (plan.getOutputJitterTokens() > 0)
			scaled += jitterSampler().sample();
		return clampOutput((int) Math.round(scaled));
	}

	/**
	 * Returns the sampler of the correlated output noise, built once.
	 *
	 * @return the normal distribution declared by the plan
	 */
	protected Distribution jitterSampler() {
		if (jitter == null)
			jitter = DistributionFactory.create("normal",
					Map.of("mean", "0", "std", Double.toString(plan.getOutputJitterTokens())), Randomness.getRandom());
		return jitter;
	}

	/**
	 * Keeps an output length inside the bounds the plan declares.
	 *
	 * @param drawn the sampled length
	 * @return the length the request asks for
	 */
	protected int clampOutput(int drawn) {
		return Math.max(1, Math.min(drawn, plan.getMaxOutputTokens()));
	}

	/**
	 * Returns the length the serving system is allowed to believe at admission time.
	 *
	 * @param drawn the length the request will really produce
	 * @return the predicted length
	 */
	protected int predictedLength(int drawn) {
		switch (plan.getPrediction()) {
		case ORACLE:
		case TRACE_OBSERVED:
			return drawn;
		case BIASED_FACTOR:
			return Math.max(1, (int) Math.round(drawn * plan.getPredictionFactor()));
		case CONSTANT:
			return plan.getPredictionConstant();
		default:
			throw new IllegalStateException("Unsupported prediction mode " + plan.getPrediction());
		}
	}

	/**
	 * Draws a positive integer from a distribution.
	 *
	 * @param distribution the sampler, not null
	 * @param name         what the drawn value is, for the error message
	 * @return a value of at least one
	 * @throws IllegalArgumentException when the distribution is missing
	 */
	protected static int positiveSample(Distribution distribution, String name) {
		if (distribution == null)
			throw new IllegalArgumentException("The workload plan declares no " + name + " distribution");
		return Math.max(1, distribution.sampleInt());
	}

	/**
	 * Schedules the next arrival instant, or stops generating when the plan is exhausted.
	 */
	protected void scheduleNextArrival() {
		if (generatedRequests >= plan.getMaxRequests())
			return;
		double delay;
		if (plan.isTrace()) {
			if (traceIndex >= plan.getTrace().size())
				return;
			delay = Math.max(0, plan.getTrace().get(traceIndex).arrivalTime() - simulation.clock());
		} else {
			delay = nextInterArrivalSeconds();
			if (simulation.clock() + delay > plan.getStopArrivalsAfterSeconds())
				return;
		}
		schedule(this, delay, ARRIVE);
	}

	/**
	 * Draws the gap to the next arrival of the open-loop patterns.
	 *
	 * @return seconds, zero for the fixed cadence when the interval is configured to zero
	 */
	protected double nextInterArrivalSeconds() {
		switch (plan.getPattern()) {
		case FIXED:
			return plan.getIntervalSeconds();
		case BURSTY:
			burstRemaining = plan.getBurstSize() - 1;
			return plan.getIntervalSeconds();
		case POISSON:
			return exponentialDelay(plan.getRatePerMinute());
		case TIME_VARYING:
			return exponentialDelay(plan.ratePerMinuteAt(simulation.clock()));
		default:
			throw new IllegalStateException("The pattern " + plan.getPattern() + " does not draw gaps");
		}
	}

	/**
	 * Draws an exponential inter-arrival time for the given mean rate.
	 *
	 * @param ratePerMinute mean arrival rate, positive
	 * @return seconds until the next arrival
	 */
	protected double exponentialDelay(double ratePerMinute) {
		if (ratePerMinute != samplerRate) {
			interArrival = DistributionFactory.create("exponential",
					Collections.singletonMap("mean", Double.toString(60.0 / ratePerMinute)), Randomness.getRandom());
			samplerRate = ratePerMinute;
		}
		return Math.max(0, interArrival.sample());
	}

	/**
	 * Returns how many requests the source released.
	 *
	 * @return generated requests
	 */
	public long getGeneratedRequests() {
		return generatedRequests;
	}

	/**
	 * Returns the plan the source follows.
	 *
	 * @return the workload plan
	 */
	public WorkloadPlan getPlan() {
		return plan;
	}

	/**
	 * Returns a one-line summary of the releases made so far, naming the arrival pattern and the
	 * number of requests the source has generated.
	 *
	 * @return the pattern and the generated request count
	 */
	@Override
	public String toString() {
		return "WorkloadSource[" + plan.getPattern() + ", generated=" + generatedRequests + "]";
	}
}
