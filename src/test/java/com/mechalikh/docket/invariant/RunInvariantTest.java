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
package com.mechalikh.docket.invariant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.mechalikh.docket.config.Scenario;
import com.mechalikh.docket.domain.InferenceRequest;
import com.mechalikh.docket.domain.RequestState;
import com.mechalikh.docket.experiment.Experiment;
import com.mechalikh.docket.experiment.RunResult;
import com.mechalikh.docket.experiment.RunResult.ReplicaStats;
import com.mechalikh.docket.support.ScenarioTestSupport;

/**
 * Properties that must hold for every finished run, checked independently of the assertions the
 * run result makes on itself.
 * <p>
 * The result already refuses a lost request or an energy that falls below the idle floor when it
 * is built. These tests re-derive the same facts from the exposed counters, so a future refactor
 * that removes the internal guard without changing the numbers still fails.
 *
 * @author Charafeddine Mechalikh
 */
class RunInvariantTest {

	@Test
	void lightScenarioHoldsEveryInvariant() {
		checkScenario(ScenarioTestSupport.load(ScenarioTestSupport.LIGHT));
	}

	@Test
	void loadedScenarioHoldsEveryInvariant() {
		checkScenario(ScenarioTestSupport.load(ScenarioTestSupport.LOADED));
	}

	private void checkScenario(Scenario scenario) {
		Experiment experiment = new Experiment(scenario);
		List<RunResult> runs = experiment.run();
		assertEquals(scenario.getRepetitions(), runs.size());
		for (RunResult run : runs)
			checkRun(run);
	}

	private void checkRun(RunResult run) {
		double duration = run.getDurationSeconds();

		assertTrue(run.getArrivedRequests() > 0, "a scenario with a workload must admit some traffic");
		assertEquals(run.getArrivedRequests(), run.getTerminalRequests(),
				"every recorded row must have reached a terminal state");
		assertTrue(run.getCompletedRequests() <= run.getTerminalRequests());
		assertTrue(run.getGoodRequests() <= run.getObjectiveRequests(),
				"requests that met every objective cannot outnumber requests that stated one");

		for (InferenceRequest request : run.getRequests()) {
			assertTrue(request.getState().isTerminal(), "row " + request.getSerial() + " is not terminal");
			if (request.getState() == RequestState.REJECTED) {
				assertTrue(Double.isNaN(request.ttftSeconds()),
						"a request refused before any GPU work has no first token");
				assertTrue(Double.isNaN(request.endToEndSeconds()),
						"a request refused before any GPU work never completed");
			}
		}

		double idleFloor = 0;
		double activeCeil = 0;
		double chargedSeconds = 0;
		double replicaEnergy = 0;
		double replicaActiveEnergy = 0;
		for (ReplicaStats replica : run.getReplicaStats()) {
			assertTrue(replica.utilisation >= 0 && replica.utilisation <= 1, "utilisation out of range");
			assertTrue(replica.peakCacheOccupancy >= 0 && replica.peakCacheOccupancy <= 1.0 + 1e-9,
					"the cache occupancy cannot exceed the pool");
			assertTrue(replica.totalBlocks >= 1);
			chargedSeconds += replica.activeSeconds + replica.idleSeconds;
			idleFloor += replica.idlePowerWatts * duration;
			activeCeil += replica.activePowerWatts * (replica.activeSeconds + replica.idleSeconds);
			replicaEnergy += replica.totalEnergyJoule;
			replicaActiveEnergy += replica.activeEnergyJoule;
			assertEquals(replica.activeEnergyJoule + replica.idleEnergyJoule, replica.totalEnergyJoule, 1e-6);
		}

		// A step is charged over its whole predicted span even when that reaches past the horizon,
		// so the device time is at least the wall duration; it must never be less, which is what
		// the swallowed-idle-gap bug produced.
		assertTrue(chargedSeconds + 1e-6 >= duration * run.getReplicaStats().size(),
				"every second of the run must be charged to the device, with no interval lost between steps");
		assertEquals(replicaEnergy, run.getDeviceEnergyJoule(), 1e-6);
		assertTrue(run.getDeviceEnergyJoule() + 1e-6 >= idleFloor,
				"a device held for the whole run draws at least its idle energy");
		assertTrue(run.getDeviceEnergyJoule() <= activeCeil + 1e-6,
				"no device can draw more than its active power over the seconds it was charged");
		assertEquals(replicaActiveEnergy, run.getRequestEnergyJoule(), 1e-6,
				"the energy shared across requests must equal the energy of the active intervals");

		assertTrue(run.getUtilisation() >= 0 && run.getUtilisation() <= 1);
		assertTrue(Double.isNaN(run.stat("ttft_s").mean()) || run.stat("ttft_s").mean() >= 0);
	}
}
