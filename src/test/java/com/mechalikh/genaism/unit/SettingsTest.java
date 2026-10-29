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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.mechalikh.genaism.config.Settings;

/**
 * The strict settings reader that turns a typo into an error rather than a silently ignored line.
 *
 * @author Charafeddine Mechalikh
 */
class SettingsTest {

	private static Settings settings(String... pairs) {
		Map<String, String> map = new LinkedHashMap<>();
		for (int i = 0; i < pairs.length; i += 2)
			map.put(pairs[i], pairs[i + 1]);
		return Settings.of(map);
	}

	@Test
	void textIsRequiredOrFallsBackToADefault() {
		Settings s = settings("name", "  value  ", "blank", "   ");
		assertEquals("value", s.text("name"));
		assertThrows(IllegalArgumentException.class, () -> s.text("blank"));
		assertThrows(IllegalArgumentException.class, () -> s.text("missing"));
		assertEquals("d", s.text("missing", "d"));
		assertEquals("value", s.text("name", "ignored"));
		assertNull(s.raw("absent"));
	}

	@Test
	void numberParsesStripsThousandsAndRefusesJunk() {
		Settings s = settings("a", "12.5", "b", "1,234", "nan", "NaN", "txt", "nope", "inf", "Infinity");
		assertEquals(12.5, s.number("a"), 1e-12);
		assertEquals(1234.0, s.number("b"), 1e-12);
		assertEquals(Double.POSITIVE_INFINITY, s.number("inf"), 1e-12);
		assertThrows(IllegalArgumentException.class, () -> s.number("nan"));
		assertThrows(IllegalArgumentException.class, () -> s.number("txt"));
		assertEquals(7.0, s.number("missing", 7.0), 1e-12);
	}

	@Test
	void integerRefusesAFraction() {
		Settings s = settings("n", "12", "f", "1.5");
		assertEquals(12, s.integer("n"));
		assertThrows(IllegalArgumentException.class, () -> s.integer("f"));
		assertEquals(3, s.integer("missing", 3));
	}

	@Test
	void flagAcceptsTheWrittenForms() {
		Settings s = settings("t", "YES", "f", "0", "u", "maybe");
		assertTrue(s.flag("t", false));
		assertFalse(s.flag("f", true));
		assertTrue(s.flag("missing", true));
		assertThrows(IllegalArgumentException.class, () -> s.flag("u", false));
	}

	@Test
	void memoryUsesBinaryUnitsAndBandwidthIsDecimal() {
		Settings s = settings("k", "2", "m", "1", "g", "1", "t", "1", "neg", "-1", "b", "5");
		assertEquals(2 * 1024, s.bytes("k", "KiB"), 1e-9);
		assertEquals(1024 * 1024, s.bytes("m", "MiB"), 1e-9);
		assertEquals(1024.0 * 1024 * 1024, s.bytes("g", "GiB"), 1e-9);
		assertEquals(1024.0 * 1024 * 1024 * 1024, s.bytes("t", "TiB"), 1e-9);
		assertEquals(5, s.bytes("b", "bytes"), 1e-9);
		assertThrows(IllegalArgumentException.class, () -> s.bytes("neg", "KiB"));
		assertThrows(IllegalArgumentException.class, () -> s.bytes("b", "petabytes"));
		assertEquals(0.0, s.bytesOrZero("missing", "GiB"), 1e-9);
	}

	@Test
	void millisBecomeSeconds() {
		Settings s = settings("ms", "250");
		assertEquals(0.25, s.millisToSeconds("ms", 0), 1e-12);
		assertEquals(0.001, s.millisToSeconds("missing", 1.0), 1e-12);
	}

	@Test
	void listSplitsAndDropsBlanks() {
		Settings s = settings("csv", "a, b ,, c");
		List<String> parsed = s.list("csv");
		assertEquals(Arrays.asList("a", "b", "c"), parsed);
		assertTrue(s.list("missing").isEmpty());
	}

	@Test
	void distributionAttributesSkipTheNameAndTrimValues() {
		Settings s = settings("w.input.name", "triangular", "w.input.min", " 8 ", "w.input.max", "64");
		assertEquals("triangular", s.distributionName("w.input"));
		Map<String, String> attributes = s.distributionAttributes("w.input");
		assertEquals(2, attributes.size());
		assertEquals("8", attributes.get("min"));
		assertFalse(attributes.containsKey("name"));
		assertTrue(s.distributionAttributes("w.unset").isEmpty());
		assertThrows(IllegalArgumentException.class, () -> s.distributionName("w.none"));
	}

	@Test
	void aDerivedValueCannotReplaceAConfiguredOne() {
		Settings s = settings("present", "x");
		assertThrows(IllegalArgumentException.class, () -> s.remember("present", "y"));
		s.remember("derived", "z");
		assertEquals("z", s.text("derived"));
	}

	@Test
	void readingAKeyRetiresItFromTheUnusedReport() {
		Settings s = settings("read", "1", "typo", "2");
		s.text("read");
		List<String> unused = s.unusedKeys();
		assertEquals(Arrays.asList("typo"), unused);
		s.raw("typo");
		assertTrue(s.unusedKeys().isEmpty());
	}

	@Test
	void effectiveEchoesTheSourceOfEveryKey() {
		Settings s = settings("a", "1");
		Map<String, String> echo = s.effective();
		assertTrue(echo.get("a").contains("in memory"));
		assertEquals(1, s.size());
	}

	@Test
	void anOverrideReplacesTheFileValueAndMustBeWrittenAsKeyEqualsValue(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
			throws java.io.IOException {
		java.nio.file.Path file = dir.resolve("scenario.properties");
		java.nio.file.Files.write(file, "x=from file\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
		Settings loaded = Settings.fromFile(file, "test", Arrays.asList("x=from cli"));
		assertEquals("from cli", loaded.text("x"), "a command line override wins over the file value");
		assertTrue(loaded.effective().get("x").contains("command line"), loaded.effective().get("x"));
		// An override without the separator is rejected, but only once the file itself can be read.
		assertThrows(IllegalArgumentException.class,
				() -> Settings.fromFile(file, "test", Arrays.asList("novalue")));
	}
}
