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
import com.mechalikh.docket.examples.offload.OffloadByPromptSizeRouting;
import com.mechalikh.docket.policy.ReplicaLoad;

/**
 * The prompt-size crossover of the access-link offloading example.
 *
 * @author Charafeddine Mechalikh
 */
class OffloadByPromptSizeRoutingTest {

	private final OffloadByPromptSizeRouting policy = new OffloadByPromptSizeRouting();

	@Test
	void aShortPromptIsOffloadedToTheRemoteEvenWhenTheLocalMachineIsIdle() {
		ReplicaLoad localIdle = load(0, "edge-datacenter", 0);
		ReplicaLoad remoteBusy = load(1, "cloud-accelerator", 3);
		assertEquals(1, policy.choose(List.of(localIdle, remoteBusy), request(100)),
				"the prompt crosses the bearer because it is small enough for the fast machine to still win");
	}

	@Test
	void aLongPromptStaysLocalEvenWhenTheRemoteIsIdle() {
		ReplicaLoad localBusy = load(0, "edge-datacenter", 3);
		ReplicaLoad remoteIdle = load(1, "cloud-accelerator", 0);
		assertEquals(0, policy.choose(List.of(localBusy, remoteIdle), request(1200)),
				"the transfer of a long prompt costs more than the compute saved, so it stays on site");
	}

	@Test
	void withinThePreferredTierTheLeastLoadedReplicaTakesTheRequest() {
		ReplicaLoad fullerRemote = load(2, "cloud-accelerator", 4);
		ReplicaLoad emptierRemote = load(5, "cloud-accelerator", 1);
		assertEquals(5, policy.choose(List.of(fullerRemote, emptierRemote), request(100)));
	}

	@Test
	void whenThePreferredTierIsAbsentTheRequestGoesToTheOtherOne() {
		ReplicaLoad onlyRemote = load(1, "cloud-accelerator", 0);
		assertEquals(1, policy.choose(List.of(onlyRemote), request(1200)),
				"a long prompt prefers the local site, but when there is no local replica it offloads");
	}

	private static ReplicaLoad load(int id, String tier, int inside) {
		return new ReplicaLoad(id, "device-" + id, tier, 64, inside, 0, 1_000_000, 1_000_000, 0);
	}

	private static InferenceRequest request(int inputTokens) {
		return new InferenceRequest(1, "edge-chat", inputTokens);
	}
}
