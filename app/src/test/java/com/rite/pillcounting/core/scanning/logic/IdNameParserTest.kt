package com.rite.pillcounting.core.scanning.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IdNameParserTest {

    @Test
    fun `badge - picks prominent name line, skips org and role`() {
        val lines = listOf(
            IdTextLine("Medical Center", 30),
            IdTextLine("Cole Paulson", 42),
            IdTextLine("ID# 123456", 18),
            IdTextLine("PHARMACIST", 36),
        )
        assertEquals(IdCardName("Cole", "Paulson"), IdNameParser.parse(lines))
    }

    @Test
    fun `badge - taller name wins over other two-word lines`() {
        val lines = listOf(
            IdTextLine("Jane Doe", 20),
            IdTextLine("Cole Paulson", 48),
        )
        assertEquals(IdCardName("Cole", "Paulson"), IdNameParser.parse(lines))
    }

    @Test
    fun `license front - labeled LN FN fields`() {
        val lines = listOf(
            IdTextLine("CALIFORNIA DRIVER LICENSE", 20),
            IdTextLine("LN PAULSON", 24),
            IdTextLine("FN COLE ALAN", 24),
            IdTextLine("DOB 01/02/1990", 16),
        )
        assertEquals(IdCardName("Cole", "Paulson"), IdNameParser.parse(lines))
    }

    @Test
    fun `license front - numbered fields with address noise`() {
        val lines = listOf(
            IdTextLine("1 PAULSON", 24),
            IdTextLine("2 COLE", 24),
            IdTextLine("8 100 MAIN ST", 16),
        )
        assertEquals(IdCardName("Cole", "Paulson"), IdNameParser.parse(lines))
    }

    @Test
    fun `numbered last-name label alone does not misfire on an address`() {
        val lines = listOf(
            IdTextLine("1 MAIN ST", 24),
            IdTextLine("ANYTOWN USA", 20),
        )
        assertNull(IdNameParser.parse(lines))
    }

    @Test
    fun `name label - inline value`() {
        val lines = listOf(
            IdTextLine("Medical Center", 30),
            IdTextLine("Name: Cole Paulson", 22),
        )
        assertEquals(IdCardName("Cole", "Paulson"), IdNameParser.parse(lines))
    }

    @Test
    fun `name label - value on next line beats a taller unlabeled name`() {
        val lines = listOf(
            IdTextLine("Jane Doe", 50),
            IdTextLine("Name", 18),
            IdTextLine("Cole Paulson", 22),
        )
        assertEquals(IdCardName("Cole", "Paulson"), IdNameParser.parse(lines))
    }

    @Test
    fun `stacked first and last name labels`() {
        val lines = listOf(
            IdTextLine("First Name", 18),
            IdTextLine("Cole", 24),
            IdTextLine("Last Name", 18),
            IdTextLine("Paulson", 24),
        )
        assertEquals(IdCardName("Cole", "Paulson"), IdNameParser.parse(lines))
    }

    @Test
    fun `word merely starting with name is not a label`() {
        val lines = listOf(
            IdTextLine("Namesake Pharmacy", 30),
            IdTextLine("Cole Paulson", 22),
        )
        assertEquals(IdCardName("Cole", "Paulson"), IdNameParser.parse(lines))
    }

    @Test
    fun `name label followed by junk falls through to prominence`() {
        val lines = listOf(
            IdTextLine("Name", 18),
            IdTextLine("ID# 123456", 16),
            IdTextLine("Cole Paulson", 30),
        )
        assertEquals(IdCardName("Cole", "Paulson"), IdNameParser.parse(lines))
    }

    @Test
    fun `candidateWords - name-labeled words come first`() {
        val lines = listOf(
            IdTextLine("Jane Doe", 50),
            IdTextLine("Name", 18),
            IdTextLine("Cole Paulson", 22),
        )
        assertEquals(
            listOf("Cole", "Paulson", "Jane", "Doe"),
            IdNameParser.candidateWords(lines),
        )
    }

    @Test
    fun `comma format - last comma first`() {
        val lines = listOf(IdTextLine("PAULSON, COLE", 30))
        assertEquals(IdCardName("Cole", "Paulson"), IdNameParser.parse(lines))
    }

    @Test
    fun `titles and credentials are stripped, not disqualifying`() {
        val lines = listOf(IdTextLine("Dr. Cole Paulson, PharmD", 30))
        assertEquals(IdCardName("Cole", "Paulson"), IdNameParser.parse(lines))
    }

    @Test
    fun `middle initial allowed`() {
        val lines = listOf(IdTextLine("Cole A Paulson", 30))
        assertEquals(IdCardName("Cole", "Paulson"), IdNameParser.parse(lines))
    }

    @Test
    fun `apostrophes and hyphens survive display casing`() {
        val lines = listOf(IdTextLine("SHAUN O'BRIEN-SMITH", 30))
        assertEquals(IdCardName("Shaun", "O'Brien-Smith"), IdNameParser.parse(lines))
    }

    @Test
    fun `no name-like content returns null`() {
        val lines = listOf(
            IdTextLine("Medical Center", 30),
            IdTextLine("PHARMACIST", 36),
            IdTextLine("ID# 123456", 18),
        )
        assertNull(IdNameParser.parse(lines))
    }

    @Test
    fun `empty input returns null`() {
        assertNull(IdNameParser.parse(emptyList()))
    }

    @Test
    fun `candidateWords - name words only, prominent line first`() {
        val lines = listOf(
            IdTextLine("Medical Center", 30),
            IdTextLine("Jane Doe", 20),
            IdTextLine("Cole Paulson", 42),
            IdTextLine("ID# 123456", 18),
            IdTextLine("PHARMACIST", 36),
        )
        assertEquals(
            listOf("Cole", "Paulson", "Jane", "Doe"),
            IdNameParser.candidateWords(lines),
        )
    }

    @Test
    fun `candidateWords - four-word name line kept even though parse rejects it`() {
        val lines = listOf(IdTextLine("Maria Del Carmen Lopez", 30))
        assertNull(IdNameParser.parse(lines))
        assertEquals(
            listOf("Maria", "Del", "Carmen", "Lopez"),
            IdNameParser.candidateWords(lines),
        )
    }

    @Test
    fun `candidateWords - lines with digits or license vocabulary contribute nothing`() {
        val lines = listOf(
            IdTextLine("CALIFORNIA DRIVER LICENSE", 20),
            IdTextLine("DOB 01/02/1990", 16),
            IdTextLine("100 MAIN ST", 16),
        )
        assertEquals(emptyList<String>(), IdNameParser.candidateWords(lines))
    }
}
