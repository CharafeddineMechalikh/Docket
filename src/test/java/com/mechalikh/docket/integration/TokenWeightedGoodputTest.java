/**
 *     Docket: a discrete-event simulator for generative AI inference serving.
 *
 *     This file is part of Docket.
 *
 *     Docket is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 **/ 
package com.mechalikh.docket.integration;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.mechalikh.docket.config.Scenario;
import com.mechalikh.docket.experiment.Experiment;
import com.mechalikh.docket.experiment.RunResult;
import com.mechalikh.docket.support.ScenarioTestSupport;

/** The delivered-token quality proxy is bounded by the granted-fidelity figure. */
class TokenWeightedGoodputTest {

	@Test
	void deliveredTokenShareNeverExceedsTheGrantedShare() {
		Scenario scenario = ScenarioTestSupport.load(ScenarioTestSupport.LOADED);
		RunResult run = new Experiment(scenario).runRepetition(1);
		assertTrue(run.tokenWeightedGoodput() <= run.qualityWeightedGoodput() + 1e-12,
				"truncation cannot increase retained work");
		assertTrue(run.tokenWeightedGoodput() >= 0.0);
		assertTrue(run.tokenWeightedGoodput() <= 1.0);
	}

	@Test
	void summaryCarriesBothQualityColumns() {
		RunResult run = new Experiment(ScenarioTestSupport.load(ScenarioTestSupport.LIGHT))
				.runRepetition(1);
		assertTrue(RunResult.summaryHeader().contains("quality_weighted_goodput"));
		assertTrue(RunResult.summaryHeader().contains("token_weighted_goodput"));
		assertTrue(run.summaryRow("test", "run").size() == RunResult.summaryHeader().size());
	}
}
