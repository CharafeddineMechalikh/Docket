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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.mechalikh.genaism.domain.GpuProfile;
import com.mechalikh.genaism.engine.ReplicaSpec;
import com.mechalikh.genaism.memory.KvCachePool;
import com.mechalikh.genaism.power.GpuPowerModel;

/**
 * Paged cache accounting and the piecewise-constant energy integral.
 *
 * @author Charafeddine Mechalikh
 */
class MemoryPowerTest {

	/** 32 bytes per token, 16 tokens per block, a budget that holds exactly four blocks. */
	private static KvCachePool pool() {
		return new KvCachePool(4 * 16 * 32, 32, 16);
	}

	@Test
	void blocksRoundUpToWholePages() {
		KvCachePool pool = pool();
		assertEquals(0, pool.blocksFor(0));
		assertEquals(1, pool.blocksFor(1));
		assertEquals(1, pool.blocksFor(16));
		assertEquals(2, pool.blocksFor(17));
		assertThrows(IllegalArgumentException.class, () -> pool.blocksFor(-1));
	}

	@Test
	void aBudgetHoldingNoBlockIsRefused() {
		assertThrows(IllegalStateException.class, () -> new KvCachePool(10, 32, 16));
	}

	@Test
	void theUsableContextIsTheSmallerOfTheWindowAndThePool() {
		ReplicaSpec spec = new ReplicaSpec().setModel(DomainTest.sevenB());
		assertEquals(4096, spec.maxContextTokens(20000), "a large pool leaves the window as the limit");
		assertEquals(3056, spec.maxContextTokens(3056), "a small pool caps the window");
	}

	@Test
	void thePoolNeedsPositiveFigures() {
		assertThrows(IllegalArgumentException.class, () -> new KvCachePool(0, 32, 16));
		assertThrows(IllegalArgumentException.class, () -> new KvCachePool(1000, 0, 16));
		assertThrows(IllegalArgumentException.class, () -> new KvCachePool(1000, 32, 0));
	}

	@Test
	void reserveHandsOutBlocksAndTracksThePeak() {
		KvCachePool pool = pool();
		assertEquals(4, pool.getTotalBlocks());
		assertTrue(pool.reserve(1, 17));
		assertEquals(2, pool.allocatedBlocks(1));
		assertEquals(32, pool.allocatedTokens(1));
		assertEquals(2, pool.usedBlocks());
		assertEquals(2, pool.peakUsedBlocks());
		assertTrue(pool.reserve(1, 3));
		assertEquals(1, pool.allocatedBlocks(1));
		assertEquals(1, pool.usedBlocks());
	}

	@Test
	void aRefusedReservationLeavesThePriorOneIntact() {
		KvCachePool pool = pool();
		assertTrue(pool.reserve(1, 64));
		long refusalsBefore = pool.getRefusedReservations();
		assertFalse(pool.reserve(2, 32));
		assertEquals(0, pool.allocatedBlocks(2));
		assertEquals(refusalsBefore + 1, pool.getRefusedReservations());
		assertTrue(pool.isFull());
	}

	@Test
	void aRequestLargerThanTheWholePoolIsAnErrorNotARefusal() {
		KvCachePool pool = pool();
		assertEquals(64, pool.capacityTokens());
		assertThrows(IllegalArgumentException.class, () -> pool.reserve(1, 100));
	}

	@Test
	void reserveOfZeroReleasesTheRequest() {
		KvCachePool pool = pool();
		assertTrue(pool.reserve(1, 16));
		assertTrue(pool.reserve(1, 0));
		assertEquals(0, pool.allocatedBlocks(1));
	}

	@Test
	void releasingAnUnknownRequestReportsFalse() {
		KvCachePool pool = pool();
		assertFalse(pool.release(42));
		assertTrue(pool.reserve(7, 16));
		assertTrue(pool.release(7));
		assertEquals(0, pool.usedBlocks());
		assertTrue(pool.reservedRequests().isEmpty());
	}

