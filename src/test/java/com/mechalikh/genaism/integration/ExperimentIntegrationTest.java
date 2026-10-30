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
package com.mechalikh.genaism.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.mechalikh.genaism.config.Scenario;
import com.mechalikh.genaism.experiment.Experiment;
import com.mechalikh.genaism.experiment.RunResult;
import com.mechalikh.genaism.support.ScenarioTestSupport;

/**
 * The determinism and reproducibility contract of the experiment runner.
 * <p>
 * A published result is only meaningful if it can be regenerated from a config, a seed and a
 * version. These tests run the engine through the real entities and cost model and compare the
 * resulting rows, so they exercise the whole path a paper would quote from.
 *
 * @author Charafeddine Mechalikh
 */
class ExperimentIntegrationTest {

	@Test
	void theSameSeedRegeneratesTheSameRunFromFreshObjects() {
		Scenario first = ScenarioTestSupport.load(ScenarioTestSupport.LOADED);
		Scenario second = ScenarioTestSupport.load(ScenarioTestSupport.LOADED);
		String a = ScenarioTestSupport.fingerprint(new Experiment(first).runRepetition(1));
		String b = ScenarioTestSupport.fingerprint(new Experiment(second).runRepetition(1));
		assertEquals(a, b, "two loads of one scenario at one seed must reproduce every row");
	}

	@Test
	void eachRepetitionDrawsItsOwnStreamButIsReproducible() {
		Experiment experiment = new Experiment(ScenarioTestSupport.load(ScenarioTestSupport.LOADED));
		List<RunResult> runs = experiment.run();
		assertEquals(3, runs.size());
		assertNotEquals(ScenarioTestSupport.fingerprint(runs.get(0)), ScenarioTestSupport.fingerprint(runs.get(1)),
				"repetitions of one seed are salted differently, so their workloads must differ");
		String again = ScenarioTestSupport.fingerprint(
				new Experiment(ScenarioTestSupport.load(ScenarioTestSupport.LOADED)).run().get(0));
		assertEquals(ScenarioTestSupport.fingerprint(runs.get(0)), again,
				"the first repetition must be identical when the whole experiment is run again");
	}

	@Test
	void aDifferentSeedChangesTheWorkload() {
		String low = ScenarioTestSupport
				.fingerprint(new Experiment(ScenarioTestSupport.load(ScenarioTestSupport.LOADED, "experiment.seed=1"))
						.runRepetition(1));
		String high = ScenarioTestSupport.fingerprint(
				new Experiment(ScenarioTestSupport.load(ScenarioTestSupport.LOADED, "experiment.seed=2024"))
						.runRepetition(1));
		assertNotEquals(low, high, "a new seed must drive a new random workload, not replay the old one");
	}

	@Test
	void theSeedIsCarriedIntoEveryRepetition() {
		RunResult run = new Experiment(ScenarioTestSupport.load(ScenarioTestSupport.LIGHT, "experiment.seed=7"))
				.runRepetition(2);
		assertEquals(7L, run.getSeed());
		assertEquals(2, run.getRepetition());
	}

	@Test
	void raisingTheArrivalRateRaisesUtilisationWithoutBreakingTheEnergyFloor() {
		// Guards the accounting bug where idle time between steps was swallowed and energy fell
		// below the physical floor, non-monotonically in the work offered.
		double lightLoad = utilisationAt(6);
		double heavyLoad = utilisationAt(600);
		assertTrue(heavyLoad >= lightLoad,
				"offering ten times the traffic at the same horizon cannot leave the device less busy");
		assertTrue(heavyLoad <= 1.0 + 1e-9);
	}

	@Test
	void aTraceWhoseSuspendedPrefillsFillTheCacheStillCompletesEveryRequest(@TempDir Path directory)
			throws IOException {
		// Three prompts of 8,000 tokens arrive together on a cache of about 20,900 tokens. Two fit
		// and start decoding; the third suspends its prefill holding the chunks it reserved, which
		// leaves too few blocks for the remaining decode steps of the first two. With preemption off nothing running can free
		// one, so a replica that keeps those chunks without ever releasing them idles with all
		// three requests inside it and the run ends with timeouts instead of results.
		Path trace = directory.resolve("long-prompts.csv");
		List<String> rows = new ArrayList<>();
		rows.add("arrival_time_s,model_id,input_tokens,output_tokens");
		for (int index = 0; index < 3; index++)
			rows.add("0.000,tiny-124m,8000,512");
		Files.write(trace, rows);

		Path scenarioFile = directory.resolve("suspended-prefills.properties");
		List<String> settings = new ArrayList<>(Files.readAllLines(Paths.get(ScenarioTestSupport.LIGHT)));
		settings.add("experiment.name=suspended-prefills");
		settings.add("experiment.durationSeconds=120");
		settings.add("experiment.repetitions=1");
		settings.add("device.memoryGiB=1");
		settings.add("model.maxContextTokens=16384");
		settings.add("replica.allowPreemption=false");
		settings.add("workload.pattern=trace");
		settings.add("workload.traceFile=" + trace.getFileName());
		settings.add("workload.prediction=trace-observed");
		Files.write(scenarioFile, settings);

		RunResult run = new Experiment(ScenarioTestSupport.load(scenarioFile.toString())).runRepetition(1);

		assertEquals(3, run.getArrivedRequests());
		assertEquals(3, run.getCompletedRequests(),
				"a suspended prefill that keeps its blocks must not leave the replica with nothing runnable");
	}

	private double utilisationAt(int ratePerMinute) {
		Scenario scenario = ScenarioTestSupport.load(ScenarioTestSupport.LIGHT,
				"workload.ratePerMinute=" + ratePerMinute, "experiment.repetitions=1");
		RunResult run = new Experiment(scenario).runRepetition(1);
		double duration = run.getDurationSeconds();
		double idle = 0;
		double charged = 0;
		double active = 0;
		for (RunResult.ReplicaStats replica : run.getReplicaStats()) {
			idle += replica.idlePowerWatts * duration;
			charged += replica.activeSeconds + replica.idleSeconds;
			active += replica.activePowerWatts * (replica.activeSeconds + replica.idleSeconds);
		}
		assertTrue(charged + 1e-6 >= duration * run.getReplicaStats().size(), "the run was not fully charged");
		assertTrue(run.getDeviceEnergyJoule() + 1e-6 >= idle, "energy fell below the idle floor");
		assertTrue(run.getDeviceEnergyJoule() <= active + 1e-6, "energy exceeded the active ceiling");
		return run.getUtilisation();
	}
}
