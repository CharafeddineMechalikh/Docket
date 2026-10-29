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
package com.mechalikh.genaism.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.mechalikh.genaism.domain.InferenceRequest;
import com.mechalikh.genaism.domain.RequestState;
import com.mechalikh.genaism.metrics.LatencyStats;
import com.mechalikh.genaism.metrics.SloChecks;

/**
 * The distribution summary and the tri-state service-level answer.
 *
 * @author Charafeddine Mechalikh
 */
class MetricsTest {

	private static List<Double> oneToTen() {
		List<Double> values = new ArrayList<>();
		for (int i = 1; i <= 10; i++)
			values.add((double) i);
		return values;
	}

	@Test
	void percentilesUseLinearInterpolationBetweenRanks() {
		LatencyStats stats = LatencyStats.of(oneToTen());
		assertEquals(1.0, stats.percentile(0), 1e-12);
		assertEquals(10.0, stats.percentile(100), 1e-12);
		assertEquals(5.5, stats.percentile(50), 1e-12);
		assertEquals(9.1, stats.percentile(90), 1e-12);
		assertEquals(9.55, stats.percentile(95), 1e-12);
		assertEquals(9.91, stats.percentile(99), 1e-12);
	}

	@Test
	void theMeanSpreadAndErrorMatchTheSampleFormulas() {
		LatencyStats stats = LatencyStats.of(oneToTen());
		assertEquals(10, stats.count());
		assertEquals(55.0, stats.sum(), 1e-9);
		assertEquals(5.5, stats.mean(), 1e-12);
		assertEquals(3.0276503540979685, stats.stdDev(), 1e-9);
		assertEquals(0.9574271071597484, stats.standardError(), 1e-9);
		double se = stats.standardError();
		double[] ci = stats.meanConfidenceInterval95();
		assertEquals(5.5 - 1.96 * se, ci[0], 1e-9);
		assertEquals(5.5 + 1.96 * se, ci[1], 1e-9);
	}

	@Test
	void aSingleObservationHasPercentilesButNoSpread() {
		LatencyStats stats = LatencyStats.of(Arrays.asList(3.0));
		assertEquals(3.0, stats.percentile(99), 1e-12);
		assertTrue(Double.isNaN(stats.stdDev()));
		assertTrue(Double.isNaN(stats.standardError()));
		assertTrue(Double.isNaN(stats.meanConfidenceInterval95()[0]));
	}

	@Test
	void anEmptySummaryReportsNoObservation() {
		LatencyStats stats = LatencyStats.empty(3);
		assertTrue(stats.isEmpty());
		assertEquals(0, stats.count());
		assertTrue(Double.isNaN(stats.mean()));
		assertTrue(Double.isNaN(stats.percentile(50)));
		assertEquals(3, stats.repetitions());
	}

	@Test
	void missingOrNonFiniteObservationsAreRefused() {
		List<Double> withNaN = new ArrayList<>(oneToTen());
		withNaN.add(Double.NaN);
		assertThrows(IllegalArgumentException.class, () -> new LatencyStats(withNaN, 1));
		assertThrows(IllegalArgumentException.class, () -> new LatencyStats(Arrays.asList(1.0, null), 1));
		assertThrows(IllegalArgumentException.class, () -> new LatencyStats(oneToTen(), 0));
	}

	@Test
	void theExposedValuesAreSortedAndImmutable() {
		LatencyStats stats = LatencyStats.of(Arrays.asList(5.0, 1.0, 3.0));
		assertEquals(Arrays.asList(1.0, 3.0, 5.0), stats.values());
		assertThrows(UnsupportedOperationException.class, () -> stats.values().add(2.0));
	}

	@Test
	void percentileRejectsAnOutOfRangeRequest() {
		LatencyStats stats = LatencyStats.of(oneToTen());
		assertThrows(IllegalArgumentException.class, () -> stats.percentile(-1));
		assertThrows(IllegalArgumentException.class, () -> stats.percentile(101));
	}

	private static InferenceRequest served(long id, double arrival, double firstToken, double lastToken, int tokens) {
		InferenceRequest request = new InferenceRequest(id, "m", 8).setOutputTokens(tokens);
		request.setArrivalTime(arrival);
		request.transitionTo(RequestState.ARRIVED);
		request.transitionTo(RequestState.ADMITTED);
		request.transitionTo(RequestState.QUEUED);
		request.transitionTo(RequestState.PREFILLING);
		request.emitToken(firstToken, 0.0);
		double step = tokens > 1 ? (lastToken - firstToken) / (tokens - 1) : 0;
		for (int i = 1; i < tokens; i++)
			request.emitToken(firstToken + step * i, step);
		return request;
	}

	@Test
	void aRequestWithoutAnyDeadlineIsNotApplicable() {
		InferenceRequest request = served(1, 0, 0.5, 1.5, 3);
		assertNull(SloChecks.ttftMet(request));
		assertNull(SloChecks.endToEndMet(request));
		assertNull(SloChecks.tpotMet(request));
		assertNull(SloChecks.allMet(request));
	}

	@Test
	void meetingAMissingDeadlineIsAFalseNotANull() {
		InferenceRequest met = served(2, 0, 0.5, 1.5, 3).setTtftDeadline(1.0);
		assertTrue(SloChecks.ttftMet(met));
		InferenceRequest missed = served(3, 0, 0.5, 1.5, 3).setTtftDeadline(0.4);
		assertFalse(SloChecks.ttftMet(missed));
	}

	@Test
	void anUncompletedRequestMissesItsEndToEndObjective() {
		InferenceRequest request = served(4, 0, 0.5, 1.5, 3).setEndToEndDeadline(10.0);
		assertFalse(SloChecks.endToEndMet(request));
		request.finish(RequestState.COMPLETED, "", 1.5);
		assertTrue(SloChecks.endToEndMet(request));
	}

	@Test
	void aSingleTokenRequestHasNoPaceObjective() {
		InferenceRequest request = served(5, 0, 0.5, 0.5, 1).setTpotTarget(0.1);
		assertNull(SloChecks.tpotMet(request));
	}

	@Test
	void allMetNeedsEveryApplicableCheckToHold() {
		InferenceRequest request = served(6, 0, 0.5, 1.5, 3).setTtftDeadline(1.0).setEndToEndDeadline(2.0)
				.setTpotTarget(0.5);
		request.finish(RequestState.COMPLETED, "", 1.5);
		assertTrue(SloChecks.allMet(request));
		request.setTtftDeadline(0.2);
		assertFalse(SloChecks.allMet(request));
	}

	@Test
	void fieldAndApplicableHelpersEncodeTheTriState() {
		assertEquals("", SloChecks.field(null));
		assertEquals("true", SloChecks.field(Boolean.TRUE));
		assertEquals(2, SloChecks.applicable(Arrays.asList(Boolean.TRUE, Boolean.FALSE, null)));
		assertEquals(1, SloChecks.met(Arrays.asList(Boolean.TRUE, Boolean.FALSE, null)));
	}
}
