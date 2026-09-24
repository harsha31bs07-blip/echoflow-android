package com.echoflow.core.safety

import com.echoflow.core.model.SnapshotJson
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Runs every snapshot dump under src/test/resources/fixtures:
 *   fixtures/sensitive/<kind>/<name>.json  must be classified as <kind> (e.g. payment, otp);
 *   fixtures/safe/<name>.json               must be classified SAFE.
 * Dumps come from the on-device safety monitor ("Dump" button); see docs/SAFETY_FIXTURES.md.
 */
class FixtureHarnessTest {
    private val classifier = ScreenSafetyClassifier(ownPackage = "com.echoflow")
    private val root: File = File(javaClass.classLoader.getResource("fixtures")?.toURI() ?: error("fixtures dir missing"))

    @Test fun `sensitive fixtures trip their kind`() {
        val dirs = File(root, "sensitive").listFiles { f -> f.isDirectory }.orEmpty()
        assertTrue(dirs.isNotEmpty(), "no sensitive fixtures found")
        val failures = dirs.flatMap { dir ->
            val kind = SensitiveKind.valueOf(dir.name.uppercase())
            dir.jsonFiles().mapNotNull { file ->
                val verdict = classifier.classify(SnapshotJson.decode(file.readText()))
                if (kind in verdict.kinds) null else "${dir.name}/${file.name}: got ${verdict.summary()}"
            }
        }
        if (failures.isNotEmpty()) fail("Missed sensitive screens:\n" + failures.joinToString("\n"))
    }

    @Test fun `safe fixtures stay safe`() {
        val failures = File(root, "safe").jsonFiles().mapNotNull { file ->
            val verdict = classifier.classify(SnapshotJson.decode(file.readText()))
            if (verdict.isSensitive) "${file.name}: ${verdict.summary()}" else null
        }
        if (failures.isNotEmpty()) fail("False hand-offs:\n" + failures.joinToString("\n"))
    }

    private fun File.jsonFiles(): List<File> = listFiles { f -> f.extension == "json" }.orEmpty().sortedBy { it.name }
}
