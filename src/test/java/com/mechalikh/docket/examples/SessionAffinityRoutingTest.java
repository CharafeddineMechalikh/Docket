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
import com.mechalikh.docket.examples.affinity.SessionAffinityRouting;
import com.mechalikh.docket.policy.ReplicaLoad;

/**
 * The routing memory of the session affinity example.
 *
 * @author Charafeddine Mechalikh
 */
class SessionAffinityRoutingTest {

	private static final List<ReplicaLoad> IDLE = List.of(load(0, 0, 900_000), load(1, 0, 900_000));
	private static final List<ReplicaLoad> FIRST_BUSY = List.of(load(0, 3, 900_000), load(1, 0, 900_000));

	private final SessionAffinityRouting policy = new SessionAffinityRouting();

	@Test
	void theSecondTurnOfASessionReturnsToTheFirstReplica() {
		assertEquals(1, policy.choose(FIRST_BUSY, request("chat-1")));
		assertEquals(1, policy.choose(IDLE, request("chat-1")),
				"the remembered replica wins even when another one is idle");
	}

	@Test
	void aRememberedReplicaThatCannotHoldTheTurnIsAbandoned() {
		assertEquals(0, policy.choose(IDLE, request("chat-1")));
		List<ReplicaLoad> noRoom = List.of(load(0, 0, 10), load(1, 0, 900_000));
		assertEquals(1, policy.choose(noRoom, request("chat-1")));
		assertEquals(1, policy.choose(noRoom, request("chat-1")), "the new replica is remembered");
	}

	@Test
	void aRequestWithoutASessionIsRoutedByLoadAndRemembersNothing() {
		assertEquals(1, policy.choose(FIRST_BUSY, new InferenceRequest(1, "sla-mix", 1_000)));
		assertEquals(0, policy.rememberedSessions());
	}

	@Test
	void resetForgetsEverySession() {
		policy.choose(FIRST_BUSY, request("chat-1"));
		policy.choose(IDLE, request("chat-2"));
		assertEquals(2, policy.rememberedSessions());
		policy.reset();
		assertEquals(0, policy.rememberedSessions());
	}

	private static ReplicaLoad load(int id, int waiting, int freeCache) {
		return new ReplicaLoad(id, waiting, 0, freeCache, 1_000_000, waiting);
	}

	private static InferenceRequest request(String session) {
		return new InferenceRequest(1, "sla-mix", 1_000).setSessionId(session);
	}
}
