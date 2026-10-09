<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { api, type AuditEntry, type CockpitEvent } from '../api'

const props = defineProps<{ lastEvent: CockpitEvent | null }>()
const emit = defineEmits<{ error: [message: string] }>()
const entries = ref<AuditEntry[]>([])

async function load() {
  try {
    entries.value = await api.audit(200)
  } catch (e: any) {
    emit('error', e.message)
  }
}

watch(() => props.lastEvent, (event) => {
  if (event && (event.type.startsWith('inbox.') || event.type === 'server.lifecycle')) load()
})
onMounted(load)
</script>

<template>
  <h1>Audit log</h1>
  <div class="panel">
    <table>
      <thead>
        <tr><th>When</th><th>Who</th><th>Action</th><th>Target</th><th>Details</th></tr>
      </thead>
      <tbody>
        <tr v-for="entry in entries" :key="entry.id">
          <td class="muted">{{ entry.at.slice(0, 19).replace('T', ' ') }}</td>
          <td>{{ entry.actor }} <span class="muted">{{ entry.role.toLowerCase() }}</span></td>
          <td><code>{{ entry.action }}</code></td>
          <td><code>{{ entry.target ?? '' }}</code></td>
          <td><code>{{ JSON.stringify(entry.details) }}</code></td>
        </tr>
      </tbody>
    </table>
    <p v-if="!entries.length" class="muted">No decisions yet.</p>
  </div>
</template>
