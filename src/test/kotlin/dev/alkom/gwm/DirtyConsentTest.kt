package dev.alkom.gwm

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [CleanCommand.decideDirtyConsent] (Этап 10, finding 4) — the ONE named, pure
 * decision that governs whether a dirty worktree may be destroyed. Direct coverage here so the
 * safety policy is pinned independently of the command's I/O.
 */
class DirtyConsentTest {

    // The caller only invokes this once `--force` is in play; these cases are all "force is set".

    @Test // --yes --force: the two explicit flags together are consent, no prompt consulted.
    fun `assumeYes grants consent without asking`() {
        var asked = false
        val ok = CleanCommand.decideDirtyConsent(assumeYes = true, interactive = false) {
            asked = true; true
        }
        assertTrue(ok, "--yes --force must grant consent")
        assertFalse(asked, "--yes must NOT consult the interactive prompt")
    }

    @Test // interactive TTY, prompt says yes → consent.
    fun `interactive yes grants consent`() {
        val ok = CleanCommand.decideDirtyConsent(assumeYes = false, interactive = true) { true }
        assertTrue(ok)
    }

    @Test // interactive TTY, prompt says no → denied.
    fun `interactive no denies consent`() {
        val ok = CleanCommand.decideDirtyConsent(assumeYes = false, interactive = true) { false }
        assertFalse(ok)
    }

    @Test // interactive TTY, prompt aborted (null) → denied (bias to not deleting).
    fun `interactive aborted prompt denies consent`() {
        val ok = CleanCommand.decideDirtyConsent(assumeYes = false, interactive = true) { null }
        assertFalse(ok)
    }

    @Test // no --yes, no TTY → cannot consent to a dirty deletion (the finding-4 invariant).
    fun `force alone without yes or TTY denies consent and never asks`() {
        var asked = false
        val ok = CleanCommand.decideDirtyConsent(assumeYes = false, interactive = false) {
            asked = true; true
        }
        assertFalse(ok, "no --yes and no TTY must deny consent — force alone must never delete dirty")
        assertFalse(asked, "with no TTY the prompt must not even be attempted")
    }
}
