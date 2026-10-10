package org.alter.plugins.content.infrastructure.spawns

import org.alter.data.spawns.SpawnEditOutbox
import org.alter.game.service.Service

/** Holds the runtime spawn-edit [outbox] (`data/run/spawn-edits.jsonl`) so the spawn commands can find it. */
class SpawnEditOutboxService(val outbox: SpawnEditOutbox) : Service
