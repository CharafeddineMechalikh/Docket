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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.mechalikh.docket.domain.GpuProfile;
import com.mechalikh.docket.domain.InferenceRequest;
import com.mechalikh.docket.domain.ModelProfile;
import com.mechalikh.docket.domain.Provenance;
import com.mechalikh.docket.domain.RequestState;

/**
 * The lifecycle, latency arithmetic and profile geometry that every other layer trusts.
 *
 * @author Charafeddine Mechalikh
 */
class DomainTest {

	private static InferenceRequest arrived(long id, int prompt, int output, double arrival) {
		InferenceRequest request = new InferenceRequest(id, "test-model", prompt).setOutputTokens(output);
		request.setArrivalTime(arrival);
		request.transitionTo(RequestState.ARRIVED);
		request.transitionTo(RequestState.ADMITTED);
		request.transitionTo(RequestState.QUEUED);
		request.transitionTo(RequestState.PREFILLING);
		return request;
	}

	@Test
	void aRequestNeedsAPromptAndAModel() {
		assertThrows(IllegalArgumentException.class, () -> new InferenceRequest(1, "m", 0));
		assertThrows(IllegalArgumentException.class, () -> new InferenceRequest(1, null, 8));
		assertThrows(IllegalArgumentException.class, () -> new InferenceRequest(1, "m", 4).setOutputTokens(0));
	}

	@Test
	void impossibleTransitionsAreRefused() {
		InferenceRequest request = new InferenceRequest(2, "m", 8);
		assertEquals(RequestState.CREATED, request.getState());
		assertThrows(IllegalStateException.class, () -> request.transitionTo(RequestState.DECODING));
		request.transitionTo(RequestState.ARRIVED);
		assertThrows(IllegalStateException.class, () -> request.transitionTo(RequestState.COMPLETED));
		assertTrue(request.getState().allowedNextStates().contains(RequestState.ADMITTED));
	}

	@Test
	void onlyDecodeMayRepeatItself() {
		assertTrue(RequestState.DECODING.canTransitionTo(RequestState.DECODING));
		assertFalse(RequestState.PREFILLING.canTransitionTo(RequestState.PREFILLING));
		assertFalse(RequestState.QUEUED.canTransitionTo(RequestState.DECODING));
	}

	@Test
	void aQueuedRequestMayBeRefusedBeforeItStarts() {
		// A prompt that can never fit the cache is removed from the queue and rejected, so the
		// queue must be able to lead straight to rejection.
		assertTrue(RequestState.QUEUED.canTransitionTo(RequestState.REJECTED));
		assertTrue(RequestState.QUEUED.allowedNextStates().contains(RequestState.REJECTED));
	}

	@Test
	void terminalStatesAreFour() {
		assertTrue(RequestState.COMPLETED.isTerminal());
		assertTrue(RequestState.REJECTED.isTerminal());
		assertTrue(RequestState.TIMED_OUT.isTerminal());
		assertTrue(RequestState.CANCELLED.isTerminal());
		assertFalse(RequestState.DECODING.isTerminal());
		assertFalse(RequestState.COMPLETED.allowedNextStates().iterator().hasNext());
	}

	@Test
	void finishingRefusesReFinishing() {
		InferenceRequest request = arrived(3, 8, 4, 0.0);
		request.finish(RequestState.COMPLETED, "", 1.0);
		assertTrue(request.isCompleted());
		assertThrows(IllegalStateException.class, () -> request.finish(RequestState.CANCELLED, "", 2.0));
		assertThrows(IllegalStateException.class, () -> request.finish(RequestState.DECODING, "", 2.0));
	}

	@Test
	void ttftMeasuresFromArrivalAndIsAbsentBeforeTheFirstToken() {
		InferenceRequest request = arrived(4, 8, 4, 2.5);
		assertTrue(Double.isNaN(request.ttftSeconds()));
		request.emitToken(3.1, 0.0);
		assertEquals(0.6, request.ttftSeconds(), 1e-12);
	}

	@Test
	void tpotAndMeanItlSplitTheIntervalAfterTheFirstToken() {
		InferenceRequest request = arrived(5, 8, 3, 0.0);
		request.emitToken(1.0, 0.0);
		request.emitToken(3.0, 1.0);
		request.emitToken(5.0, 1.5);
		assertEquals(2.0, request.tpotSeconds(), 1e-12);
		assertEquals(1.25, request.meanInterTokenLatency(), 1e-12);
		assertEquals(2, request.getInterTokenLatencyCount());
		assertEquals(1.5, request.getMaxInterTokenLatency(), 1e-12);
	}

	@Test
	void aSingleTokenRequestHasNoPace() {
		InferenceRequest request = arrived(6, 8, 1, 0.0);
		request.emitToken(2.0, 0.0);
		assertEquals(0.0, request.tpotSeconds(), 1e-12);
		assertEquals(0.0, request.meanInterTokenLatency(), 1e-12);
	}

	@Test
	void endToEndNeedsCompletion() {
		InferenceRequest request = arrived(7, 8, 2, 1.0);
		request.emitToken(2.0, 0.0);
		request.emitToken(4.0, 2.0);
		assertTrue(Double.isNaN(request.endToEndSeconds()));
		request.finish(RequestState.COMPLETED, "", 4.0);
		assertEquals(3.0, request.endToEndSeconds(), 1e-12);
	}

