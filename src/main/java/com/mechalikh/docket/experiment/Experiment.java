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
package com.mechalikh.docket.experiment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.mechalikh.docket.config.Scenario;
import com.mechalikh.docket.domain.InferenceRequest;
import com.mechalikh.docket.domain.OutcomeReason;
import com.mechalikh.docket.engine.InferenceService;
import com.mechalikh.docket.engine.ReplicaServer;
import com.mechalikh.docket.engine.WorkloadPlan;
import com.mechalikh.docket.engine.WorkloadSource;
import com.mechalikh.docket.metrics.RequestRecorder;
import com.mechalikh.pureedgesim.distribution.Randomness;
import com.mechalikh.pureedgesim.simulationengine.Event;
import com.mechalikh.pureedgesim.simulationengine.OnSimulationStartListener;
import com.mechalikh.pureedgesim.simulationengine.PureEdgeSim;
import com.mechalikh.pureedgesim.simulationengine.SimEntity;

/**
 * Runs a scenario as one or more repetitions and turns each repetition into a {@link RunResult}.
 * <p>
 * A repetition is a fresh event engine with fresh entities. Nothing is carried from one repetition
 * to the next, including the random samplers: {@link WorkloadPlan#copyForRun()} rebuilds them
 * because a PureEdgeSim distribution binds the thread's {@link Randomness} generator when it is
 * created, so reusing a plan would silently reuse the generator of the first repetition and make
 * the following ones draw from a stream that no longer depends on their own seed. The order of the
 * steps below is therefore part of the reproducibility contract, not an implementation detail:
 * seed the generator, name the repetition, rebuild the plan, only then create the entities.
 *
 * @author Charafeddine Mechalikh
 * @since Docket 0.1
 */
public class Experiment {

	/** Scenario every repetition runs. */
	protected final Scenario scenario;
	/** Results of the repetitions, in the order they ran. */
	protected final List<RunResult> runs = new ArrayList<>();

	/**
	 * Prepares an experiment over a validated scenario.
	 *
	 * @param scenario the scenario to run
	 */
	public Experiment(Scenario scenario) {
		if (scenario == null)
			throw new IllegalArgumentException("An experiment needs a scenario");
		this.scenario = scenario;
	}

	/**
	 * Runs every repetition of the scenario.
	 * <p>
	 * A repetition that fails an invariant aborts the experiment: a result whose rows and
	 * aggregates disagree is not a measurement, and quietly keeping the other repetitions would
	 * present a broken run as a sampled one.
	 *
	 * @return the results, one per repetition
	 */
	public List<RunResult> run() {
		runs.clear();
		int repetitions = scenario.getRepetitions();
		for (int repetition = 1; repetition <= repetitions; repetition++)
			runs.add(runRepetition(repetition));
		return runs;
	}

	/**
	 * Runs one repetition end to end.
	 *
	 * @param repetition repetition index, counting from one
	 * @return the aggregates of the repetition
	 */
	public RunResult runRepetition(int repetition) {
		if (repetition < 1)
			throw new IllegalArgumentException("Repetitions count from one, asked for " + repetition);
		long seed = scenario.getSeed();
		Randomness.setSeed(seed);
		Randomness.beginScenario(repetition);
		// PureEdgeSim counts generator requests, not draws: a distribution binds the thread
		// generator once when it is created and then draws from it uncounted. The delta is
		// therefore the number of samplers this repetition built, which is what proves that
		// copyForRun() rebuilt them instead of reusing the previous repetition's stream.
		long bindingsBefore = Randomness.getDrawCount();

		WorkloadPlan plan = scenario.getWorkload().copyForRun();
		RequestRecorder recorder = new RequestRecorder(metadata(seed, repetition));

		PureEdgeSim simulation = new PureEdgeSim();
		InferenceService service = new InferenceService(simulation, scenario.getModel().getId(),
				scenario.getReplicas(), scenario.getCost(), scenario.getAdmission(), scenario.getRouting(),
				scenario.getScheduling(), recorder);
		WorkloadSource source = new WorkloadSource(simulation, plan, service);
		RunController controller = new RunController(simulation, service, recorder,
				scenario.getDurationSeconds(), scenario.getSamplingIntervalSeconds());

		simulation.start();
		double end = simulation.clock();

		// The engine stopped: either the controller reached the configured horizon or the event
		// queue ran dry. Requests still inside the system did not finish, and a row that is not
		// terminal cannot be aggregated, so they are closed with the reason that says why.
		int closed = service.finishRemaining(OutcomeReason.SIMULATION_ENDED);
		for (ReplicaServer replica : service.getReplicas()) {
			replica.accountUntil(end);
			replica.checkCacheConsistency();
		}
		service.checkNoRequestIsLost();

		RunResult result = new RunResult(repetition, seed, end, service, recorder, scenario.getCost(),
				Randomness.getDrawCount() - bindingsBefore);
		int endedByHorizon = 0;
		for (InferenceRequest request : result.getRequests())
			if (OutcomeReason.SIMULATION_ENDED.label().equals(request.getOutcomeReason()))
				endedByHorizon++;
		if (endedByHorizon != closed)
			throw new IllegalStateException("Repetition " + repetition + ": " + closed
					+ " requests were closed when the simulation ended but " + endedByHorizon
					+ " rows carry that reason");
		return result;
	}

