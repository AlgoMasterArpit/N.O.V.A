package com.arpit.nova

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrainTest {
    private val tenAm = 10 * 60
    private fun p(s: String) = Brain.parse(s, tenAm)

    @Test fun commands() {
        assertEquals(Cmd.Alarm(7 * 60), p("hey nova subah 7 baje ka alarm laga do"))
        assertEquals(Cmd.Alarm(19 * 60 + 30), p("saade saat baje alarm"))  // 10am now -> next 7:30 is pm
        assertEquals(Cmd.Alarm(17 * 60), p("set alarm for 5"))           // 10am now -> 5pm is next
        assertEquals(Cmd.Alarm(6 * 60 + 15), p("wake me at 6.15 a.m."))
        assertEquals(Cmd.Alarm(null), p("alarm laga do"))
        assertEquals(Cmd.Call("sahil"), p("call sahil"))
        assertEquals(Cmd.Call("sahil"), p("Sahil ko phone lagao"))
        assertEquals(Cmd.Quiet(120), p("listen nova agle do ghante shaant rehna"))
        assertEquals(Cmd.Quiet(60), p("chup ho jao"))
        assertEquals(Cmd.Ask(App.CHATGPT, "photosynthesis kya hai"), p("ChatGPT se pucho photosynthesis kya hai"))
        assertEquals(Cmd.Ask(App.CLAUDE, "plan my goa trip"), p("ask claude plan my goa trip"))
        assertEquals(Cmd.Ask(App.GOOGLE, "weather"), p("google pe weather search karo"))
        assertEquals(Cmd.Open(App.GEMINI), p("gemini kholo"))
        assertEquals(Cmd.Open(App.CALENDAR), p("open calendar"))
        assertEquals(Cmd.Navigate("india gate"), p("navigate to india gate"))
        assertEquals(Cmd.Navigate("connaught place"), p("mujhe connaught place le chalo"))
        assertEquals(Cmd.StoreSearch("whatsapp"), p("play store pe whatsapp search karo"))
        assertEquals(Cmd.StoreSearch("spotify"), p("spotify install karo"))
        assertEquals(Cmd.Schedule(false), p("aaj meri meetings kya hai"))
        assertEquals(Cmd.Schedule(true), p("kal ka schedule batao"))
        assertEquals(Cmd.AddEvent("ravi ke saath meeting", 17 * 60, true), p("kal 5 baje ravi ke saath meeting add karo"))
        assertEquals(Cmd.Note("kal doodh lana hai"), p("note karo kal doodh lana hai"))
        assertEquals(Cmd.ReadNotes, p("mere notes padho"))
        assertEquals(Cmd.Unknown, p("open youtube"))
    }

    @Test fun lockedPhoneOnlyAlarms() {
        assertTrue(p("alarm 7 baje").safeWhenLocked())
        assertFalse(p("call sahil").safeWhenLocked())
        assertFalse(p("chup ho jao").safeWhenLocked())
        assertFalse(p("mere notes padho").safeWhenLocked())
    }

    @Test fun done() {
        assertTrue(Brain.isDone("nahi boss"))
        assertTrue(Brain.isDone("bas thank you"))
        assertTrue(Brain.isDone("so jao"))
        assertFalse(Brain.isDone("call sahil"))
        assertFalse(Brain.isDone("no i want to know the weather in delhi today"))
    }

    @Test fun yesNo() {
        assertTrue(Brain.isYes("haan kar do"))
        assertTrue(Brain.isYes("ok"))
        assertFalse(Brain.isYes("nahi mat karo"))
        assertFalse(Brain.isYes("pata nahi"))
    }
}
