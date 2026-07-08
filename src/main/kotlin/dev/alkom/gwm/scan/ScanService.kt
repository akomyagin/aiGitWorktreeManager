package dev.alkom.gwm.scan

import dev.alkom.gwm.git.GitRunner
import dev.alkom.gwm.git.RealGitRunner
import dev.alkom.gwm.git.WorktreeService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * A repository that failed during aggregation, kept out of the main result so one
 * broken/unreadable repo never sinks the rest of the scan.
 *
 * @param repo   repository name
 * @param dir    its directory
 * @param reason short human-readable cause (git stderr or an exception message)
 */
data class RepoError(val repo: String, val dir: File, val reason: String)

/**
 * Outcome of a portfolio scan: every worktree found across all healthy repos, plus
 * the list of repos that errored. A partial result is a valid result — the caller
 * renders the table and, separately, warns about the failures.
 */
data class ScanResult(
    val worktrees: List<AggregatedWorktree>,
    val errors: List<RepoError>,
)

/**
 * Aggregates worktrees across every git repository under a portfolio root.
 *
 * Reuses the Фаза-1 [WorktreeService] per repo (no git-call logic is duplicated here)
 * and layers two Этап-4 concerns on top:
 *
 *  - **Parallelism.** Each repo's `list` + `withDirtyFlags` is several git subprocesses;
 *    across dozens of repos, doing them sequentially is visibly slow. We fan the repos
 *    out concurrently on [Dispatchers.IO] (blocking-process work) and join the results.
 *  - **Error isolation.** A single repo throwing (unreadable dir, git failure) must not
 *    abort the others. Each repo is aggregated inside its own `runCatching`; failures
 *    become [RepoError]s in the result instead of propagating, mirroring the
 *    crash-resistance already baked into [dev.alkom.gwm.git.GitCommand].
 *
 * The [git] runner is injectable so tests can drive aggregation with a fake git; the
 * default is the real one.
 */
class ScanService(private val git: GitRunner = RealGitRunner) {

    /** Blocking entry point for CLI callers; runs the parallel scan to completion. */
    fun scan(repos: List<File>): ScanResult = runBlocking { scanAsync(repos) }

    /** Suspending core: aggregate all [repos] concurrently, collecting partial results. */
    suspend fun scanAsync(repos: List<File>): ScanResult = coroutineScope {
        val outcomes = repos
            .map { repo -> async(Dispatchers.IO) { aggregateOne(repo) } }
            .awaitAll()

        val worktrees = outcomes.flatMap { it.first }
        val errors = outcomes.mapNotNull { it.second }
        ScanResult(worktrees, errors)
    }

    /**
     * Aggregates one repo. Returns its tagged worktrees paired with an optional error;
     * on any failure the worktree list is empty and the error is populated, so a broken
     * repo contributes nothing but a warning rather than blowing up the whole scan.
     */
    private fun aggregateOne(repo: File): Pair<List<AggregatedWorktree>, RepoError?> {
        val name = repo.name
        return runCatching {
            val service = WorktreeService(repo, git)
            val flagged = service.withDirtyFlags(service.list())
            flagged.map { AggregatedWorktree(name, it) }
        }.fold(
            onSuccess = { it to null },
            onFailure = { emptyList<AggregatedWorktree>() to RepoError(name, repo, it.message ?: it.toString()) },
        )
    }
}
