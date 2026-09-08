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

/**
 * Frozen call contracts. A blacklist of banned tokens does not bite the contract:
 * a paragraph could teach `handle = rlm('sub-task')` and pass every "not.toContain".
 * The call-contract line is pinned whole, per arm, so any Python shape added to it
 * is red whatever it is spelled with. (Found by a cross-review, 2026-09-08.)
 */
const CLOJURE_CALL_CONTRACT_LINE =
	'Call contract: this session\'s workspace is a persistent Clojure/SCI REPL; evaluate forms here. Continual harness skill entries carry a Python `reference` and `arguments` contract and are not callable from this workspace; treat them as routing/context hints and say so plainly rather than pretending to run them. Spawn a continual harness subagent spec by composing a concise task prompt and evaluating `(def handle (rlm "sub-task"))`; admission returns immediately with `:rlm-child-id`, `:name`, `:session-dir`, and `:model`, never the child\'s answer. Results arrive only through explicit agent messages or files; children reply with `(host-request {:type "agent_message.send" :message "answer" :receiver_role "parent"})`. Use `(rlm-children)` to recover direct child handles and the same host verb with `:receiver_role "child"` plus `:receiver_name` for follow-ups. Do not invent wrappers such as `call-skill` or `run-subagent`, and do not assume Python names exist here.';

const PYTHON_CALL_CONTRACT_LINE =
	"Call contract: read each installed Python skill's SKILL.md and call its documented module function in the Python REPL; do not assume a `.run` entrypoint. Use `<skill_import> ...` in shell when a CLI exists. Continual harness skill entries are Python REPL skills with an explicit Python `reference` and `arguments` contract. Spawn a continual harness subagent spec by composing a concise task prompt and calling `handle = await rlm('sub-task')`; admission returns immediately with `rlm_child_id`, `name`, `session_dir`, and `model`, never the child's answer. Results arrive only through explicit `agent_message` replies or files; children reply with `await agent_message.send(message, receiver_role='parent')`. Use `await rlm.list_subagents()` to recover direct child handles and `await agent_message.send(..., receiver_role='child', receiver_name=handle.name)` for follow-ups. Do not invent wrappers such as `call_skill(...)`, `run_subagent(...)`, or named subagent registries.";

/** Every `Call contract:` line in a block, so an added second one is visible too. */
function callContractLines(block: string): string[] {
	return block.split("\n").filter((line) => line.startsWith("Call contract:"));
}

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

	test("each arm's call contract is pinned whole, not by a list of banned words", () => {
		const clojure = formatHarnessStateForPrompt(harnessFixture(), {
			includeIpythonExamples: true,
			includeRefineExamples: true,
			kernelRuntime: "clojure",
		});
		const python = formatHarnessStateForPrompt(harnessFixture(), {
			includeIpythonExamples: true,
			includeRefineExamples: true,
			kernelRuntime: "python",
		});

		expect(callContractLines(clojure)).toEqual([CLOJURE_CALL_CONTRACT_LINE]);
		expect(callContractLines(python)).toEqual([PYTHON_CALL_CONTRACT_LINE]);
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

	test("the live clojure block's call contract is the pinned one, and there is only one", () => {
		const block = harnessBlock(buildSystemPrompt({ ...base, kernelRuntime: "clojure" }));

		expect(callContractLines(block)).toEqual([CLOJURE_CALL_CONTRACT_LINE]);
	});

	test("a python session's live harness block is untouched", () => {
		const block = harnessBlock(buildSystemPrompt({ ...base, kernelRuntime: "python" }));

		expect(block).toContain(PYTHON_CALL_CONTRACT);
		expect(block).toContain("handle = await rlm('sub-task')");
		expect(block).not.toContain("Clojure/SCI");
	});
});
