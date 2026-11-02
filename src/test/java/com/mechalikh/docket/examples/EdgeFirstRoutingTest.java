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
import com.mechalikh.docket.examples.edgefirst.EdgeFirstRouting;
import com.mechalikh.docket.examples.edgefirst.EdgeFirstWithinBudgetRouting;
import com.mechalikh.docket.policy.ReplicaLoad;

/**
 * The tier order of the edge-first placement example and its budget guard.
 *
 * @author Charafeddine Mechalikh
 */
class EdgeFirstRoutingTest {

	private final EdgeFirstRouting edgeFirst = new EdgeFirstRouting();
	private final EdgeFirstWithinBudgetRouting guarded = new EdgeFirstWithinBudgetRouting();

	@Test
	void theCheapestTierWinsEvenWhenACheaperOneIsBusyAndAFasterOneIdle() {
		ReplicaLoad busy = load(0, "edge-device", 5, 5);
		ReplicaLoad idleCloud = load(1, "cloud-accelerator", 0, 0);
		assertEquals(0, edgeFirst.choose(List.of(busy, idleCloud), request()),
				"the preference is for the tier, not for the least loaded machine");
	}

	@Test
	void withinATierTheLeastLoadedReplicaReceivesTheRequest() {
		ReplicaLoad fuller = load(2, "edge-device", 4, 4);
		ReplicaLoad emptier = load(3, "edge-device", 1, 1);
		assertEquals(3, edgeFirst.choose(List.of(fuller, emptier), request()));
	}

	@Test
	void equalLoadsFallBackToTheLowestIdentifier() {
		ReplicaLoad later = load(5, "edge-datacenter", 2, 2);
		ReplicaLoad first = load(1, "edge-datacenter", 2, 2);
		assertEquals(1, edgeFirst.choose(List.of(later, first), request()));
	}

	@Test
	void anUnlistedTierIsPlacedAfterEveryListedOne() {
		ReplicaLoad unlisted = load(0, "unknown-machine", 0, 0);
		ReplicaLoad cloud = load(1, "cloud-accelerator", 3, 3);
		assertEquals(1, edgeFirst.choose(List.of(unlisted, cloud), request()),
				"cloud-accelerator is listed, so it comes first even though the unknown tier is idle");
	}

	@Test
	void theGuardSkipsATierWhoseQueuedWorkBustsTheFirstTokenBudget() {
		ReplicaLoad busyEdge = load(0, "edge-device", 5, 5);
		ReplicaLoad affordableServer = load(1, "edge-datacenter", 0, 0.5);
		InferenceRequest withBudget = request();
		withBudget.setArrivalTime(0);
		withBudget.setTtftDeadline(2);
		assertEquals(1, guarded.choose(List.of(busyEdge, affordableServer), withBudget),
				"five seconds of promised work does not fit a two second budget, so the request moves up a tier");
	}

	@Test
	void theGuardKeepsTheEdgeWhenItsQueuedWorkFitsTheBudget() {
		ReplicaLoad edge = load(0, "edge-device", 0, 1);
		ReplicaLoad server = load(1, "edge-datacenter", 0, 0);
		InferenceRequest withBudget = request();
		withBudget.setArrivalTime(0);
		withBudget.setTtftDeadline(2);
		assertEquals(0, guarded.choose(List.of(edge, server), withBudget));
	}

	@Test
	void aRequestThatStatesNoBudgetIsPlacedAsTheUnguardedPolicyWould() {
		ReplicaLoad busyEdge = load(0, "edge-device", 5, 5);
		ReplicaLoad idleCloud = load(1, "cloud-accelerator", 0, 0);
		assertEquals(0, guarded.choose(List.of(busyEdge, idleCloud), request()));
	}

	@Test
	void whenNoCandidateMeetsTheBudgetTheLeastBusyMachineTakesTheRequest() {
		ReplicaLoad busyEdge = load(0, "edge-device", 6, 30);
		ReplicaLoad fullerCloud = load(1, "cloud-accelerator", 2, 40);
		InferenceRequest withBudget = request();
		withBudget.setArrivalTime(0);
		withBudget.setTtftDeadline(2);
		assertEquals(1, guarded.choose(List.of(busyEdge, fullerCloud), withBudget),
				"missing is cheapest on the machine with the fewest requests already inside");
	}

	private static ReplicaLoad load(int id, String tier, int inside, double pending) {
		return new ReplicaLoad(id, "device-" + id, tier, 64, inside, 0, 1_000_000, 1_000_000, pending);
	}

	private static InferenceRequest request() {
		return new InferenceRequest(1, "edge-chat", 100);
	}
}