	@Test
	void occupancyAndFreeSpaceFollowTheReservations() {
		KvCachePool pool = pool();
		assertEquals(0.0, pool.occupancyRatio(), 1e-12);
		assertTrue(pool.reserve(1, 32));
		assertEquals(0.5, pool.occupancyRatio(), 1e-12);
		assertEquals(2, pool.freeBlocks());
		assertEquals(32, pool.freeTokens());
		assertEquals(32, pool.usedTokens());
		pool.checkConsistency();
	}

	private static GpuProfile device(double idle, double active) {
		return new GpuProfile().setId("d").setMemoryBytes(1e9).setMemoryBandwidthBytesPerSecond(1000)
				.addAchievedTeraFlops("fp16", 1000).setIdlePowerWatts(idle).setActivePowerWatts(active);
	}

	@Test
	void energyIsTheIntegralOfAConstantPower() {
		GpuPowerModel power = new GpuPowerModel(device(50, 300));
		assertEquals(600.0, power.recordActive(2.0), 1e-9);
		assertEquals(150.0, power.recordIdle(3.0), 1e-9);
		assertEquals(750.0, power.totalJoule(), 1e-9);
		assertEquals(600.0, power.activeEnergyJoule(), 1e-9);
		assertEquals(150.0, power.idleEnergyJoule(), 1e-9);
		assertEquals(2.0, power.getActiveSeconds(), 1e-9);
		assertEquals(3.0, power.getIdleSeconds(), 1e-9);
	}

	@Test
	void averagePowerAndUtilisationUseTheWallDuration() {
		GpuPowerModel power = new GpuPowerModel(device(50, 300));
		power.recordActive(1.0);
		power.recordIdle(3.0);
		assertEquals(112.5, power.averagePowerWatts(4.0), 1e-9);
		assertEquals(0.25, power.utilisation(4.0), 1e-9);
		assertEquals(0.0, power.averagePowerWatts(0.0), 1e-12);
		assertEquals(0.0, power.utilisation(0.0), 1e-12);
	}

	@Test
	void utilisationClampsToUnityWhenChargedSecondsOverflowTheDuration() {
		GpuPowerModel power = new GpuPowerModel(device(50, 300));
		power.recordActive(10.0);
		assertEquals(1.0, power.utilisation(4.0), 1e-12);
	}

	@Test
	void aDeviceWithoutPowerFiguresCannotBackAModel() {
		GpuProfile bad = device(300, 200);
		assertThrows(IllegalArgumentException.class, () -> new GpuPowerModel(bad));
		assertThrows(IllegalArgumentException.class, () -> new GpuPowerModel(null));
	}

	@Test
	void intervalsMustBeFiniteAndNonNegative() {
		GpuPowerModel power = new GpuPowerModel(device(50, 300));
		assertThrows(IllegalArgumentException.class, () -> power.recordActive(-1));
		assertThrows(IllegalArgumentException.class, () -> power.recordIdle(Double.NaN));
		assertThrows(IllegalArgumentException.class, () -> power.recordActive(Double.POSITIVE_INFINITY));
	}

	@Test
	void energyIsSharedAcrossTheSequencesOfAnInterval() {
		assertEquals(100.0, GpuPowerModel.shareOf(300, 1, 3), 1e-9);
		assertEquals(200.0, GpuPowerModel.shareOf(300, 2, 3), 1e-9);
		assertEquals(0.0, GpuPowerModel.shareOf(300, 1, 0), 1e-12);
		assertEquals(3600.0, 3600.0 * GpuPowerModel.wattHours(3600), 1e-9);
		assertEquals(1.0, GpuPowerModel.wattHours(3600), 1e-12);
	}

	@Test
	void theModelReportsThePowerLevelsItWasBuiltFrom() {
		GpuPowerModel power = new GpuPowerModel(device(50, 300));
		assertEquals(300.0, power.activePowerWatts(), 1e-12);
		assertEquals(50.0, power.idlePowerWatts(), 1e-12);
	}
}
