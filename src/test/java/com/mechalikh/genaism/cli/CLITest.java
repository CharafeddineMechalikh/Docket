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
package com.mechalikh.genaism.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.mechalikh.genaism.support.ScenarioTestSupport;

/**
 * Drives {@link Main} the way a user does, through its returned exit code and the bytes it wrote to
 * the two streams, without calling {@code System.exit}.
 * <p>
 * Every branch of the argument parser and every artifact the run is supposed to leave behind is
 * checked against the real loader and the real engine, so a green test means the executable a
 * reviewer would run behaves as documented.
 *
 * @author Charafeddine Mechalikh
 */
class CLITest {

	/**
	 * Runs one set of arguments and captures both streams.
	 *
	 * @param args command line arguments
	 * @return the exit code, the console text and the diagnostics text
	 */
	private static Result run(String... args) {
		ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
		ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
		try (PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
				PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8)) {
			int exit = new Main(out, err).run(args);
			return new Result(exit, outBytes.toString(StandardCharsets.UTF_8),
					errBytes.toString(StandardCharsets.UTF_8));
		}
	}

	@Test
	void helpFlagPrintsUsageAndSucceeds() {
		Result result = run("--help");
		assertEquals(Main.EXIT_OK, result.exit);
		assertTrue(result.out.contains("usage: genaism"), result.out);
		assertTrue(result.out.contains("-r, --repetitions"), result.out);
		assertTrue(result.err.isEmpty(), result.err);
	}

	@Test
	void shortHelpFlagBehavesLikeLongHelp() {
		assertEquals(Main.EXIT_OK, run("-h").exit);
		assertTrue(run("-h").out.contains("usage: genaism"));
	}

	@Test
	void versionFlagPrintsTheNameAndSucceeds() {
		Result result = run("--version");
		assertEquals(Main.EXIT_OK, result.exit);
		assertTrue(result.out.startsWith("GenAIInferenceSim "), result.out);
	}

	@Test
	void noScenarioFileIsAUsageError() {
		Result result = run();
		assertEquals(Main.EXIT_USAGE, result.exit);
		assertTrue(result.err.contains("no scenario file given"), result.err);
		assertTrue(result.err.contains("usage: genaism"), "a usage error reprints the usage block");
	}

	@Test
	void unknownOptionIsAUsageError() {
		Result result = run("--frobnicate");
		assertEquals(Main.EXIT_USAGE, result.exit);
		assertTrue(result.err.contains("unknown option \"--frobnicate\""), result.err);
	}

	@Test
	void missingFileIsAUsageError() {
		Result result = run("no-such-scenario.properties");
		assertEquals(Main.EXIT_USAGE, result.exit);
		assertTrue(result.err.contains("cannot read the scenario file"), result.err);
	}

	@Test
	void scenarioOptionWithoutAValueIsAUsageError() {
		Result result = run("-c");
		assertEquals(Main.EXIT_USAGE, result.exit);
		assertTrue(result.err.contains("-c needs a file"), result.err);
	}

	@Test
	void twoScenarioFilesAreRejected() {
		Result result = run(ScenarioTestSupport.LIGHT, ScenarioTestSupport.LIGHT);
		assertEquals(Main.EXIT_USAGE, result.exit);
		assertTrue(result.err.contains("two scenario files given"), result.err);
	}

	@Test
	void nonNumericSeedIsAUsageError() {
		Result result = run("-s", "not-a-seed", ScenarioTestSupport.LIGHT);
		assertEquals(Main.EXIT_USAGE, result.exit);
		assertTrue(result.err.contains("expects an integer seed"), result.err);
	}

	@Test
	void zeroRepetitionsIsAUsageError() {
		Result result = run("-r", "0", ScenarioTestSupport.LIGHT);
		assertEquals(Main.EXIT_USAGE, result.exit);
		assertTrue(result.err.contains("at least one repetition"), result.err);
	}

	@Test
	void aScenarioThatDoesNotLoadIsAScenarioError() {
		// durationSeconds is read first as a required number; "not-a-number" makes the strict loader
		// throw, which the CLI must report as a scenario that does not load, not as a crash.
		Result result = run("-Dexperiment.durationSeconds=not-a-number", ScenarioTestSupport.LIGHT);
		assertEquals(Main.EXIT_SCENARIO, result.exit);
		assertTrue(result.err.contains("the scenario does not load"), result.err);
	}

	@Test
	void aTypoedKeyWarnsButStillRuns() {
		Result result = run("-q", "-r", "1", "-o", outDir("cli-unused").toString(), "-Dtotally.unread.key=1",
				ScenarioTestSupport.LIGHT);
		assertEquals(Main.EXIT_OK, result.exit);
		assertTrue(result.err.contains("nothing reads it"), result.err);
		assertTrue(result.err.contains("totally.unread.key"), result.err);
	}

	@Test
	void quietRunWritesEveryArtifactWithoutTheConsoleReport() {
		Path output = outDir("cli-quiet");
		Result result = run("-q", "-r", "1", "-o", output.toString(), ScenarioTestSupport.LIGHT);
		assertEquals(Main.EXIT_OK, result.exit);
		assertEquals("", result.out, "quiet suppresses the console report");
		Path root = output.resolve("single-gpu-poisson");
		assertTrue(Files.isRegularFile(root.resolve("summary.csv")), root.toString());
		assertTrue(Files.isRegularFile(root.resolve("slo.csv")), root.toString());
		assertTrue(Files.isRegularFile(root.resolve("config.txt")), root.toString());
		assertTrue(Files.isRegularFile(root.resolve("rep01").resolve("requests.csv")), root.toString());
		assertTrue(Files.isRegularFile(root.resolve("rep01").resolve("requests.jsonl")), root.toString());
		assertTrue(Files.isRegularFile(root.resolve("rep01").resolve("samples.csv")), root.toString());
	}

	@Test
	void everyJsonLineQuotesItsTextFields() throws IOException {
		Path output = outDir("cli-json");
		Result result = run("-q", "-r", "1", "-o", output.toString(), ScenarioTestSupport.LIGHT);
		assertEquals(Main.EXIT_OK, result.exit);
		List<String> lines = Files.readAllLines(
				output.resolve("single-gpu-poisson").resolve("rep01").resolve("requests.jsonl"));
		assertFalse(lines.isEmpty(), "the light scenario generates requests");
		for (String line : lines) {
			assertTrue(line.startsWith("{") && line.endsWith("}"), line);
			// A bare yes/no is not a JSON literal; the calibrated flag must be a quoted string.
			assertTrue(line.contains("\"cost_calibrated\": \""), line);
		}
	}

	@Test
	void consoleReportIsWrittenWhenNotQuiet() {
		Path output = outDir("cli-console");
		Result result = run("-r", "1", "-o", output.toString(), ScenarioTestSupport.LIGHT);
		assertEquals(Main.EXIT_OK, result.exit);
		assertTrue(result.out.contains("Artifacts written under"), result.out);
		assertTrue(result.out.contains("Service level objectives"), result.out);
		assertFalse(result.out.contains("usage: genaism"), "a good run does not print usage");
	}

	@Test
	void longOptionFormsAreAccepted() {
		Result result = run("--repetitions", "1", "--seed", "7", "--quiet", "--output", outDir("cli-long").toString(),
				"--scenario", ScenarioTestSupport.LIGHT);
		assertEquals(Main.EXIT_OK, result.exit);
		assertTrue(Files.isRegularFile(outDir("cli-long").resolve("single-gpu-poisson").resolve("summary.csv")));
	}

	private static Path outDir(String name) {
		return Paths.get("target").resolve(name);
	}

	/** Deletes a directory tree left over from a previous run so the artifact assertions are fresh. */
	static {
		for (String name : new String[] {"cli-unused", "cli-quiet", "cli-console", "cli-long", "cli-json"})
			deleteRecursively(Paths.get("target").resolve(name));
	}

	private static void deleteRecursively(Path root) {
		if (!Files.exists(root))
			return;
		try (Stream<Path> paths = Files.walk(root)) {
			paths.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(path -> {
				try {
					Files.deleteIfExists(path);
				} catch (IOException e) {
					throw new UncheckedIOException(e);
				}
			});
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** The outcome of one CLI invocation. */
	private static final class Result {
		private final int exit;
		private final String out;
		private final String err;

		private Result(int exit, String out, String err) {
			this.exit = exit;
			this.out = out;
			this.err = err;
		}
	}
}
