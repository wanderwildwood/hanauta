package com.wanderwildwood.hanauta.music

import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

class MusicXmlTest {
    private fun parse(xml: String) = DocumentBuilderFactory.newInstance().apply {
        setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
    }.newDocumentBuilder().parse(xml.byteInputStream())

    @Test
    fun `every bar adds up and a note over the bar line is tied`() {
        // 4/4: a dotted half from beat 3 crosses into bar two.
        val notes = listOf(Note(0, 480, 60), Note(960, 1440, 64), Note(2400, 160, 67))
        val doc = parse(MusicXml.write(Score("Tune & such", notes, 90f, 4, Key(2, false))))
        val measures = doc.getElementsByTagName("measure")
        assertEquals(2, measures.length)
        for (m in 0 until measures.length) {
            val notesIn = (measures.item(m) as Element).getElementsByTagName("duration")
            var sum = 0
            for (i in 0 until notesIn.length) sum += notesIn.item(i).textContent.toInt()
            assertEquals("bar ${m + 1}", 48, sum)
        }
        assertEquals(2, doc.getElementsByTagName("tied").length)
        assertEquals("2", doc.getElementsByTagName("fifths").item(0).textContent)
        assertEquals("Tune & such", doc.getElementsByTagName("work-title").item(0).textContent)
        // The triplet eighth, and the triplet quarter rest that finishes its beat.
        assertEquals(2, doc.getElementsByTagName("time-modification").length)
    }

    @Test
    fun `flats in a flat key`() {
        val doc = parse(MusicXml.write(Score("x", listOf(Note(0, 480, 70)), 90f, 3, Key(5, false))))
        assertEquals("B", doc.getElementsByTagName("step").item(0).textContent)
        assertEquals("-1", doc.getElementsByTagName("alter").item(0).textContent)
        var sum = 0
        val d = doc.getElementsByTagName("duration")
        for (i in 0 until d.length) sum += d.item(i).textContent.toInt()
        assertEquals(36, sum)
    }
}
