package com.fightexchange

import com.fightexchange.ingest.HistoryImporter
import io.micronaut.context.ApplicationContext
import java.io.File
import kotlin.system.exitProcess

/** `./gradlew :app:importHistory --file=ufc-master.csv [--organization=UFC]` (database from the usual env). */
fun main(args: Array<String>) {
    val options =
        args.filter { it.startsWith("--") }.associate {
            it.removePrefix("--").substringBefore('=') to
                it.substringAfter('=', "")
        }
    val file = options["file"]?.takeIf { it.isNotBlank() }
    if (file == null || !File(file).isFile) {
        System.err.println("Usage: importHistory --file=<csv> [--organization=UFC]")
        exitProcess(2)
    }
    ApplicationContext.run().use { context ->
        val result =
            context.getBean(HistoryImporter::class.java).import(
                File(file).reader(),
                options["organization"] ?: "UFC",
            )
        println(result)
    }
}
