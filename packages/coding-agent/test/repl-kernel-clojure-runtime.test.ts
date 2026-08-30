import { chmodSync, existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { Agent } from "@earendil-works/pi-agent-core";
import { getModel } from "@earendil-works/pi-ai";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { AgentSession } from "../src/core/agent-session.js";
import { AuthStorage } from "../src/core/auth-storage.js";
import { ReplKernelManager } from "../src/core/kernel/index.js";
import { resolveClojureRuntimeExecutable, resolveKernelRuntimeKind } from "../src/core/kernel/runtime.js";
import { convertToLlm } from "../src/core/messages.js";
import { ModelRegistry } from "../src/core/model-registry.js";
import { buildRlmPrompt } from "../src/core/prompts/index.js";
import { type CreateRlmSubagentRuntimeOptions, createRlmRunHostHandler } from "../src/core/rlm-runtime.js";
import { SessionManager } from "../src/core/session-manager.js";
import { SettingsManager } from "../src/core/settings-manager.js";
import {
	buildClojureBootstrapCode,
	formatKernelErrorText,
	IpythonKernelProvisioner,
} from "../src/core/tools/ipython.js";
import { createTestResourceLoader } from "./utilities.js";

const CLOJURE_READY = JSON.stringify({
	event: "ready",
	protocol: 2,
	python: "clojure-native",
	runtime: { language: "clojure", engine: "sci", native: true },
});

const nativeRuntime = resolve(
	dirname(fileURLToPath(import.meta.url)),
	"../../../prime-agent-runtime-clj/target/rlm-repl",
);

let tempDir = "";
let logPath = "";

/** A stand-in for `target/rlm-repl`: it journals its argv and every request frame. */
function writeFakeRuntime(options: { ready?: string; bootstrapResult?: string } = {}): string {
	const scriptPath = join(tempDir, "rlm-repl.cjs");
	writeFileSync(
		scriptPath,
		[
			"#!/usr/bin/env node",
			'const fs = require("node:fs");',
			'const readline = require("node:readline");',
			`const log = ${JSON.stringify(logPath)};`,
			`const ready = ${JSON.stringify(options.ready ?? CLOJURE_READY)};`,
			`const bootstrapResult = ${JSON.stringify(options.bootstrapResult ?? "[true true true]")};`,
			'const note = (entry) => fs.appendFileSync(log, JSON.stringify(entry) + "\\n");',
			'const emit = (event) => process.stdout.write(JSON.stringify(event) + "\\n");',
			"note({ argv: process.argv.slice(2) });",
			'process.stdout.write(ready + "\\n");',
			"readline.createInterface({ input: process.stdin }).on('line', (line) => {",
			"  if (!line.trim()) return;",
			"  const req = JSON.parse(line);",
			"  note({ request: req });",
			'  if (req.type === "execute") emit({ event: "result", id: req.id, text: bootstrapResult });',
			'  if (req.id) emit({ event: "done", id: req.id, status: "ok" });',
			'  if (req.type === "shutdown") process.exit(0);',
			"});",
			"",
		].join("\n"),
	);
	chmodSync(scriptPath, 0o755);
	vi.stubEnv("PRIME_AGENT_CLOJURE_RUNTIME", scriptPath);
	return scriptPath;
}

function journal(): Array<Record<string, any>> {
	return readFileSync(logPath, "utf8")
		.split("\n")
		.filter((line) => line.trim())
		.map((line) => JSON.parse(line));
}

describe("formatKernelErrorText", () => {
	it("shows clojure evalue when traceback is frames only", () => {
		expect(
			formatKernelErrorText({
				ename: "Exception",
				evalue: "Unable to resolve classname: Throwable",
				traceback: ["sci.impl.analyzer$throw_error_with_location.invokeStatic"],
			}),
		).toContain("Unable to resolve classname: Throwable");
	});

	it("does not duplicate a python traceback that already contains evalue", () => {
		const traceback = "Traceback (most recent call last):\nValueError: nope";
		expect(formatKernelErrorText({ ename: "ValueError", evalue: "nope", traceback: [traceback] })).toBe(traceback);
	});
});

describe("clojure kernel runtime", () => {
	beforeEach(() => {
		tempDir = mkdtempSync(join(tmpdir(), "prime-agent-clj-runtime-"));
		logPath = join(tempDir, "runtime.log");
		writeFileSync(logPath, "");
	});

	afterEach(() => {
		vi.unstubAllEnvs();
		if (tempDir) {
			rmSync(tempDir, { recursive: true, force: true });
			tempDir = "";
		}
	});

	it("defaults to the python oracle and only switches on an explicit selection", () => {
		expect(resolveKernelRuntimeKind({})).toBe("python");
		expect(resolveKernelRuntimeKind({ PRIME_AGENT_KERNEL_RUNTIME: "clojure" })).toBe("clojure");
		expect(() => resolveKernelRuntimeKind({ PRIME_AGENT_KERNEL_RUNTIME: "lisp" })).toThrow(/python.*clojure/i);
		expect(() => resolveClojureRuntimeExecutable({ PRIME_AGENT_CLOJURE_RUNTIME: join(tempDir, "absent") })).toThrow(
			/missing executable/,
		);
	});

	it("spawns the native runtime with no arguments and boots on its clojure ready frame", async () => {
		writeFakeRuntime();
		const manager = new ReplKernelManager({ runtime: "clojure", cwd: tempDir });
		try {
			await manager.start();
			expect(manager.isRunning).toBe(true);
			expect(journal()[0]).toEqual({ argv: [] });
		} finally {
			await manager.dispose();
		}
	});
	it("rejects a runtime that does not announce the clojure language", async () => {
		writeFakeRuntime({ ready: JSON.stringify({ event: "ready", protocol: 2, python: "3.13.0" }) });
		const errorSpy = vi.spyOn(console, "error").mockImplementation(() => {});
		const manager = new ReplKernelManager({ runtime: "clojure", cwd: tempDir });
		try {
			await expect(manager.start()).rejects.toThrow(/announced language "\(none\)", expected "clojure"/);
		} finally {
			errorSpy.mockRestore();
			await manager.dispose();
		}
	});

	it("bootstraps clojure forms and never mints a state-op frame", async () => {
		writeFakeRuntime();
		const provisioner = new IpythonKernelProvisioner(tempDir, { runtime: "clojure", snapshotDir: tempDir });
		try {
			const manager = await provisioner.ensure();
			// list_names and snapshot are Python-oracle state ops; both must answer
			// null from the host without touching the wire.
			expect(await manager.listNamespaceNames()).toBeNull();
			expect(await manager.snapshotState()).toBeNull();
			expect(await manager.restoreState()).toBeNull();

			const requests = journal().flatMap((entry) => (entry.request ? [entry.request] : []));
			const executed = requests.filter((r) => r.type === "execute").map((r) => String(r.code));
			expect(executed).toEqual([buildClojureBootstrapCode()]);
			expect(executed[0]).toMatch(/\(fn\? rlm\)/);
			expect(executed[0]).not.toMatch(/\bimport\b|\basync def\b|\bawait\b|print\(/);
			expect(requests.map((r) => String(r.type))).toEqual(["execute"]);
		} finally {
			await provisioner.dispose();
		}
	});

	it("fails startup when the clojure workspace lacks the public bindings", async () => {
		writeFakeRuntime({ bootstrapResult: "[true false]" });
		const errorSpy = vi.spyOn(console, "error").mockImplementation(() => {});
		const provisioner = new IpythonKernelProvisioner(tempDir, { runtime: "clojure" });
		try {
			await expect(provisioner.ensure()).rejects.toThrow(/did not expose the rlm and host-request bindings/);
		} finally {
			errorSpy.mockRestore();
			await provisioner.dispose();
		}
	});

	it("builds a clojure orchestration prompt with no python syntax", () => {
		// Python skills are not loaded into the Clojure workspace, so a prompt that
		// still announced them would be teaching a capability the runtime lacks.
		const options = {
			cwd: tempDir,
			kernelRuntime: "clojure" as const,
			messagesPath: join(tempDir, "messages.jsonl"),
			installedSkills: ["agent_message", "agent_observe", "edit", "refine"],
			skillsDir: join(tempDir, "skills"),
			activeTools: ["ipython"],
			allowRecursion: true,
		};
		const prompt = buildRlmPrompt(options);
		expect(prompt).toMatch(/persistent Clojure REPL/);
		expect(prompt).toMatch(/\(rlm "sub-task"\)/);
		expect(prompt).toMatch(/:rlm-child-id/);
		expect(prompt).toMatch(/Do not write Python/);
		expect(prompt).not.toMatch(/await rlm\(|uv pip install|Pre-installed Python packages|rlm\.harness/);
		expect(prompt).not.toMatch(/pre-imported|bash\(command\)|top-level `await`|time\.sleep/i);
		expect(prompt).not.toMatch(/await agent_message|agent_observe/);
		expect(prompt).toMatch(/host-request \{:type t\}/);
		expect(prompt).toMatch(/rlm\.list_subagents/);
		expect(prompt).toMatch(/agent_message\.list_agents/);
		expect(prompt).toMatch(/read-text/);
		expect(buildRlmPrompt({ ...options, depth: 1, parentAgent: "root" })).not.toMatch(/await agent_message\.send/);
	});

	it("hands an rlm child the same runtime as its parent", async () => {
		const recorded: CreateRlmSubagentRuntimeOptions[] = [];
		const authStorage = AuthStorage.create(join(tempDir, "auth.json"));
		authStorage.setRuntimeApiKey("anthropic", "test-key");
		const session = new AgentSession({
			agent: new Agent({
				convertToLlm,
				getApiKey: () => "test-key",
				initialState: {
					model: getModel("anthropic", "claude-sonnet-4-5")!,
					systemPrompt: "",
					tools: [],
					thinkingLevel: "off",
				},
				streamFn: () => {
					throw new Error("unused");
				},
			}),
			sessionManager: SessionManager.create(tempDir, join(tempDir, "sessions")),
			settingsManager: SettingsManager.create(tempDir, tempDir),
			cwd: tempDir,
			modelRegistry: ModelRegistry.create(authStorage, join(tempDir, "models.json")),
			resourceLoader: createTestResourceLoader({}),
			kernelRuntime: "clojure",
			subagentRuntimeHost: {
				createRlmSubagentRuntime: async (options) => {
					recorded.push(options);
					throw new Error("child runtime not built in this test");
				},
				deleteRlmSubagentRuntime: async () => {},
			},
		});
		try {
			expect(session.kernelRuntime).toBe("clojure");
			await session.runRlmChild("inspect the workspace");
			await vi.waitFor(() => expect(recorded).toHaveLength(1));
			expect(recorded[0]?.kernelRuntime).toBe("clojure");
		} finally {
			await session.disposeAsync();
		}
	});

	it.skipIf(!existsSync(nativeRuntime))("drives a host request through the native runtime", async () => {
		const manager = new ReplKernelManager({
			runtime: "clojure",
			cwd: tempDir,
			hostHandlers: {
				"rlm.run": createRlmRunHostHandler(async (request) => ({
					rlm_child_id: "c1",
					name: String(request.kwargs.name ?? "child"),
					session_dir: tempDir,
					model: "anthropic/claude-sonnet-4-5",
				})),
			},
		});
		try {
			const defined = await manager.execute("(def x 41)");
			expect(defined.status).toBe("ok");
			const persisted = await manager.execute("(inc x)");
			expect(persisted.result).toBe("42");
			const spawned = await manager.execute('(rlm "child task" {:name "api-reviewer"})');
			expect(spawned.status).toBe("ok");
			expect(spawned.result).toMatch(/:rlm-child-id "c1"/);
			expect(spawned.result).toMatch(/:name "api-reviewer"/);
			expect(await manager.listNamespaceNames()).toBeNull();
		} finally {
			await manager.dispose();
		}
	});
});
