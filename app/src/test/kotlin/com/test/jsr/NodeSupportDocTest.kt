package com.test.jsr

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * docs/node-support.json is generated from the mapper registry (plan 6.1 A3):
 * this test re-writes the file from the registry statuses and fails on drift
 * against the committed copy. One row per registered node type:
 * { type, status, reason? } — the smallest truthful generator.
 */
class NodeSupportDocTest {

    private fun inventoryRoot(): File =
        listOf("app/.conformance", ".conformance", "../.conformance")
            .map { File("$it/js_widget_runtime/docs/contract/nodes") }
            .first { it.isDirectory }

    private fun docFile(): File {
        val root = listOf(File("docs/node-support.json"), File("../docs/node-support.json"))
            .first { it.parentFile != null && it.parentFile.isDirectory }
        return root
    }

    private fun expected(): String {
        val rows = JSONArray()
        for (type in inventoryRoot().listFiles()!!.map { it.name.removeSuffix(".md") }.sorted()) {
            val (status, reason) = NodeMapperRegistry.statusOf(type)
            val row = JSONObject()
            row.put("type", type)
            row.put("status", status)
            if (reason != null) row.put("reason", reason)
            rows.put(row)
        }
        return rows.toString(2) + "\n"
    }

    @Test
    fun `node support doc matches the registry`() {
        val file = docFile()
        val committed = if (file.isFile) file.readText() else null
        val generated = expected()
        file.writeText(generated)
        if (committed == null) {
            // First generation: the test IS the generator; drift checks apply from now on.
            return
        }
        assertEquals(
            "docs/node-support.json drifted from the mapper registry; commit the regenerated file",
            committed,
            generated,
        )
    }

    @Test
    fun `the doc covers the full inventory with unique rows`() {
        val file = docFile()
        if (!file.isFile) file.writeText(expected())
        val rows = JSONArray(file.readText())
        val types = (0 until rows.length()).map { rows.getJSONObject(it).getString("type") }
        assertEquals(inventoryRoot().listFiles()!!.size, types.size)
        assertEquals(types.size, types.toSet().size)
    }
}
