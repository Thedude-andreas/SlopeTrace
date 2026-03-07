package com.slopetrace.ui.session

import com.slopetrace.tracking.ActiveSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionViewModelHelpersTest {

    @Test
    fun resolveResumeCandidateRequiresRememberMeAndMatchingUser() {
        val stored = ActiveSessionState(
            sessionId = "session-123",
            userId = "user-123"
        )

        assertEquals(stored, resolveResumeCandidate(true, "user-123", stored))
        assertNull(resolveResumeCandidate(false, "user-123", stored))
        assertNull(resolveResumeCandidate(true, null, stored))
        assertNull(resolveResumeCandidate(true, "user-999", stored))
    }

    @Test
    fun mergeMemberIdsCombinesExistingTrailsPresenceAndCurrentUser() {
        val merged = mergeMemberIds(
            existing = listOf("user-b", "user-a"),
            trailUserIds = setOf("user-c"),
            presentUserIds = setOf("user-d", "user-a"),
            currentUserId = "user-me"
        )

        assertEquals(
            listOf("user-a", "user-b", "user-c", "user-d", "user-me"),
            merged
        )
    }
}
