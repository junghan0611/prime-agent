import { execFileSync } from "node:child_process";
import { existsSync, mkdirSync, mkdtempSync, readdirSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { getAgentDir } from "../src/config.js";
import {
	appendGlobalRefinement,
	ENV_GLOBAL_HARNESS_STATE_DIR,
	getGlobalHarnessStateDir,
	type HarnessState,
	type RefinementResult,
	saveHarnessState,
} from "../src/core/refinement/refinement.js";
import { createTestSession } from "./utilities.js";

// Seam ② of BASELINE.md 「Baseline isolation」: a BENCH/BASELINE run must be able
// to allocate its own empty global continual-harness store, and the launch receipt
// must name the arm and both store paths. The whole agent dir cannot move for that
// (credentials and settings live there), so the store gets its own per-run override.

const REPO_ROOT = join(import.meta.dirname, "..", "..", "..");
const RUN_SH = join(REPO_ROOT, "run.sh");
const NATIVE_RUNTIME = join(REPO_ROOT, "prime-agent-runtime-clj", "target", "rlm-repl");

function harnessStateWith(id: string, content: string): HarnessState {
	const now = new Date().toISOString();
	return {
		schema: 1,
		entries: {
			prompt: {
				[id]: {
					id,
					kind: "prompt",
					title: id,
					content,
					path: `${id}.md`,
					scope: "global",
					reference: {},
					arguments: {},
					metadata: {},
					source: "test",
					created_at: now,
					updated_at: now,
					version: 1,
				},
			},
			memory: {},
			skill: {},
			subagent: {},
		},
		refinements: [],
	};
}

function refinementWith(id: string, harnessStatePath: string): RefinementResult {
	return {
		id,
		summary: `summary-${id}`,
		rationale: "",
		expectedOutcome: "",
		appliedEdits: [],
		harnessStatePath,
		scope: "global",
	};
}

describe("global harness store — per-run override", () => {
	let tempDir: string;
	const savedEnv = process.env[ENV_GLOBAL_HARNESS_STATE_DIR];

	beforeEach(() => {
		tempDir = mkdtempSync(join(tmpdir(), "harness-store-"));
	});

	afterEach(() => {
		if (savedEnv === undefined) delete process.env[ENV_GLOBAL_HARNESS_STATE_DIR];
		else process.env[ENV_GLOBAL_HARNESS_STATE_DIR] = savedEnv;
		rmSync(tempDir, { recursive: true, force: true });
	});

	it("resolves under the agent dir when no override is set", () => {
		delete process.env[ENV_GLOBAL_HARNESS_STATE_DIR];
		expect(getGlobalHarnessStateDir()).toBe(join(getAgentDir(), "harness"));
	});

	it("uses the override verbatim, so the launcher's allocated store is the store", () => {
		const store = join(tempDir, "global-harness");
		process.env[ENV_GLOBAL_HARNESS_STATE_DIR] = store;
		expect(getGlobalHarnessStateDir()).toBe(store);
	});

	it("lets an explicit agentDir win, so fixtures are not disturbed by a stray env var", () => {
		process.env[ENV_GLOBAL_HARNESS_STATE_DIR] = join(tempDir, "global-harness");
		const fixture = join(tempDir, "fixture-agent-dir");
		expect(getGlobalHarnessStateDir(fixture)).toBe(join(fixture, "harness"));
	});
});

describe("global harness store — one path for prompt loader, refinement, and kernel env", () => {
	let tempDir: string;
	let store: string;
	let cleanup: (() => void) | undefined;
	const savedEnv = process.env[ENV_GLOBAL_HARNESS_STATE_DIR];

	beforeEach(() => {
		tempDir = mkdtempSync(join(tmpdir(), "harness-share-"));
		store = join(tempDir, "global-harness");
		mkdirSync(store, { recursive: true });
		process.env[ENV_GLOBAL_HARNESS_STATE_DIR] = store;
	});

	afterEach(() => {
		cleanup?.();
		cleanup = undefined;
		if (savedEnv === undefined) delete process.env[ENV_GLOBAL_HARNESS_STATE_DIR];
		else process.env[ENV_GLOBAL_HARNESS_STATE_DIR] = savedEnv;
		rmSync(tempDir, { recursive: true, force: true });
	});

	it("serves all three consumers from the one store the run allocated", () => {
		const statePath = saveHarnessState(store, harnessStateWith("bench-note", "note written by this run"));
		appendGlobalRefinement(store, refinementWith("bench-refinement", statePath));

		const ctx = createTestSession();
		cleanup = ctx.cleanup;
		const session = ctx.session as unknown as {
			_loadMergedHarnessState(): HarnessState;
			_loadRefinementHistory(): RefinementResult[];
			_rlmKernelEnv(): Record<string, string>;
		};

		// prompt loader
		const merged = session._loadMergedHarnessState();
		expect(merged.entries.prompt["bench-note"]?.content).toBe("note written by this run");
		// refinement history
		expect(session._loadRefinementHistory().map((r) => r.id)).toContain("bench-refinement");
		// kernel env — the workspace writes where the host reads
		expect(session._rlmKernelEnv().RLM_GLOBAL_HARNESS_STATE_DIR).toBe(store);
		expect(session._rlmKernelEnv().RLM_GLOBAL_HARNESS_STATE_DIR).toBe(getGlobalHarnessStateDir());
	});

	it("does not read a store the run did not allocate", () => {
		const other = join(tempDir, "some-other-run");
		saveHarnessState(other, harnessStateWith("foreign-note", "belongs to another run"));

		const ctx = createTestSession();
		cleanup = ctx.cleanup;
		const session = ctx.session as unknown as { _loadMergedHarnessState(): HarnessState };
		expect(session._loadMergedHarnessState().entries.prompt["foreign-note"]).toBeUndefined();
	});
});

describe("launcher receipt — arm, global store, local store root", () => {
	let tempDir: string;

	beforeEach(() => {
		tempDir = mkdtempSync(join(tmpdir(), "launch-receipt-"));
	});

	afterEach(() => {
		rmSync(tempDir, { recursive: true, force: true });
	});

	function receiptPaths(stderr: string): { global?: string; local?: string } {
		const globalLine = stderr.match(/global harness store \((fresh|inherited)\): (.+)/);
		const localLine = stderr.match(/local harness store root \((fresh|inherited)\): (.+)/);
		return { global: globalLine?.[2]?.trim(), local: localLine?.[2]?.trim() };
	}

	// execFileSync gives stdout; the launcher prints its receipt to stderr, so run it
	// through a shell that folds stderr into stdout.
	function dryLaunchStderr(arm: string, env: Record<string, string> = {}): string {
		return execFileSync("bash", ["-c", `"$0" "$1" 2>&1`, RUN_SH, arm], {
			encoding: "utf8",
			env: {
				...process.env,
				PRIME_AGENT_RUN_SH_DRY: "1",
				PRIME_AGENT_GLOBAL_HARNESS_STATE_DIR: "",
				PRIME_AGENT_SESSION_DIR: "",
				...env,
			},
		});
	}

	it("names the arm and both store paths, and both start empty", () => {
		const out = dryLaunchStderr("py");
		expect(out).toContain("python arm");
		const { global: globalStore, local: localRoot } = receiptPaths(out);
		expect(globalStore).toBeTruthy();
		expect(localRoot).toBeTruthy();
		expect(existsSync(globalStore!)).toBe(true);
		expect(existsSync(localRoot!)).toBe(true);
		expect(readdirSync(globalStore!)).toEqual([]);
		expect(readdirSync(localRoot!)).toEqual([]);
	});

	it("gives two launches of the same arm different stores", () => {
		const first = receiptPaths(dryLaunchStderr("py"));
		const second = receiptPaths(dryLaunchStderr("py"));
		expect(first.global).not.toBe(second.global);
		expect(first.local).not.toBe(second.local);
	});

	it("gives the two arms different stores", () => {
		const python = receiptPaths(dryLaunchStderr("py"));
		const clojure = existsSync(NATIVE_RUNTIME) ? receiptPaths(dryLaunchStderr("clj")) : undefined;
		if (!clojure) return; // native binary is a local build artifact; CI has none
		expect(python.global).not.toBe(clojure.global);
		expect(python.local).not.toBe(clojure.local);
	});

	it("keeps a pinned store across the turns of one session, and says it inherited it", () => {
		const pinnedStore = join(tempDir, "pinned-global");
		const pinnedSessions = join(tempDir, "pinned-sessions");
		const out = dryLaunchStderr("py", {
			PRIME_AGENT_GLOBAL_HARNESS_STATE_DIR: pinnedStore,
			PRIME_AGENT_SESSION_DIR: pinnedSessions,
		});
		expect(out).toContain(`global harness store (inherited): ${pinnedStore}`);
		expect(out).toContain(`local harness store root (inherited): ${join(tempDir, "session-artifacts")}`);
		expect(out).toContain(`session dir (inherited): ${pinnedSessions}`);
	});
});
