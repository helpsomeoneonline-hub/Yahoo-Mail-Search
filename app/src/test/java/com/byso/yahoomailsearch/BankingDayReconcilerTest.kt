package com.byso.yahoomailsearch

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class BankingDayReconcilerTest {
    private val zone = ZoneId.of("America/Port_of_Spain")

    private fun millis(date: LocalDate): Long =
        date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    private fun reconcileForArrival(date: LocalDate): ReconciliationResult {
        val friday = LocalDate.of(2026, 10, 9)
        val sale = SheetEntry(
            rowNumber = 830,
            dateMs = friday.atStartOfDay(zone).toInstant().toEpochMilli(),
            amount = 500.00,
            description = "Facebook ads payment",
            reference = "",
            raw = ""
        )
        val email = MailRecord(
            folder = "Inbox",
            uid = 200L,
            subject = "Republic Bank payment notice",
            sender = "Republic Bank",
            recipients = "customer@example.com",
            dateMs = millis(date),
            body = "Transaction Amount: TT$500.00",
            hasAttachments = false,
            attachmentNames = ""
        )
        return Reconciler.reconcile(listOf(email), listOf(sale))
    }

    @Test fun fridayPaymentArrivingMondayMatches() {
        assertEquals(1, reconcileForArrival(LocalDate.of(2026, 10, 12)).matched)
    }

    @Test fun fridayPaymentArrivingTuesdayMatches() {
        assertEquals(1, reconcileForArrival(LocalDate.of(2026, 10, 13)).matched)
    }

    @Test fun fridayPaymentArrivingWednesdayNeedsReview() {
        val result = reconcileForArrival(LocalDate.of(2026, 10, 14))
        assertEquals(0, result.matched)
        // A late transfer should never be silently confirmed.
        assertEquals(1, result.needsReview)
    }
}
