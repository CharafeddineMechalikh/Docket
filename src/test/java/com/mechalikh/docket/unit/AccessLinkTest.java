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
package com.mechalikh.docket.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.mechalikh.docket.domain.InferenceRequest;
import com.mechalikh.docket.domain.Provenance;
import com.mechalikh.docket.network.AccessLink;
import com.mechalikh.docket.network.LinkProfile;

/**
 * The arithmetic of one hop: what a bearer charges a message, and what it makes the messages
 * behind it wait.
 *
 * @author Charafeddine Mechalikh
 */
class AccessLinkTest {

	/** 0.8 Mbps is 100,000 bytes per second, so a 1000-token prompt of four bytes takes 0.04 s. */
	private static final double CAPACITY_MBPS = 0.8;
	/** One way delay of the test bearer, in seconds. */
	private static final double LATENCY_SECONDS = 0.01;
	/** Transmission time of a 1000-token prompt at that capacity, in seconds. */
	private static final double PROMPT_TRANSFER_SECONDS = 0.04;

	private static LinkProfile bearer() {
		return new LinkProfile().setId("test").setCapacityMbps(CAPACITY_MBPS).setLatencyMs(10);
	}

	private static InferenceRequest prompt(int tokens) {
		return new InferenceRequest(1, "m", tokens);
	}

	private static InferenceRequest answer(int promptTokens, int generatedTokens) {
		InferenceRequest request = prompt(promptTokens);
		for (int i = 0; i < generatedTokens; i++)
			request.emitToken(i * 0.001, 0.001);
		return request;
	}

	@Test
	void aPromptPaysItsTransmissionAndTheOneWayDelay() {
		AccessLink link = new AccessLink(bearer());
		double delivered = link.sendUplink(prompt(1000), 0);
		assertEquals(PROMPT_TRANSFER_SECONDS + LATENCY_SECONDS, delivered, 1e-12);
	}

	@Test
	void theTimeIsLeftOnTheRequestSoTheRowCanReportItAlone() {
		AccessLink link = new AccessLink(bearer());
		InferenceRequest request = prompt(1000);
		request.setArrivalTime(0);
		link.sendUplink(request, 0);
		assertEquals(PROMPT_TRANSFER_SECONDS + LATENCY_SECONDS, request.getUplinkSeconds(), 1e-12);
		assertEquals(0, request.getDownlinkSeconds(), 1e-12);
	}

	@Test
	void aSecondPromptWaitsForTheBearerTheFirstOneHolds() {
		AccessLink link = new AccessLink(bearer());
		double first = link.sendUplink(prompt(1000), 0);
		double second = link.sendUplink(prompt(1000), 0);
		assertEquals(PROMPT_TRANSFER_SECONDS + LATENCY_SECONDS, first, 1e-12);
		// Both were released at once, so the second transmits after the first: two services plus
		// the delay it could not avoid.
		assertEquals(2 * PROMPT_TRANSFER_SECONDS + LATENCY_SECONDS, second, 1e-12);
	}

	@Test
	void aPromptReleasedAfterTheBearerFellIdleDoesNotWait() {
		AccessLink link = new AccessLink(bearer());
		link.sendUplink(prompt(1000), 0);
		double late = link.sendUplink(prompt(1000), 1.0);
		assertEquals(1.0 + PROMPT_TRANSFER_SECONDS + LATENCY_SECONDS, late, 1e-12);
	}

	@Test
	void aLongPromptHoldsTheBearerForAsLongAsItIsLong() {
		AccessLink link = new AccessLink(bearer());
		double shortOne = link.sendUplink(prompt(1000), 0);
		double longOne = link.sendUplink(prompt(3000), 0);
		assertEquals(PROMPT_TRANSFER_SECONDS + LATENCY_SECONDS, shortOne, 1e-12);
		// Three times the bytes, and it starts only when the shorter prompt let go of the bearer.
		assertEquals(4 * PROMPT_TRANSFER_SECONDS + LATENCY_SECONDS, longOne, 1e-12);
	}

	@Test
	void theTwoDirectionsDoNotSlowEachOther() {
		AccessLink link = new AccessLink(bearer());
		InferenceRequest first = answer(1000, 1000);
		InferenceRequest second = answer(1000, 1000);
		double up = link.sendUplink(first, 0);
		double down = link.sendDownlink(second, 0);
		assertEquals(PROMPT_TRANSFER_SECONDS + LATENCY_SECONDS, up, 1e-12);
		// A thousand generated tokens are as many bytes as the thousand prompt tokens, and the
		// return direction is idle: the response leaves at once.
		assertEquals(PROMPT_TRANSFER_SECONDS + LATENCY_SECONDS, down, 1e-12);
	}

	@Test
	void theDownlinkQueuesBehindTheResponsesAheadOfIt() {
		AccessLink link = new AccessLink(bearer());
		link.sendDownlink(answer(10, 1000), 0);
		double second = link.sendDownlink(answer(10, 1000), 0);
		assertEquals(2 * PROMPT_TRANSFER_SECONDS + LATENCY_SECONDS, second, 1e-12);
	}

