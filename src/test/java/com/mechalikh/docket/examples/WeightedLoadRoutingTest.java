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
package com.mechalikh.docket.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.mechalikh.docket.domain.InferenceRequest;
import com.mechalikh.docket.examples.weighted.WeightedLoadRouting;
import com.mechalikh.docket.policy.ReplicaLoad;

/**
 * The three-signal score of the weighted routing example.
 *
 * @author Charafeddine Mechalikh
 */
class WeightedLoadRoutingTest {

	private final WeightedLoadRouting policy = new WeightedLoadRouting();

	@Test
	void theScoreIsTheWeightedSumOfTheFourLoads() {
		// 1.0 * 2 waiting + 0.2 * 1 running + 1.0 * half the cache taken + 2.0 * 3 s pending.
		assertEquals(8.7, WeightedLoadRouting.score(load(0, 2, 1, 500_000, 3)), 1e-12);
	}

	@Test
	void aLongQueueOutweighsASlightlyFullerCache() {
		ReplicaLoad queued = load(0, 4, 0, 1_000_000, 0);
		ReplicaLoad cached = load(1, 0, 0, 600_000, 0);
		assertEquals(1, policy.choose(List.of(queued, cached), request()));
	}

	@Test
	void equalScoresFallBackToTheLowestIdentifier() {
		List<ReplicaLoad> loads = List.of(load(2, 1, 1, 800_000, 5), load(1, 1, 1, 800_000, 5));
		assertEquals(1, policy.choose(loads, request()));
	}

	private static ReplicaLoad load(int id, int waiting, int running, int freeCache, double pending) {
		return new ReplicaLoad(id, waiting, running, freeCache, 1_000_000, pending);
	}

	private static InferenceRequest request() {
		return new InferenceRequest(1, "sla-mix", 1_000);
	}
}
