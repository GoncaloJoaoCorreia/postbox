package com.postbox.db

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class InvitationRepositoryTest {

    // Fixed-width format required by the TEXT comparison in InvitationRepository.redeem.
    private val future: String get() = isoUtc(Instant.now().plusSeconds(3600))
    private val past: String get() = isoUtc(Instant.now().minusSeconds(3600))

    @Test
    fun `invite with null created_by is accepted and can be redeemed exactly once`() {
        val db = freshTestDatabase("invite-single-use")
        val invitations = InvitationRepository(db)

        val created = invitations.create(token = "tok-1", createdBy = null, expiresAt = future)
        assertNull(created.createdBy)
        assertEquals(created, invitations.findByToken("tok-1"))

        val redeemed = invitations.redeem("tok-1")
        assertNotNull(redeemed)
        assertNotNull(redeemed.usedAt)
        assertNull(invitations.redeem("tok-1"), "a used token must not be redeemable again")
    }

    @Test
    fun `expired or unknown tokens are rejected on redemption`() {
        val db = freshTestDatabase("invite-expired")
        val invitations = InvitationRepository(db)

        invitations.create(token = "tok-expired", createdBy = null, expiresAt = past)
        assertNull(invitations.redeem("tok-expired"), "an expired token must be rejected")
        assertNull(invitations.redeem("tok-unknown"), "an unknown token must be rejected")
    }

    @Test
    fun `invite token is unique`() {
        val db = freshTestDatabase("invite-unique-token")
        val invitations = InvitationRepository(db)

        val created = invitations.create(token = "tok-dup", createdBy = null, expiresAt = future)
        assertFailsWith<Exception> {
            invitations.create(token = "tok-dup", createdBy = null, expiresAt = future)
        }
        assertEquals(created, invitations.findByToken("tok-dup"), "the rejected duplicate must not overwrite the original invite")
    }
}
