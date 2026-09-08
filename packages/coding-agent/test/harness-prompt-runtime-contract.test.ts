import { describe, expect, test } from "vitest";
import type { HarnessState } from "../src/core/refinement/index.js";
import { formatHarnessStateForPrompt } from "../src/core/refinement/index.js";
import { buildSystemPrompt } from "../src/core/system-prompt.js";

/**
 * The harness block is the one prompt section that used to stay runtime-blind:
 * `ToolName` is `"ipython"` on both arms, so the Clojure workspace read a Python
 * call contract byte-identical to the Python arm's. These tests bite that seam.
 * Reverting `kernelRuntime` in either `formatHarnessStateForPrompt` or its
 * `system-prompt.ts` call sites must turn them red.
 */

function entry(kind: "subagent" | "skill", id: string, title: string) {
	return {
		id,
		kind,
		title,
		content: `${title} content`,
		path: "policy",
		reference: {},
		arguments: {},
		metadata: {},
		source: "refine" as const,
		created_at: "2026-06-08T00:00:00.000Z",
		updated_at: "2026-06-08T00:00:00.000Z",
		version: 1,
	};
}

function harnessFixture(): HarnessState {
	return {
		schema: 1,
		entries: {
			prompt: {},
			memory: {},
			skill: { reviewer_skill: entry("skill", "reviewer_skill", "Reviewer skill") },
			subagent: { reviewer: entry("subagent", "reviewer", "Reviewer") },
		},
		refinements: [],
	} as HarnessState;
}

/** The harness block only, so an unrelated prompt section cannot satisfy an assertion. */
function harnessBlock(prompt: string): string {
	const start = prompt.indexOf("# Continual Harness State");
	expect(start).toBeGreaterThanOrEqual(0);
	const rest = prompt.slice(start);
	const next = rest.indexOf("\n\n# ", 1);
	return next === -1 ? rest : rest.slice(0, next);
}

const PYTHON_CALL_CONTRACT = "Call contract: read each installed Python skill's SKILL.md";

describe("harness block call contract follows the kernel runtime", () => {
	test("the clojure block teaches no Python call contract", () => {
		const block = formatHarnessStateForPrompt(harnessFixture(), {
			includeIpythonExamples: true,
			includeRefineExamples: true,
			kernelRuntime: "clojure",
		});

		expect(block).not.toContain("Python REPL");
		expect(block).not.toContain("await rlm(");
		expect(block).not.toContain("SKILL.md");
		expect(block).not.toContain("await ");
		expect(block).not.toContain("asyncio");
		expect(block).not.toContain("rlm.list_subagents()");
		expect(block).not.toContain("refine.run()");
	});

	test("the clojure block teaches the verbs this workspace actually has", () => {
		const block = formatHarnessStateForPrompt(harnessFixture(), {
			includeIpythonExamples: true,
			includeRefineExamples: true,
			kernelRuntime: "clojure",
		});

		expect(block).toContain("persistent Clojure/SCI REPL");
		expect(block).toContain('(def handle (rlm "sub-task"))');
		expect(block).toContain(":rlm-child-id");
		expect(block).toContain('(host-request {:type "agent_message.send"');
		expect(block).toContain(':receiver_role "parent"');
		expect(block).toContain("(rlm-children)");
		// unsupported is named, not silent
		expect(block).toContain("not callable from this workspace");
		expect(block).toContain("When to refine the continual harness:");
	});

	test("the subagent roster hint is spelled in the arm's own language", () => {
		const clojure = formatHarnessStateForPrompt(harnessFixture(), {
			includeIpythonExamples: true,
			kernelRuntime: "clojure",
		});
		const python = formatHarnessStateForPrompt(harnessFixture(), {
			includeIpythonExamples: true,
			kernelRuntime: "python",
		});

		expect(clojure).toContain('spawning with `(rlm "<task>")`');
		expect(clojure).not.toContain("await rlm('<task>')");
		expect(python).toContain("spawning with `await rlm('<task>')`");
	});

	test("the python block is unchanged, and an unset runtime still reads as python", () => {
		const state = harnessFixture();
		const options = { includeIpythonExamples: true, includeRefineExamples: true } as const;
		const python = formatHarnessStateForPrompt(state, { ...options, kernelRuntime: "python" });
		const unset = formatHarnessStateForPrompt(state, options);

		expect(python).toBe(unset);
		expect(python).toContain(PYTHON_CALL_CONTRACT);
		expect(python).toContain("handle = await rlm('sub-task')");
		expect(python).toContain("When to call `await refine.run()`");
	});
});

describe("buildSystemPrompt carries the runtime into the harness block", () => {
	const base = {
		selectedTools: ["ipython"],
		cwd: "/repo",
		messagesPath: "/repo/.pi/sessions/session.jsonl",
		contextFiles: [],
		skills: [],
		harnessState: harnessFixture(),
	};

	test("a clojure session's live harness block carries no Python call contract", () => {
		const block = harnessBlock(buildSystemPrompt({ ...base, kernelRuntime: "clojure" }));

		expect(block).not.toContain(PYTHON_CALL_CONTRACT);
		expect(block).not.toContain("await rlm(");
		expect(block).not.toContain("Python REPL");
		expect(block).toContain('(def handle (rlm "sub-task"))');
	});

	test("a python session's live harness block is untouched", () => {
		const block = harnessBlock(buildSystemPrompt({ ...base, kernelRuntime: "python" }));

		expect(block).toContain(PYTHON_CALL_CONTRACT);
		expect(block).toContain("handle = await rlm('sub-task')");
		expect(block).not.toContain("Clojure/SCI");
	});
});
