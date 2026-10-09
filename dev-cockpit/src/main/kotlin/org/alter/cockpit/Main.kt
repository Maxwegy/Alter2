package org.alter.cockpit

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.data.config.DataPaths
import java.util.concurrent.CountDownLatch

/**
 * `./gradlew :dev-cockpit:run [--args="--new-owner-token"]`. Runs from `game-server/` (or with
 * `-Dalter.dataDir=`) so the data directory resolves like it does for the server.
 */
fun main(args: Array<String>) {
    val logger = KotlinLogging.logger {}
    val paths = DataPaths.default()
    val config = CockpitConfig.load(paths.cockpitConfig)
    val cockpit = Cockpit.build(paths, config, newOwnerToken = "--new-owner-token" in args)
    val stopped = CountDownLatch(1)
    Runtime.getRuntime().addShutdownHook(
        Thread({
            logger.info { "Dev Cockpit stopping" }
            cockpit.close()
            stopped.countDown()
        }, "cockpit-shutdown"),
    )
    cockpit.start()
    logger.info { "Dev Cockpit ready: http://${config.bindAddress}:${config.port}/" }
    stopped.await()
}
