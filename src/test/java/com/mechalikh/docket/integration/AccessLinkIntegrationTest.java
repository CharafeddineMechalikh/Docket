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
package com.mechalikh.docket.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.mechalikh.docket.config.Scenario;
import com.mechalikh.docket.domain.InferenceRequest;
import com.mechalikh.docket.domain.RequestState;
import com.mechalikh.docket.experiment.Experiment;
import com.mechalikh.docket.experiment.RunResult;
import com.mechalikh.docket.metrics.RequestRecorder;
import com.mechalikh.docket.support.ScenarioTestSupport;

/**
 * What an access link does to a run: the prompts it carries, the responses it carries back, and
 * the requests it is still holding when the horizon passes.
 *
 * @author Charafeddine Mechalikh
 */
class AccessLinkIntegrationTest {

	/** Prompt length the link tests declare, so a flight is known in advance. */
	private static final int PROMPT_TOKENS = 1000;
	/** Bytes per prompt token and per generated token, which are the defaults of a bearer. */
	private static final double BYTES_PER_TOKEN = 4;
	/** Simulated seconds the link tests run, matching the horizon they override. */
	private static final double DURATION_SECONDS = 6;
	/** The tolerance of a comparison between two durations the ledger already fixed. */
	private static final double EPSILON = 1e-9;

	/**
	 * A stressed run of the light scenario behind one bearer: enough arrivals to make the prompts
	 * queue on it, and a horizon short enough to cut some of them in flight.
	 *
	 * @param capacityMbps capacity the scenario writes for the bearer
	 * @return the first repetition
	 */
	private static RunResult behind(double capacityMbps) {
		Scenario scenario = ScenarioTestSupport.load(ScenarioTestSupport.LIGHT, "device.link=access",
				"link.access.capacityMbps=" + capacityMbps, "link.access.latencyMs=0",
				"workload.inputDistribution.name=constant", "workload.inputDistribution.value=" + PROMPT_TOKENS,
				"workload.outputDistribution.name=constant", "workload.outputDistribution.value=8",
				"workload.maxOutputTokens=8", "workload.ratePerMinute=600", "workload.maxRequests=40",
				"experiment.durationSeconds=" + DURATION_SECONDS, "experiment.repetitions=1", "replicas.count=2");
		return new Experiment(scenario).runRepetition(1);
	}

	/**
	 * Seconds the bearer owes for a message of the given size, before any queueing.
	 *
	 * @param capacityMbps capacity of the bearer
	 * @param tokens       tokens carried by the message
	 * @return transmission time in seconds
	 */
	private static double transfer(double capacityMbps, int tokens) {
		return tokens * BYTES_PER_TOKEN / (capacityMbps * 1e6 / 8);
	}

	@Test
	void aPromptReachesItsReplicaExactlyWhenTheBearerReleasesIt() {
		RunResult run = behind(0.2);
		List<InferenceRequest> rows = run.getRequests();
		assertFalse(rows.isEmpty(), "the run has to have produced rows");
		int enqueued = 0;
		for (InferenceRequest request : rows) {
			assertTrue(request.getUplinkSeconds() > 0,
					"row " + request.getSerial() + " crossed no bearer although the scenario declares one");
			if (request.getQueueEntryTime() <= 0)
				continue;
			enqueued++;
			assertEquals(request.getUplinkSeconds(), request.getQueueEntryTime() - request.getArrivalTime(), EPSILON,
					"row " + request.getSerial() + " entered the queue of its replica too early or too late");
		}
		assertTrue(enqueued > 0, "no request reached a replica, so the queueing step was never exercised");
	}

	@Test
	void theBearerCarriesOnePromptAtATimeInTheOrderTheClientsReleasedThem() {
		RunResult run = behind(0.2);
		// The uplink charge is the wait plus the transmission, and the propagation is zero here, so
		// the interval the bearer was held for ends at the queue entry and starts one transmission
		// earlier. A request still on the wire when the horizon passed never entered a queue.
		List<double[]> flights = new ArrayList<>();
		for (InferenceRequest request : run.getRequests()) {
			if (request.getQueueEntryTime() <= 0)
				continue;
			double end = request.getQueueEntryTime();
			flights.add(new double[] {request.getArrivalTime(),
					end - transfer(0.2, request.getInputTokens()), end});
		}
		flights.sort(Comparator.comparingDouble(flight -> flight[1]));
		assertTrue(flights.size() >= 3, "the run needs several delivered prompts to say anything about queueing");
		for (int i = 0; i < flights.size(); i++) {
			double[] flight = flights.get(i);
			assertTrue(flight[1] >= flight[0] - EPSILON,
					"a prompt started on the bearer before its client released it");
			if (i == 0)
				continue;
			double[] previous = flights.get(i - 1);
			assertTrue(flight[1] >= previous[2] - EPSILON,
					"two prompts held the bearer at the same instant, which a first-in first-out queue forbids");
			assertTrue(flight[2] - flight[1] >= previous[2] - previous[1] - EPSILON,
					"a prompt released later was served ahead of the one it queued behind");
		}
	}

