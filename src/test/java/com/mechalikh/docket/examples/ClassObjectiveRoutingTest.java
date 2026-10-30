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
import com.mechalikh.docket.examples.fairshare.ClassObjectiveRouting;
import com.mechalikh.docket.policy.ReplicaLoad;

/**
 * The routing rule of the class fair-share example.
 *
 * @author Charafeddine Mechalikh
 */
class ClassObjectiveRoutingTest {

	private final ClassObjectiveRouting policy = new ClassObjectiveRouting();

	@Test
	void aLatencySensitiveRequestGoesWhereTheLeastWorkIsOwed() {
		List<ReplicaLoad> loads = List.of(load(0, 0, 900_000, 9), load(1, 0, 100_000, 1));
		assertEquals(1, policy.choose(loads, request("interactive")));
	}

	@Test
	void aThroughputTolerantRequestGoesWhereTheMostCacheIsFree() {
		List<ReplicaLoad> loads = List.of(load(0, 0, 900_000, 9), load(1, 0, 100_000, 1));
		assertEquals(0, policy.choose(loads, request("batch")));
	}

	@Test
	void equalScoresFallBackToTheLowestIdentifier() {
		List<ReplicaLoad> loads = List.of(load(3, 0, 500_000, 2), load(2, 0, 500_000, 2));
		assertEquals(2, policy.choose(loads, request("interactive")));
		assertEquals(2, policy.choose(loads, request("batch")));
	}

	@Test
	void noFeasibleReplicaIsReportedAsARefusal() {
		List<ReplicaLoad> loads = List.of(load(0, 0, 10, 0), load(1, 0, 10, 0));
		assertEquals(-1, policy.choose(loads, request("interactive")));
	}

	private static ReplicaLoad load(int id, int waiting, int freeCache, double pendingWork) {
		return new ReplicaLoad(id, waiting, 0, freeCache, 1_000_000, pendingWork);
	}

	private static InferenceRequest request(String serviceClass) {
		return new InferenceRequest(1, "sla-mix", 1_000).setServiceClass(serviceClass);
	}
}
