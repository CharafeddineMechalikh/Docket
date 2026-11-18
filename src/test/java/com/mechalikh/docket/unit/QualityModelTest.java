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
package com.mechalikh.docket.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.mechalikh.docket.domain.InferenceRequest;
import com.mechalikh.docket.domain.Provenance;
import com.mechalikh.docket.quality.CurveQualityModel;
import com.mechalikh.docket.quality.MeasuredQualityCurve;
import com.mechalikh.docket.quality.QualityModel;

/** Tests the configurable quality model and its measured-curve loader. */
class QualityModelTest {

	private static InferenceRequest request(double fidelity, int generated, int asked) {
		InferenceRequest request = new InferenceRequest(1, "m", asked).setOutputTokens(asked)
				.grantReasoningBudget((int) Math.round(asked * fidelity));
		try {
			Field field = InferenceRequest.class.getDeclaredField("generatedTokens");
			field.setAccessible(true);
			field.setInt(request, generated);
		} catch (ReflectiveOperationException e) {
			throw new AssertionError(e);
		}
		return request;
	}

	@Test
	void everyAnalyticCurveIsNormalisedAtFullEffort() {
		for (String name : CurveQualityModel.CURVES) {
			CurveQualityModel model = new CurveQualityModel(name, 0.5, 0, null, Provenance.SYNTHETIC);
			assertEquals(1.0, model.analytic(1.0), 1e-12, name);
			assertEquals(0.0, model.analytic(0.0), 1e-12, name);
		}
	}

	@Test
	void curvatureChangesTheScoreButNotTheOrdering() {
		double f = 0.5;
		CurveQualityModel linear = new CurveQualityModel("linear", 0.5, 0, null, Provenance.SYNTHETIC);
		CurveQualityModel sqrt = new CurveQualityModel("sqrt", 0.5, 0, null, Provenance.SYNTHETIC);
		CurveQualityModel cubic = new CurveQualityModel("cubic", 0.5, 0, null, Provenance.SYNTHETIC);
		assertTrue(cubic.analytic(f) < linear.analytic(f), "convex punishes shedding more");
		assertTrue(linear.analytic(f) < sqrt.analytic(f), "concave rewards shedding more");
		assertEquals(0.5, linear.score(request(f, 200, 400)), 1e-12);
	}

	@Test
	void thresholdCurveJumpsAtTheDeclaredPoint() {
		CurveQualityModel model = new CurveQualityModel("threshold", 0.5, 0, null, Provenance.LITERATURE);
		assertEquals(0.0, model.analytic(0.49), 1e-12);
		assertEquals(1.0, model.analytic(0.5), 1e-12);
	}

	@Test
	void tokenWeightBlendsTheGrantWithWhatWasDelivered(@TempDir Path dir) throws Exception {
		// A request granted half the work but truncated to a quarter of the asked output:
		// grant 0.5, delivered 0.25, so a half-and-half blend is 0.375.
		CurveQualityModel model = new CurveQualityModel("linear", 0.5, 0.5, null, Provenance.SYNTHETIC);
		assertEquals(0.375, model.score(request(0.5, 100, 400)), 1e-12);
		assertEquals(Provenance.SYNTHETIC, model.provenance());
		assertTrue(model.describe().contains("token weight 0.50"));
	}

	@Test
	void measuredCurveInterpolatesAndIsReportedAsMeasured(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("q.csv");
		Files.write(file, ("# measured on a real serving stack\nfidelity,quality\n"
				+ "0.1,0.0\n0.5,0.6\n1.0,1.0\n").getBytes(StandardCharsets.UTF_8));
		QualityModel model = QualityModel.from(com.mechalikh.docket.config.Settings.of(new java.util.HashMap<>() {{
			put("quality.calibration.file", file.toString());
			put("quality.calibration.provenance", "measured");
		}}));
		assertEquals(Provenance.MEASURED, model.provenance());
		assertEquals(0.3, model.score(request(0.3, 120, 400)), 1e-9);
		assertEquals(0.8, model.score(request(0.75, 300, 400)), 1e-9);
		assertTrue(model.describe().contains("measured"));
	}

	@Test
	void aCurveThatNeverReachesFullQualityIsRejected(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("bad.csv");
		Files.write(file, "0.1,0.0\n1.0,0.5\n".getBytes(StandardCharsets.UTF_8));
		assertThrows(IllegalArgumentException.class, () -> MeasuredQualityCurve.from(file));
	}

	@Test
	void unknownCurveNamesAreRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> new CurveQualityModel("magic", 0.5, 0, null, Provenance.SYNTHETIC));
	}
}