	/**
	 * Builds the metadata block the recorder writes in front of every row.
	 *
	 * @param seed       seed the repetition drew from
	 * @param repetition repetition index
	 * @return eight ordered values matching the leading columns of
	 *         {@link RequestRecorder#HEADER}
	 */
	protected Map<String, String> metadata(long seed, int repetition) {
		Map<String, String> metadata = new LinkedHashMap<>();
		metadata.put("experiment", scenario.getName());
		metadata.put("seed", Long.toString(seed));
		metadata.put("repetition", Integer.toString(repetition));
		metadata.put("cost_model", scenario.getCost().name());
		metadata.put("cost_provenance", scenario.getCost().provenance().label());
		metadata.put("cost_calibrated", scenario.getCost().isCalibrated() ? "yes" : "no");
		metadata.put("model_provenance", scenario.getModel().getProvenance().label());
		metadata.put("device_provenance", scenario.getDevice().getProvenance().label());
		return metadata;
	}

	/**
	 * Returns the results collected so far.
	 *
	 * @return one result per finished repetition
	 */
	public List<RunResult> getRuns() {
		return runs;
	}

	/**
	 * Returns the scenario every repetition ran.
	 *
	 * @return the scenario
	 */
	public Scenario getScenario() {
		return scenario;
	}


	/**
	 * Stops a repetition at the configured horizon and takes the periodic resource samples.
	 * <p>
	 * The engine itself has no notion of an experiment length: it runs until the event queue is
	 * empty. This entity is what bounds a repetition in simulated time, and it is also the only
	 * place that sees the whole service at a known instant, which is what a time series of cache
	 * occupancy and batch size needs.
	 */
	protected static class RunController extends SimEntity implements OnSimulationStartListener {

		/** Tag that ends the repetition. */
		protected static final int STOP = 40;
		/** Tag that takes one sample from every replica. */
		protected static final int SAMPLE = 41;

		/** Service whose replicas are sampled and whose requests are left running at the horizon. */
		protected final InferenceService service;
		/** Sink the samples are added to. */
		protected final RequestRecorder recorder;
		/** Simulated instant the repetition ends. */
		protected final double durationSeconds;
		/** Period of the samples, zero or NaN when sampling is off. */
		protected final double samplingIntervalSeconds;

		/**
		 * Creates the controller of one repetition.
		 *
		 * @param simulation             the event engine
		 * @param service                the service being run
		 * @param recorder               the sink of the samples
		 * @param durationSeconds        simulated horizon, positive
		 * @param samplingIntervalSeconds sampling period, zero or negative to disable sampling
		 */
		protected RunController(PureEdgeSim simulation, InferenceService service, RequestRecorder recorder,
				double durationSeconds, double samplingIntervalSeconds) {
			super(simulation);
			if (durationSeconds <= 0 || Double.isNaN(durationSeconds))
				throw new IllegalArgumentException("A repetition needs a positive simulated duration, found "
						+ durationSeconds);
			this.service = service;
			this.recorder = recorder;
			this.durationSeconds = durationSeconds;
			this.samplingIntervalSeconds = samplingIntervalSeconds;
		}

		/**
		 * {@inheritDoc}
		 */
		@Override
		public void onSimulationStart() {
			schedule(this, durationSeconds, STOP);
			if (samplingIntervalSeconds > 0)
				schedule(this, samplingIntervalSeconds, SAMPLE);
		}

		/**
		 * {@inheritDoc}
		 */
		@Override
		protected void processEvent(Event e) {
			if (e.getTag() == STOP) {
				simulation.terminate();
				return;
			}
			if (e.getTag() != SAMPLE)
				throw new IllegalArgumentException("RunController received tag " + e.getTag() + ", it only knows " + STOP
						+ " (STOP) and " + SAMPLE + " (SAMPLE)");
			for (ReplicaServer replica : service.getReplicas())
				recorder.addSample(replica.sample());
			if (simulation.clock() + samplingIntervalSeconds < durationSeconds)
				schedule(this, samplingIntervalSeconds, SAMPLE);
		}
	}

	/**
	 * Returns a one-line summary of the scenario name, the configured repetitions and the
	 * repetitions that have finished so far.
	 *
	 * @return the summary
	 */
	@Override
	public String toString() {
		return "Experiment[" + scenario.getName() + ", repetitions=" + scenario.getRepetitions() + ", runs="
				+ runs.size() + "]";
	}
}
