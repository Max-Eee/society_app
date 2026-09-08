package com.example.Chennai_Coop.data.models

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemberTest {
    @Test
    fun `A-C Closed station is treated as closed`() {
        val member = Member(memberNumber = "19773", station = "A/C Closed")
        assertTrue(member.isAccountClosed)
        assertFalse(member.isEligibleForQr)
    }

    @Test
    fun `No Demand station is independently ineligible`() {
        val member = Member(station = " No Demand ")
        assertFalse(member.isAccountClosed)
        assertTrue(member.isNoDemand)
        assertFalse(member.isEligibleForQr)
    }

    @Test
    fun `active station is not treated as closed`() {
        val member = Member(station = "HEAD OFFICE")
        assertFalse(member.isAccountClosed)
        assertFalse(member.isNoDemand)
        assertTrue(member.isEligibleForQr)
    }
}
