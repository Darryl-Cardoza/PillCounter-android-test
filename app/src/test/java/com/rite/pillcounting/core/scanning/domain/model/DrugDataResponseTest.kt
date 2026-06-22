package com.rite.pillcounting.core.scanning.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DrugDataResponseTest {

    // ---------- ActiveIngredient ----------
    private fun ingredient() = ActiveIngredient(
        name = "Aspirin",
        strength = "500 mg",
        strength_raw = "500mg",
        strength_value = 500.0,
        strength_unit = "mg",
        ingredient_code = "IC1"
    )

    @Test
    fun activeIngredient_defaultsNull() {
        val a = ActiveIngredient()
        assertNull(a.name)
        assertNull(a.strength)
        assertNull(a.strength_raw)
        assertNull(a.strength_value)
        assertNull(a.strength_unit)
        assertNull(a.ingredient_code)
    }

    @Test
    fun activeIngredient_fullCoverage() {
        val a = ingredient()
        assertEquals("Aspirin", a.name)
        assertEquals("500 mg", a.strength)
        assertEquals("500mg", a.strength_raw)
        assertEquals(500.0, a.strength_value!!, 0.0)
        assertEquals("mg", a.strength_unit)
        assertEquals("IC1", a.ingredient_code)
        assertEquals(ingredient(), a)
        assertEquals(ingredient().hashCode(), a.hashCode())
        assertNotEquals(a, a.copy(name = "x"))
        assertTrue(a.toString().contains("Aspirin"))
        assertEquals("x", a.copy(name = "x").name)
        assertEquals("Aspirin", a.component1())
        assertEquals("500 mg", a.component2())
        assertEquals("500mg", a.component3())
        assertEquals(500.0, a.component4()!!, 0.0)
        assertEquals("mg", a.component5())
        assertEquals("IC1", a.component6())
    }

    // ---------- DrugRegulatory ----------
    private fun regulatory() = DrugRegulatory(
        schedule = "II",
        is_controlled = true,
        status = "active",
        status_date = "2020"
    )

    @Test
    fun drugRegulatory_defaultsNull() {
        val r = DrugRegulatory()
        assertNull(r.schedule)
        assertNull(r.is_controlled)
        assertNull(r.status)
        assertNull(r.status_date)
    }

    @Test
    fun drugRegulatory_fullCoverage() {
        val r = regulatory()
        assertEquals("II", r.schedule)
        assertEquals(true, r.is_controlled)
        assertEquals("active", r.status)
        assertEquals("2020", r.status_date)
        assertEquals(regulatory(), r)
        assertEquals(regulatory().hashCode(), r.hashCode())
        assertNotEquals(r, r.copy(schedule = "I"))
        assertTrue(r.toString().contains("active"))
        assertEquals("I", r.copy(schedule = "I").schedule)
        assertEquals("II", r.component1())
        assertEquals(true, r.component2())
        assertEquals("active", r.component3())
        assertEquals("2020", r.component4())
    }

    // ---------- DrugTherapeutic ----------
    private fun therapeutic() = DrugTherapeutic(
        primary_class = "P",
        secondary_classes = listOf("S1"),
        rxclass_source = "src",
        fda_note = "note",
        atc_codes = listOf("A1")
    )

    @Test
    fun drugTherapeutic_defaultsNull() {
        val t = DrugTherapeutic()
        assertNull(t.primary_class)
        assertNull(t.secondary_classes)
        assertNull(t.rxclass_source)
        assertNull(t.fda_note)
        assertNull(t.atc_codes)
    }

    @Test
    fun drugTherapeutic_fullCoverage() {
        val t = therapeutic()
        assertEquals("P", t.primary_class)
        assertEquals(listOf("S1"), t.secondary_classes)
        assertEquals("src", t.rxclass_source)
        assertEquals("note", t.fda_note)
        assertEquals(listOf("A1"), t.atc_codes)
        assertEquals(therapeutic(), t)
        assertEquals(therapeutic().hashCode(), t.hashCode())
        assertNotEquals(t, t.copy(primary_class = "x"))
        assertTrue(t.toString().contains("note"))
        assertEquals("x", t.copy(primary_class = "x").primary_class)
        assertEquals("P", t.component1())
        assertEquals(listOf("S1"), t.component2())
        assertEquals("src", t.component3())
        assertEquals("note", t.component4())
        assertEquals(listOf("A1"), t.component5())
    }

    // ---------- DrugImageItem ----------
    private fun imageItem() = DrugImageItem(url = "http://x", filename = "f.png")

    @Test
    fun drugImageItem_defaultsNull() {
        val i = DrugImageItem()
        assertNull(i.url)
        assertNull(i.filename)
    }

    @Test
    fun drugImageItem_fullCoverage() {
        val i = imageItem()
        assertEquals("http://x", i.url)
        assertEquals("f.png", i.filename)
        assertEquals(imageItem(), i)
        assertEquals(imageItem().hashCode(), i.hashCode())
        assertNotEquals(i, i.copy(url = "y"))
        assertTrue(i.toString().contains("f.png"))
        assertEquals("y", i.copy(url = "y").url)
        assertEquals("http://x", i.component1())
        assertEquals("f.png", i.component2())
    }

    // ---------- DrugImages ----------
    private fun images() = DrugImages(total = 1, primary = "p", all = listOf(imageItem()))

    @Test
    fun drugImages_defaultsNull() {
        val d = DrugImages()
        assertNull(d.total)
        assertNull(d.primary)
        assertNull(d.all)
    }

    @Test
    fun drugImages_fullCoverage() {
        val d = images()
        assertEquals(1, d.total)
        assertEquals("p", d.primary)
        assertEquals(listOf(imageItem()), d.all)
        assertEquals(images(), d)
        assertEquals(images().hashCode(), d.hashCode())
        assertNotEquals(d, d.copy(total = 9))
        assertTrue(d.toString().contains("p"))
        assertEquals(9, d.copy(total = 9).total)
        assertEquals(1, d.component1())
        assertEquals("p", d.component2())
        assertEquals(listOf(imageItem()), d.component3())
    }

    // ---------- PackageLevel ----------
    private fun packageLevel() = PackageLevel(
        type = "UNIT",
        name = "TABLET",
        quantity = 30,
        modifiers = listOf("m1"),
        material = "plastic",
        contains = PackageLevel(type = "INNER")
    )

    @Test
    fun packageLevel_defaultsNull() {
        val p = PackageLevel()
        assertNull(p.type)
        assertNull(p.name)
        assertNull(p.quantity)
        assertNull(p.modifiers)
        assertNull(p.material)
        assertNull(p.contains)
    }

    @Test
    fun packageLevel_fullCoverage() {
        val p = packageLevel()
        assertEquals("UNIT", p.type)
        assertEquals("TABLET", p.name)
        assertEquals(30, p.quantity)
        assertEquals(listOf("m1"), p.modifiers)
        assertEquals("plastic", p.material)
        assertEquals(PackageLevel(type = "INNER"), p.contains)
        assertEquals(packageLevel(), p)
        assertEquals(packageLevel().hashCode(), p.hashCode())
        assertNotEquals(p, p.copy(type = "x"))
        assertTrue(p.toString().contains("TABLET"))
        assertEquals("x", p.copy(type = "x").type)
        assertEquals("UNIT", p.component1())
        assertEquals("TABLET", p.component2())
        assertEquals(30, p.component3())
        assertEquals(listOf("m1"), p.component4())
        assertEquals("plastic", p.component5())
        assertEquals(PackageLevel(type = "INNER"), p.component6())
    }

    // ---------- Package ----------
    private fun pkg() = Package(
        description = "desc",
        sizes = listOf("30s"),
        levels = listOf(packageLevel())
    )

    @Test
    fun pkg_defaultsNull() {
        val p = Package()
        assertNull(p.description)
        assertNull(p.sizes)
        assertNull(p.levels)
    }

    @Test
    fun pkg_fullCoverage() {
        val p = pkg()
        assertEquals("desc", p.description)
        assertEquals(listOf("30s"), p.sizes)
        assertEquals(listOf(packageLevel()), p.levels)
        assertEquals(pkg(), p)
        assertEquals(pkg().hashCode(), p.hashCode())
        assertNotEquals(p, p.copy(description = "x"))
        assertTrue(p.toString().contains("desc"))
        assertEquals("x", p.copy(description = "x").description)
        assertEquals("desc", p.component1())
        assertEquals(listOf("30s"), p.component2())
        assertEquals(listOf(packageLevel()), p.component3())
    }

    // ---------- NdcDrugInfo ----------
    private fun ndcInfo() = NdcDrugInfo(
        drug_code = "DC1",
        brand_name = "Brand",
        generic_name = "Generic",
        splittable = true,
        standard_name = "Std",
        active_ingredients = listOf(ingredient()),
        regulatory = regulatory(),
        dosage_form = listOf("TABLET"),
        lookup_name = "lookup",
        manufacturer = "Mfg",
        route = listOf("ORAL"),
        therapeutic = therapeutic(),
        images = images(),
        updated_at = "2021",
        `package` = pkg(),
        is_hazardous = false
    )

    @Test
    fun ndcDrugInfo_defaultsNull() {
        val n = NdcDrugInfo()
        assertNull(n.drug_code)
        assertNull(n.brand_name)
        assertNull(n.generic_name)
        assertNull(n.splittable)
        assertNull(n.standard_name)
        assertNull(n.active_ingredients)
        assertNull(n.regulatory)
        assertNull(n.dosage_form)
        assertNull(n.lookup_name)
        assertNull(n.manufacturer)
        assertNull(n.route)
        assertNull(n.therapeutic)
        assertNull(n.images)
        assertNull(n.updated_at)
        assertNull(n.`package`)
        assertNull(n.is_hazardous)
    }

    @Test
    fun ndcDrugInfo_fullCoverage() {
        val n = ndcInfo()
        assertEquals("DC1", n.drug_code)
        assertEquals("Brand", n.brand_name)
        assertEquals("Generic", n.generic_name)
        assertEquals(true, n.splittable)
        assertEquals("Std", n.standard_name)
        assertEquals(listOf(ingredient()), n.active_ingredients)
        assertEquals(regulatory(), n.regulatory)
        assertEquals(listOf("TABLET"), n.dosage_form)
        assertEquals("lookup", n.lookup_name)
        assertEquals("Mfg", n.manufacturer)
        assertEquals(listOf("ORAL"), n.route)
        assertEquals(therapeutic(), n.therapeutic)
        assertEquals(images(), n.images)
        assertEquals("2021", n.updated_at)
        assertEquals(pkg(), n.`package`)
        assertEquals(false, n.is_hazardous)
        assertEquals(ndcInfo(), n)
        assertEquals(ndcInfo().hashCode(), n.hashCode())
        assertNotEquals(n, n.copy(drug_code = "x"))
        assertTrue(n.toString().contains("Brand"))
        assertEquals("x", n.copy(drug_code = "x").drug_code)
        assertEquals("DC1", n.component1())
        assertEquals("Brand", n.component2())
        assertEquals("Generic", n.component3())
        assertEquals(true, n.component4())
        assertEquals("Std", n.component5())
        assertEquals(listOf(ingredient()), n.component6())
        assertEquals(regulatory(), n.component7())
        assertEquals(listOf("TABLET"), n.component8())
        assertEquals("lookup", n.component9())
        assertEquals("Mfg", n.component10())
        assertEquals(listOf("ORAL"), n.component11())
        assertEquals(therapeutic(), n.component12())
        assertEquals(images(), n.component13())
        assertEquals("2021", n.component14())
        assertEquals(pkg(), n.component15())
        assertEquals(false, n.component16())
    }

    // ---------- DrugComparisonData ----------
    private fun comparison() = DrugComparisonData(
        is_ndc_same = true,
        is_ndc_equivalent = false,
        target_ndc = ndcInfo(),
        scanned_ndc = ndcInfo().copy(drug_code = "DC2")
    )

    @Test
    fun drugComparisonData_defaultsNull() {
        val c = DrugComparisonData()
        assertNull(c.is_ndc_same)
        assertNull(c.is_ndc_equivalent)
        assertNull(c.target_ndc)
        assertNull(c.scanned_ndc)
    }

    @Test
    fun drugComparisonData_fullCoverage() {
        val c = comparison()
        assertEquals(true, c.is_ndc_same)
        assertEquals(false, c.is_ndc_equivalent)
        assertEquals(ndcInfo(), c.target_ndc)
        assertEquals(ndcInfo().copy(drug_code = "DC2"), c.scanned_ndc)
        assertEquals(comparison(), c)
        assertEquals(comparison().hashCode(), c.hashCode())
        assertNotEquals(c, c.copy(is_ndc_same = false))
        assertTrue(c.toString().contains("is_ndc_same"))
        assertEquals(false, c.copy(is_ndc_same = false).is_ndc_same)
        assertEquals(true, c.component1())
        assertEquals(false, c.component2())
        assertEquals(ndcInfo(), c.component3())
        assertEquals(ndcInfo().copy(drug_code = "DC2"), c.component4())
    }

    // ---------- DrugDataResponse ----------
    private fun response() = DrugDataResponse(
        status = 200,
        is_success = true,
        message = "ok",
        token = "tok",
        data = comparison()
    )

    @Test
    fun drugDataResponse_defaultsNull() {
        val r = DrugDataResponse()
        assertNull(r.status)
        assertNull(r.is_success)
        assertNull(r.message)
        assertNull(r.token)
        assertNull(r.data)
    }

    @Test
    fun drugDataResponse_fullCoverage() {
        val r = response()
        assertEquals(200, r.status)
        assertEquals(true, r.is_success)
        assertEquals("ok", r.message)
        assertEquals("tok", r.token)
        assertEquals(comparison(), r.data)
        assertEquals(response(), r)
        assertEquals(response().hashCode(), r.hashCode())
        assertNotEquals(r, r.copy(status = 500))
        assertTrue(r.toString().contains("ok"))
        assertEquals(500, r.copy(status = 500).status)
        assertEquals(200, r.component1())
        assertEquals(true, r.component2())
        assertEquals("ok", r.component3())
        assertEquals("tok", r.component4())
        assertEquals(comparison(), r.component5())
    }
}
