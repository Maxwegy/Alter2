<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { api, type CockpitEvent, type Principal, type ServerStatus } from '../api'

const props = defineProps<{ me: Principal; lastEvent: CockpitEvent | null }>()
const emit = defineEmits<{ error: [message: string] }>()

const status = ref<ServerStatus | null>(null)
const log = ref<string[]>([])
const ticks = ref(0)
const busy = ref(false)
let timer: number | undefined

const canControl = computed(() => props.me.role !== 'VIEWER')
const health = computed(() => (status.value?.health ?? {}) as Record<string, any>)

async function refresh() {
  try {
    status.value = await api.server()
  } catch (e: any) {
    emit('error', e.message)
  }
}

async function loadLog() {
  try {
    log.value = (await api.serverLog(200)).lines
  } catch (e: any) {
    emit('error', e.message)
  }
}

async function run(action: () => Promise<unknown>) {
  busy.value = true
  try {
    await action()
    await refresh()
  } catch (e: any) {
    emit('error', e.message)
  } finally {
    busy.value = false
  }
}

watch(() => props.lastEvent, (event) => {
  if (!event) return
  if (event.type === 'log') {
    log.value.push(event.payload as string)
    if (log.value.length > 500) log.value.shift()
  }
  if (event.type === 'server.lifecycle') refresh()
})

onMounted(() => {
  refresh()
  loadLog()
  timer = window.setInterval(refresh, 10_000)
})
onUnmounted(() => window.clearInterval(timer))
</script>

<template>
  <h1>Dashboard</h1>
  <div class="panel">
    <div class="row">
      <h2 style="margin: 0">Game server</h2>
      <span v-if="status" class="pill" :class="status.state">{{ status.state }}</span>
      <span v-if="status?.pid" class="muted">pid {{ status.pid }}</span>
      <span v-if="status && !status.managed && status.state !== 'STOPPED'" class="muted">started outside the cockpit</span>
      <span v-if="status?.lastExitCode !== undefined && status?.state === 'STOPPED'" class="muted">last exit code {{ status.lastExitCode }}</span>
    </div>
    <div v-if="status?.health" class="row" style="margin-top: .5rem">
      <span>revision {{ health.revision }}</span>
      <span>cycle {{ health.cycle }}</span>
      <span>{{ health.players }} players</span>
      <span>{{ health.npcs }} npcs</span>
      <span>up {{ Math.round((health.uptimeSeconds ?? 0) / 60) }} min</span>
      <span v-if="health.status === 'stalled'" style="color: var(--danger)">tick stalled</span>
      <span v-if="health.shutdownScheduled" style="color: var(--warn)">shutdown in {{ health.rebootTimer }} ticks</span>
    </div>
    <div v-if="canControl" class="row" style="margin-top: .75rem">
      <button class="primary" :disabled="busy || status?.state !== 'STOPPED'" @click="run(api.serverStart)">Start</button>
      <label class="muted">countdown <input v-model.number="ticks" type="number" min="0" max="6000" style="width: 5rem" /> ticks</label>
      <button :disabled="busy || !status || status.state === 'STOPPED'" @click="run(() => api.serverStop(ticks))">Stop</button>
      <button :disabled="busy || !status || status.state === 'STOPPED'" @click="run(() => api.serverRestart(ticks))">Restart</button>
      <button :disabled="busy || status?.state !== 'RUNNING'" @click="run(api.wikiReload)">Reload wiki snapshot</button>
    </div>
  </div>

  <div class="panel">
    <div class="row"><h2 style="margin: 0">Server log</h2><button @click="loadLog">Reload</button></div>
    <pre style="margin-top: .5rem">{{ log.join('\n') || 'No log lines yet.' }}</pre>
  </div>
</template>