	@Test
	void aPromptStillOnTheWireWhenTheHorizonPassesIsRecordedRatherThanLost() {
		RunResult run = behind(0.1);
		int onTheWire = 0;
		for (InferenceRequest request : run.getRequests()) {
			if (request.getQueueEntryTime() > 0 || request.getUplinkSeconds() <= 0)
				continue;
			onTheWire++;
			assertEquals(RequestState.TIMED_OUT, request.getState(),
					"a prompt that never reached a replica cannot have completed");
			assertEquals(DURATION_SECONDS, request.getTerminalTime(), EPSILON,
					"the request was closed at an instant other than the horizon");
			// The bearer was still holding it when the run ended, so the flight is longer than the
			// time between its release and the horizon.
			assertTrue(request.getUplinkSeconds() > DURATION_SECONDS - request.getArrivalTime() - EPSILON,
					"row " + request.getSerial() + " reports a flight that had already ended");
			assertEquals(request.getTerminalTime(), request.getDeliveryTime(), EPSILON,
					"a request that never reached its device has no answer to deliver");
		}
		assertTrue(onTheWire > 0,
				"the bearer was fast enough to deliver every prompt, so the case was never exercised");
		assertEquals(run.getArrivedRequests(), run.getRequests().size(),
				"a request the link was holding disappeared from the rows");
	}

	@Test
	void theTimeToFirstTokenAlwaysContainsTheUplink() {
		RunResult run = behind(0.2);
		int completed = 0;
		for (InferenceRequest request : run.getRequests()) {
			if (request.getState() != RequestState.COMPLETED)
				continue;
			completed++;
			assertTrue(request.ttftSeconds() >= request.getUplinkSeconds() - EPSILON,
					"row " + request.getSerial() + " answered its client before the prompt arrived");
		}
		assertTrue(completed > 0, "no request finished, so the check was never exercised");
	}

	@Test
	void aResponseIsChargedAfterTheLastTokenAndTheRowKeepsTheDeviceInstant() {
		double capacityMbps = 0.2;
		RunResult run = behind(capacityMbps);
		InferenceRequest first = null;
		int completed = 0;
		for (InferenceRequest request : run.getRequests()) {
			if (request.getState() != RequestState.COMPLETED) {
				assertEquals(request.getTerminalTime(), request.getDeliveryTime(), EPSILON,
						"a request with no response was given a delivery later than its end");
				continue;
			}
			completed++;
			assertTrue(request.getDownlinkSeconds() > 0, "the answer crossed no bearer");
			assertEquals(request.getDownlinkSeconds(), request.getDeliveryTime() - request.getTerminalTime(), EPSILON,
					"row " + request.getSerial() + " reports a delivery that does not match its downlink charge");
			assertEquals(8, request.getGeneratedTokens());
			assertTrue(request.getDownlinkSeconds() >= transfer(capacityMbps, 8) - EPSILON,
					"an answer of eight tokens crossed the bearer faster than its own transmission time");
			if (first == null || request.getTerminalTime() < first.getTerminalTime())
				first = request;
		}
		assertTrue(completed > 0, "no request finished, so the return path was never exercised");
		// The first answer of the run finds the return path empty, so it owes exactly its flight.
		assertEquals(transfer(capacityMbps, 8), first.getDownlinkSeconds(), EPSILON,
				"the first response on an idle bearer was charged a queue it could not have met");
	}

	@Test
	void aFasterBearerShortensTheWaitOfThePromptsBehindIt() {
		RunResult slow = behind(0.2);
		RunResult fast = behind(20);
		double slowTotal = 0;
		double fastTotal = 0;
		for (InferenceRequest request : slow.getRequests())
			slowTotal += request.getUplinkSeconds();
		for (InferenceRequest request : fast.getRequests())
			fastTotal += request.getUplinkSeconds();
		// A bearer a hundred times faster carries each prompt a hundred times sooner and builds no
		// queue, so its total is far below a hundredth of the slow one rather than exactly it: the
		// slow run leaves prompts undelivered at the horizon, and both runs count them at full flight.
		assertTrue(slowTotal / 100 > 3 * fastTotal,
				"the same traffic on a bearer a hundred times faster was charged " + fastTotal + " s of uplink "
						+ "against " + slowTotal + " s, which says the queue never formed");
		assertTrue(maxUplink(slow) > 10 * maxUplink(fast),
				"the slow bearer did not build a queue that the fast one avoided");
	}

	@Test
	void aDeploymentThatNamesNoBearerChargesNoNetworkTime() {
		RunResult run = new Experiment(ScenarioTestSupport.load(ScenarioTestSupport.LIGHT)).runRepetition(1);
		assertFalse(run.getRequests().isEmpty(), "the light scenario has to produce rows");
		for (InferenceRequest request : run.getRequests()) {
			assertEquals(0, request.getUplinkSeconds(), EPSILON, "a scenario without a link charged one anyway");
			assertEquals(0, request.getDownlinkSeconds(), EPSILON, "a scenario without a link sent a response");
			assertEquals(request.getTerminalTime(), request.getDeliveryTime(), EPSILON,
					"without a link the client holds the answer the instant the device ends");
		}
		assertTrue(run.uplinkStats().isEmpty(), "the pooled uplink summary invented observations");
		assertTrue(run.downlinkStats().isEmpty(), "the pooled downlink summary invented observations");
	}

	@Test
	void theRowsCarryOneValuePerHeaderColumn() {
		RunResult run = behind(0.2);
		RequestRecorder recorder = run.getRecorder();
		for (InferenceRequest request : run.getRequests()) {
			List<String> row = recorder.rowValues(request);
			assertEquals(RequestRecorder.HEADER.size(), row.size(),
					"a row of the run does not line up with the header it is written under");
			assertEquals(Long.toString(request.getRequestId()), row.get(RequestRecorder.HEADER.indexOf("request_id")),
					"the row of one request was written as another's");
			assertEquals(Double.parseDouble(row.get(RequestRecorder.HEADER.indexOf("uplink_s"))),
					request.getUplinkSeconds(), 1e-9, "the uplink column is not the flight the ledger charged");
		}
	}

	private static double maxUplink(RunResult run) {
		double max = 0;
		for (InferenceRequest request : run.getRequests())
			max = Math.max(max, request.getUplinkSeconds());
		return max;
	}
}