	@Test
	void anEmptyMessageStillPaysTheSpeedOfLight() {
		AccessLink link = new AccessLink(bearer());
		assertEquals(LATENCY_SECONDS, link.sendDownlink(answer(10, 0), 0), 1e-12);
	}

	@Test
	void thePayloadPerTokenIsWhatTheScenarioSaysItIs() {
		LinkProfile heavy = bearer().setBytesPerInputToken(40).setBytesPerOutputToken(1);
		AccessLink link = new AccessLink(heavy);
		// Ten times the default bytes per prompt token, so ten times the transmission.
		assertEquals(10 * PROMPT_TRANSFER_SECONDS + LATENCY_SECONDS, link.sendUplink(prompt(1000), 0), 1e-12);
		// A generated token costing one byte is a quarter of the prompt figure the default charges.
		assertEquals(0.25 * PROMPT_TRANSFER_SECONDS + LATENCY_SECONDS, link.sendDownlink(answer(10, 1000), 0), 1e-12);
	}

	@Test
	void megabitsAreDecimalAndComeBackUnchanged() {
		LinkProfile profile = bearer();
		assertEquals(100_000.0, profile.getBandwidth(), 1e-9);
		assertEquals(CAPACITY_MBPS, profile.getCapacityMbps(), 1e-12);
		assertEquals(10.0, profile.getLatencyMs(), 1e-12);
	}

	@Test
	void theDefaultPayloadIsFourBytesATokenInBothDirections() {
		LinkProfile profile = new LinkProfile().setId("bare").setCapacityMbps(1);
		assertEquals(4, profile.getBytesPerInputToken(), 1e-12);
		assertEquals(4, profile.getBytesPerOutputToken(), 1e-12);
		assertEquals(0, profile.getLatencyMs(), 1e-12);
		assertEquals("", profile.getNote());
	}

	@Test
	void aBearerThatStatesNothingIsASyntheticOne() {
		assertEquals(Provenance.SYNTHETIC, new LinkProfile().setId("x").setCapacityMbps(1).getProvenance());
		assertEquals(Provenance.SYNTHETIC,
				new LinkProfile().setId("x").setCapacityMbps(1).setProvenance(null).getProvenance());
		assertEquals(Provenance.MEASURED, new LinkProfile().setId("x").setCapacityMbps(1)
				.setProvenance(Provenance.MEASURED).getProvenance());
	}

	@Test
	void theSummaryNamesEveryFigureThatShapesTheDelay() {
		String text = bearer().toString();
		assertTrue(text.contains("0.8 Mbps"), text);
		assertTrue(text.contains("10.0 ms"), text);
		assertTrue(text.contains("4/4 bytes per input/output token"), text);
	}

	@Test
	void aQueueNeedsABearerToDrain() {
		assertThrows(IllegalArgumentException.class, () -> new AccessLink(null));
	}

	@Test
	void aBearerThatCarriesNothingIsRefusedBeforeTheRunStarts() {
		LinkProfile unnamed = new LinkProfile().setCapacityMbps(1);
		IllegalArgumentException name = assertThrows(IllegalArgumentException.class, unnamed::validate);
		assertTrue(name.getMessage().contains("link.<name>.capacityMbps"), name.getMessage());

		LinkProfile noCapacity = new LinkProfile().setId("x");
		assertThrows(IllegalArgumentException.class, noCapacity::validate);

		LinkProfile zeroCapacity = new LinkProfile().setId("x").setCapacityMbps(0);
		IllegalArgumentException capacity = assertThrows(IllegalArgumentException.class, zeroCapacity::validate);
		assertTrue(capacity.getMessage().contains("Link \"x\""), capacity.getMessage());

		LinkProfile fastButLate = new LinkProfile().setId("x").setCapacityMbps(1);
		fastButLate.setLatencyMs(-1);
		assertThrows(IllegalArgumentException.class, fastButLate::validate);
		fastButLate.setLatencyMs(0);
		fastButLate.setBytesPerInputToken(0);
		assertThrows(IllegalArgumentException.class, fastButLate::validate);
		fastButLate.setBytesPerInputToken(4);
		fastButLate.setBytesPerOutputToken(-2);
		assertThrows(IllegalArgumentException.class, fastButLate::validate);
	}

	@Test
	void aMessageOfNegativeSizeIsAProgrammingError() {
		AccessLink link = new AccessLink(bearer());
		IllegalArgumentException negative = assertThrows(IllegalArgumentException.class,
				() -> link.getProfile().transferSeconds(-1));
		assertTrue(negative.getMessage().contains("Link \"test\" cannot carry a message of"), negative.getMessage());
	}

	@Test
	void theQueueReportsTheBearerItDrains() {
		LinkProfile profile = bearer();
		AccessLink link = new AccessLink(profile);
		assertEquals(profile, link.getProfile());
		assertEquals("test", link.getId());
		assertTrue(link.toString().contains("test"), link.toString());
	}
}