	@Test
	void queueingIgnoresNonPositiveSpansAndAccumulates() {
		InferenceRequest request = arrived(8, 8, 2, 0.0);
		request.addQueueingTime(0.0);
		request.addQueueingTime(-1.0);
		assertEquals(0.0, request.getQueueingSeconds(), 1e-12);
		request.addQueueingTime(0.25);
		request.addQueueingTime(0.75);
		assertEquals(1.0, request.getQueueingSeconds(), 1e-12);
	}

	@Test
	void predictedAndOracleLengthsAreBothVisible() {
		InferenceRequest request = new InferenceRequest(9, "m", 100).setOutputTokens(50).setPredictedOutputTokens(10)
				.setMaxOutputTokens(200);
		assertEquals(150, request.finalContextTokens(false));
		assertEquals(110, request.finalContextTokens(true));
		assertEquals(150, request.expectedTotalTokens(false));
		assertEquals(110, request.expectedTotalTokens(true));
	}

	@Test
	void theMaximumOutputCapsTheOracleLength() {
		InferenceRequest request = new InferenceRequest(10, "m", 100).setOutputTokens(90).setMaxOutputTokens(40);
		assertEquals(140, request.finalContextTokens(false));
	}

	@Test
	void keyValueBytesPerTokenFollowsTheConventionalEstimate() {
		ModelProfile model = sevenB();
		double expected = 2.0 * 32 * 32 * 128 * 2.0;
		assertEquals(expected, model.kvBytesPerToken(), 1e-9);
		assertEquals(expected * 512, model.kvCacheBytes(512), 1e-6);
		assertEquals(0.0, model.kvCacheBytes(-5), 1e-9);
	}

	@Test
	void weightBytesScaleWithPrecision() {
		ModelProfile model = sevenB();
		assertEquals(7.0e9 * 2.0, model.weightBytes(), 1e-6);
		model.setBytesPerParameter(1.0);
		assertEquals(7.0e9, model.weightBytes(), 1e-6);
	}

	@Test
	void defaultsFillHeadsAndDeriveTheHeadWidth() {
		ModelProfile model = new ModelProfile().setId("m").setParameterCount(1000).setLayers(2).setHiddenDimension(64)
				.setAttentionHeads(8).setMaxContextTokens(256);
		model.applyDefaults();
		assertEquals(8, model.getKvHeads());
		assertEquals(8, model.getHeadDimension());
		model.validate();
	}

	@Test
	void validateRejectsInconsistentGeometry() {
		ModelProfile model = sevenB();
		model.setHeadDimension(100);
		assertThrows(IllegalArgumentException.class, model::validate);
	}

	@Test
	void validateRejectsKvHeadsAboveAttentionHeads() {
		ModelProfile model = sevenB();
		model.setKvHeads(64);
		assertThrows(IllegalArgumentException.class, model::validate);
	}

	@Test
	void validateRejectsNonDenseArchitecture() {
		ModelProfile model = sevenB();
		model.setArchitecture("mixture-of-experts");
		assertThrows(IllegalArgumentException.class, model::validate);
	}

	@Test
	void theDeviceConvertsTeraFlopsAndRefusesUnknownPrecision() {
		GpuProfile device = syntheticDevice();
		assertEquals(700e12, device.flopsPerSecond("FP16"), 1e-6);
		assertThrows(IllegalArgumentException.class, () -> device.flopsPerSecond("int4"));
		assertTrue(device.declaredPrecisions().contains("fp16"));
	}

	@Test
	void usableMemoryReservesTheRuntimeSlice() {
		GpuProfile device = syntheticDevice().setMemoryBytes(80e9).setUnusableMemoryFraction(0.1);
		assertEquals(72e9, device.usableMemoryBytes(), 1e-6);
	}

	@Test
	void aDeviceRefusesActivePowerBelowIdlePower() {
		GpuProfile device = syntheticDevice().setIdlePowerWatts(300).setActivePowerWatts(200);
		assertThrows(IllegalArgumentException.class, device::validate);
	}

	@Test
	void copyKeepsFiguresButNotIdentity() {
		GpuProfile device = syntheticDevice().setId("original");
		GpuProfile clone = device.copy();
		clone.setId("replica-2");
		assertEquals("original", device.getId());
		assertEquals("replica-2", clone.getId());
		assertEquals(device.flopsPerSecond("fp16"), clone.flopsPerSecond("fp16"), 1e-6);
	}

	@Test
	void provenanceParsesCaseInsensitiveAndRejectsUnknown() {
		assertEquals(Provenance.LITERATURE, Provenance.parse("Literature"));
		assertThrows(IllegalArgumentException.class, () -> Provenance.parse("guessed"));
	}

	static ModelProfile sevenB() {
		return new ModelProfile().setId("big-7b").setParameterCount(7_000_000_000L).setLayers(32).setHiddenDimension(4096)
				.setAttentionHeads(32).setKvHeads(32).setHeadDimension(128).setFeedForwardDimension(11008)
				.setMaxContextTokens(4096).setPrecision("fp16").setBytesPerParameter(2).setKvBytesPerScalar(2);
	}

	static GpuProfile syntheticDevice() {
		return new GpuProfile().setId("gpu-synthetic").setMemoryBytes(80e9).setMemoryBandwidthBytesPerSecond(700e9)
				.addAchievedTeraFlops("fp16", 700).setIdlePowerWatts(60).setActivePowerWatts(300);
	}
}
