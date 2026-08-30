// Which REPL runtime a kernel speaks. `python` is the oracle (`python -m rlm.repl`);
// `clojure` is the native SCI runtime delivered as prime-agent-runtime-clj/target/rlm-repl.
// The Clojure runtime implements execute/host_reply/interrupt/shutdown only — it has no
// snapshot, restore, or list_names, so state ops are skipped rather than emulated.
import { existsSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { getPackageDir } from "../../config.js";

export type KernelRuntimeKind = "python" | "clojure";

export const KERNEL_RUNTIME_ENV_VAR = "PRIME_AGENT_KERNEL_RUNTIME";
export const CLOJURE_RUNTIME_ENV_VAR = "PRIME_AGENT_CLOJURE_RUNTIME";
export const DEFAULT_KERNEL_RUNTIME: KernelRuntimeKind = "python";
export const REPL_PROTOCOL_VERSION = 2;

const CLOJURE_RUNTIME_PACKAGE = "prime-agent-runtime-clj";
const CLOJURE_RUNTIME_BINARY = "rlm-repl";
const CLOJURE_RUNTIME_BUILD_HINT = `./native-image/build.sh in ${CLOJURE_RUNTIME_PACKAGE}`;

function isRecord(value: unknown): value is Record<string, unknown> {
	return typeof value === "object" && value !== null && !Array.isArray(value);
}

export function parseKernelRuntimeKind(value: string | undefined): KernelRuntimeKind | undefined {
	const normalized = value?.trim().toLowerCase();
	if (!normalized) return undefined;
	if (normalized === "python" || normalized === "clojure") return normalized;
	throw new Error(`${KERNEL_RUNTIME_ENV_VAR} must be "python" or "clojure", got "${value}"`);
}

/** The runtime this process selects when a session does not inherit one. */
export function resolveKernelRuntimeKind(env: NodeJS.ProcessEnv = process.env): KernelRuntimeKind {
	return parseKernelRuntimeKind(env[KERNEL_RUNTIME_ENV_VAR]) ?? DEFAULT_KERNEL_RUNTIME;
}

/** Snapshot / restore / list_names. Only the Python oracle implements them. */
export function kernelRuntimeSupportsStateOps(kind: KernelRuntimeKind): boolean {
	return kind === "python";
}

function clojureRuntimeCandidates(): string[] {
	const moduleDir = path.dirname(fileURLToPath(import.meta.url));
	const relative = path.join(CLOJURE_RUNTIME_PACKAGE, "target", CLOJURE_RUNTIME_BINARY);
	// Mirrors runtimeCandidateDirs() in bootstrap.ts: src/core/kernel and dist/core/kernel
	// are both five levels below the repo root. The binary is gitignored build output, so
	// there is no packaged copy — a non-checkout install must set the env override.
	return [
		path.resolve(moduleDir, "..", "..", "..", "..", "..", relative),
		path.join(getPackageDir(), "..", "..", relative),
		path.resolve(process.cwd(), relative),
	];
}

/** Absolute path to the native Clojure runtime executable. Throws a teaching error when absent. */
export function resolveClojureRuntimeExecutable(env: NodeJS.ProcessEnv = process.env): string {
	const override = env[CLOJURE_RUNTIME_ENV_VAR]?.trim();
	if (override) {
		if (!existsSync(override)) {
			throw new Error(`${CLOJURE_RUNTIME_ENV_VAR} points at a missing executable: ${override}`);
		}
		return path.resolve(override);
	}
	const candidates = clojureRuntimeCandidates();
	for (const candidate of candidates) {
		if (existsSync(candidate)) return path.resolve(candidate);
	}
	throw new Error(
		`Clojure kernel runtime not found. Looked for: ${candidates.join(", ")}. ` +
			`Build it with ${CLOJURE_RUNTIME_BUILD_HINT}, or set ${CLOJURE_RUNTIME_ENV_VAR} to the ${CLOJURE_RUNTIME_BINARY} executable.`,
	);
}

/** Executable plus argv for one runtime kind. The Clojure binary serves the protocol directly. */
export function resolveKernelRuntimeCommand(
	kind: KernelRuntimeKind,
	pythonExecutable: string,
	env: NodeJS.ProcessEnv = process.env,
): { command: string; args: string[] } {
	if (kind === "clojure") {
		return { command: resolveClojureRuntimeExecutable(env), args: [] };
	}
	return { command: pythonExecutable, args: ["-m", "rlm.repl"] };
}

/**
 * Gate the first protocol frame. The protocol number alone does not say which
 * language answered, so a Clojure selection additionally requires the runtime
 * to announce `runtime.language = "clojure"`.
 */
export function assertKernelRuntimeReady(kind: KernelRuntimeKind, ready: Record<string, unknown>): void {
	const protocol = typeof ready.protocol === "number" ? ready.protocol : -1;
	if (protocol !== REPL_PROTOCOL_VERSION) {
		throw new Error(
			`Kernel runtime speaks protocol ${protocol}, expected ${REPL_PROTOCOL_VERSION}. ` +
				(kind === "clojure"
					? `Rebuild the runtime with ${CLOJURE_RUNTIME_BUILD_HINT} to match this prime-agent.`
					: "Update prime-agent-runtime in the kernel Python (PRIME_AGENT_KERNEL_PYTHON) to match this prime-agent."),
		);
	}
	if (kind !== "clojure") return;
	const runtime = isRecord(ready.runtime) ? ready.runtime : undefined;
	const language = typeof runtime?.language === "string" ? runtime.language : undefined;
	if (language !== "clojure") {
		throw new Error(
			`Kernel runtime announced language "${language ?? "(none)"}", expected "clojure". ` +
				`${KERNEL_RUNTIME_ENV_VAR}=clojure must be served by the native SCI runtime.`,
		);
	}
}
