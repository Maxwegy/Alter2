<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { api, type ActionStatus, type CockpitEvent, type InboxAction, type Principal } from '../api'
import Enrichment from './Enrichment.vue'

const props = defineProps<{ me: Principal; lastEvent: CockpitEvent | null }>()
const emit = defineEmits<{ error: [message: string] }>()

const cards = ref<InboxAction[]>([])
const filter = ref<ActionStatus | 'OPEN'>('OPEN')
const editing = ref<string | null>(null)
const editText = ref('')
const deleting = ref<string | null>(null)
const reason = ref('')
const busy = ref<string | null>(null)

const canDecide = computed(() => props.me.role !== 'VIEWER')
const visible = computed(() =>
  filter.value === 'OPEN' ? cards.value.filter((c) => ['PENDING', 'RUNNING', 'SNOOZED', 'FAILED'].includes(c.status)) : cards.value.filter((c) => c.status === filter.value),
)

async function load() {
  try {
    cards.value = await api.inbox()
  } catch (e: any) {
    emit('error', e.message)
  }
}

function merge(card: InboxAction) {
  const index = cards.value.findIndex((c) => c.id === card.id)
  if (index >= 0) cards.value[index] = card
  else cards.value.unshift(card)
}

async function act(card: InboxAction, action: () => Promise<InboxAction>) {
  busy.value = card.id
  try {
    merge(await action())
  } catch (e: any) {
    emit('error', e.message)
  } finally {
    busy.value = null
  }
}

function startEdit(card: InboxAction) {
  editing.value = card.id
  editText.value = JSON.stringify(card.params, null, 2)
}

function saveEdit(card: InboxAction) {
  let params: Record<string, unknown>
  try {
    params = JSON.parse(editText.value)
  } catch {
    emit('error', 'Params must be valid JSON')
    return
  }
  editing.value = null
  act(card, () => api.edit(card.id, params))
}

function confirmDelete(card: InboxAction) {
  if (!reason.value.trim()) { emit('error', 'Give a reason; the automation rules learn from it.'); return }
  const why = reason.value
  deleting.value = null
  reason.value = ''
  act(card, () => api.remove(card.id, why))
}

function when(iso?: string) {
  return iso ? iso.slice(0, 16).replace('T', ' ') : ''
}

watch(() => props.lastEvent, (event) => {
  if (event && event.type.startsWith('inbox.')) merge(event.payload as InboxAction)
})
onMounted(load)
</script>

<template>
  <div class="row" style="justify-content: space-between">
    <h1>Inbox</h1>
    <label class="muted">
      show
      <select v-model="filter">
        <option value="OPEN">open</option>
        <option v-for="s in ['PENDING', 'SNOOZED', 'RUNNING', 'DONE', 'FAILED', 'DELETED']" :key="s" :value="s">{{ s.toLowerCase() }}</option>
      </select>
    </label>
  </div>
  <p v-if="!visible.length" class="muted">Nothing here. Cards appear when players hit unscripted content or the server needs attention.</p>

  <div v-for="card in visible" :key="card.id" class="card">
    <div class="row" style="justify-content: space-between">
      <h3 style="margin: 0">{{ card.title }}</h3>
      <span class="pill" :class="card.status">{{ card.status.toLowerCase() }}</span>
    </div>
    <div class="kind">{{ card.kind }} · {{ card.summary }} · updated {{ when(card.updatedAt) }}</div>
    <div v-if="card.error" class="error">{{ card.error }}</div>
    <Enrichment v-if="card.result?.kind" :result="card.result" />
    <div v-else-if="card.result" class="result">{{ JSON.stringify(card.result) }}</div>
    <div v-if="card.decisionReason" class="muted">Reason: {{ card.decisionReason }}</div>

    <details>
      <summary>Plan ({{ card.plan.length }} step{{ card.plan.length === 1 ? '' : 's' }}) and evidence</summary>
      <ol v-if="card.plan.length">
        <li v-for="(step, i) in card.plan" :key="i">
          <span class="pill">{{ step.type }}</span> {{ step.description }}
          <template v-if="step.target"> — <a v-if="step.type === 'request'" :href="step.target" target="_blank" rel="noopener">{{ step.target }}</a><code v-else>{{ step.target }}</code></template>
        </li>
      </ol>
      <p v-else class="muted">This card has no automatic plan yet; it needs a hand-written fix.</p>
      <pre>{{ JSON.stringify(card.evidence, null, 2) }}</pre>
    </details>

    <div v-if="editing === card.id" class="actions">
      <textarea v-model="editText"></textarea>
      <div class="row" style="margin-top: .4rem">
        <button class="primary" @click="saveEdit(card)">Save params</button>
        <button @click="editing = null">Cancel</button>
      </div>
    </div>
    <div v-else-if="deleting === card.id" class="actions row">
      <input v-model="reason" placeholder="why delete this?" style="flex: 1" @keyup.enter="confirmDelete(card)" />
      <button class="danger" @click="confirmDelete(card)">Delete</button>
      <button @click="deleting = null">Cancel</button>
    </div>
    <div v-else-if="canDecide && ['PENDING', 'SNOOZED', 'FAILED'].includes(card.status)" class="actions row">
      <button class="primary" :disabled="busy === card.id" @click="act(card, () => api.go(card.id))">GO!</button>
      <button :disabled="busy === card.id" @click="startEdit(card)">Edit</button>
      <button class="danger" :disabled="busy === card.id" @click="deleting = card.id; reason = ''">Delete</button>
      <button :disabled="busy === card.id" @click="act(card, () => api.snooze(card.id, 24))">Snooze 24h</button>
    </div>
  </div>
</template>
